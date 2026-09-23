package community.flock.byterails.validation

import community.flock.byterails.model.ConfigProblem
import community.flock.byterails.rules.DefaultRuleSet
import community.flock.byterails.model.EffectiveRule
import community.flock.byterails.model.PackageDeclaration
import community.flock.byterails.model.Prefix
import community.flock.byterails.model.ResolvedRuleSet
import community.flock.byterails.model.Rule
import community.flock.byterails.model.RuleKind
import community.flock.byterails.model.RuleSet
import community.flock.byterails.model.Severity
import community.flock.byterails.model.SourceLocation

/**
 * Checks a [RuleSet] before any class file is read. Errors stop the build; warnings are printed.
 */
object RuleSetValidator {

    fun validate(ruleSet: RuleSet): List<ConfigProblem> {
        val resolved = ResolvedRuleSet(ruleSet)
        val problems = mutableListOf<ConfigProblem>()
        ruleSet.sliceTemplate?.let {
            problems += error("the rules file has a slice { } block, but no slices are configured; name them in the build", it.location)
        }
        duplicateDeclarations(resolved, problems)
        exclusiveAtRoot(ruleSet, problems)
        exclusiveClashes(resolved, problems)
        deadAllows(resolved, problems)
        emptyNaming(resolved, problems)
        cycles(resolved, problems)
        kotlinNames(resolved, problems)
        return problems
    }

    private fun duplicateDeclarations(resolved: ResolvedRuleSet, problems: MutableList<ConfigProblem>) {
        resolved.declarations.groupBy { it.prefix }.values
            .filter { it.size > 1 }
            .forEach { duplicates ->
                val first = duplicates.first()
                duplicates.drop(1).forEach { again ->
                    problems += error(
                        "package \"${again.name}\" is declared twice; the first declaration is at ${first.location ?: "an unknown location"}",
                        again.location,
                    )
                }
            }
    }

    private fun exclusiveAtRoot(ruleSet: RuleSet, problems: MutableList<ConfigProblem>) {
        ruleSet.rootRules.filter { it.kind == RuleKind.EXCLUSIVE }.forEach {
            problems += error("${it.text} at the root is a plain allow; exclusive needs a package to own it", it.location)
        }
    }

    private fun exclusiveClashes(resolved: ResolvedRuleSet, problems: MutableList<ConfigProblem>) {
        val exclusives = resolved.exclusives
        for (i in exclusives.indices) {
            for (j in i + 1 until exclusives.size) {
                val a = exclusives[i]
                val b = exclusives[j]
                if (a.origin === b.origin) continue
                if (a.rule.group != null && a.rule.group == b.rule.group) continue
                val overlap = a.rule.prefix.covers(b.rule.prefix) || b.rule.prefix.covers(a.rule.prefix)
                if (!overlap) continue
                val aOwner = a.origin!!
                val bOwner = b.origin!!
                // Narrowing inside the owner's own subtree is fine: the child claims part of what the parent owns.
                val narrowing = (aOwner.covers(bOwner.prefix) && a.rule.prefix.covers(b.rule.prefix)) ||
                    (bOwner.covers(aOwner.prefix) && b.rule.prefix.covers(a.rule.prefix))
                if (narrowing) continue
                problems += error(
                    "${b.rule.text} in \"${bOwner.name}\" clashes with ${a.rule.text} in \"${aOwner.name}\" " +
                        "(${a.rule.location ?: "unknown location"}); only one package can own a prefix",
                    b.rule.location,
                )
            }
        }
    }

    private fun deadAllows(resolved: ResolvedRuleSet, problems: MutableList<ConfigProblem>) {
        val reported = HashSet<Pair<Rule, Rule>>()
        val rootAllows = resolved.ruleSet.rootRules.filter { it.kind == RuleKind.ALLOW }.map { EffectiveRule(it, null) }
        val rootDenies = resolved.ruleSet.rootRules.filter { it.kind == RuleKind.DENY }.map { EffectiveRule(it, null) }

        // An allow shadowed by a deny in the same block, or in an enclosing block, can never take effect.
        for (allow in rootAllows) {
            for (deny in rootDenies) shadowedByDeny(allow, deny, reported, problems)
        }
        for (declaration in resolved.declarations) {
            val enclosingDenies = if (declaration.isolated) {
                declaration.rules.filter { it.kind == RuleKind.DENY }.map { EffectiveRule(it, declaration) }
            } else {
                rootDenies + resolved.chain(declaration).flatMap { enclosing ->
                    enclosing.rules.filter { it.kind == RuleKind.DENY }.map { EffectiveRule(it, enclosing) }
                }
            }
            declaration.rules.filter { it.kind == RuleKind.ALLOW }.forEach { rule ->
                val allow = EffectiveRule(rule, declaration)
                for (deny in enclosingDenies) shadowedByDeny(allow, deny, reported, problems)
            }
        }

        // An allow of something another package owns exclusively can never take effect either.
        val allAllows = rootAllows + resolved.declarations.flatMap { declaration ->
            declaration.rules.filter { it.kind == RuleKind.ALLOW }.map { EffectiveRule(it, declaration) }
        }
        for (allow in allAllows) {
            if (DefaultRuleSet.of(allow.rule) != null) continue
            for (group in resolved.exclusiveGroups) {
                if (!group.prefix.covers(allow.rule.prefix)) continue
                if (resolved.isInside(allow.origin, group)) continue
                if (!reported.add(allow.rule to group.rule)) continue
                problems += error(
                    "${allow.rule.text} in ${describe(allow)} can never apply: ${group.ownerDescription} owns it through " +
                        "${group.rule.text} (${group.rule.location ?: "unknown location"})",
                    allow.rule.location,
                )
            }
        }
    }

    private fun shadowedByDeny(
        allow: EffectiveRule,
        deny: EffectiveRule,
        reported: MutableSet<Pair<Rule, Rule>>,
        problems: MutableList<ConfigProblem>,
    ) {
        if (!deny.rule.prefix.covers(allow.rule.prefix)) return
        if (DefaultRuleSet.of(allow.rule) != null) return
        if (!reported.add(allow.rule to deny.rule)) return
        problems += error(
            "${allow.rule.text} in ${describe(allow)} can never apply: it is shadowed by ${deny.rule.text} in " +
                "${describe(deny)} (${deny.rule.location ?: "unknown location"}), and deny always wins",
            allow.rule.location,
        )
    }

    private fun emptyNaming(resolved: ResolvedRuleSet, problems: MutableList<ConfigProblem>) {
        resolved.declarations.forEach { declaration ->
            val naming = declaration.naming ?: return@forEach
            if (naming.patterns.isEmpty()) {
                problems += error("naming block of \"${declaration.name}\" has no patterns", naming.location)
            }
        }
    }

    /** A allows B and B allows A: legal, but worth knowing. Reported once per strongly connected component. */
    private fun cycles(resolved: ResolvedRuleSet, problems: MutableList<ConfigProblem>) {
        val declarations = resolved.declarations
        val index = declarations.withIndex().associate { (i, d) -> d to i }
        val edges = declarations.map { from ->
            // An allow of the root prefix grants everything and describes no dependency, so it draws no edge.
            val targets = resolved.effectiveRules(from)
                .filter { it.rule.kind != RuleKind.DENY && !it.rule.isExported && !it.rule.prefix.isRoot }
                .map { it.rule.prefix }
            declarations.filter { to ->
                to !== from && targets.any { it.covers(to.prefix) || to.covers(it) }
            }.map { index.getValue(it) }
        }
        for (component in Tarjan(declarations.size, edges).components()) {
            if (component.size < 2) continue
            val names = component.sorted().map { declarations[it].name }
            problems += ConfigProblem(
                Severity.WARNING,
                "packages depend on each other in a cycle: ${names.joinToString(" -> ")} -> ${names.first()}",
                declarations[component.min()].location,
            )
        }
    }

    private fun kotlinNames(resolved: ResolvedRuleSet, problems: MutableList<ConfigProblem>) {
        val rules = resolved.ruleSet.rootRules + resolved.declarations.flatMap { it.rules }
        rules.forEach { rule ->
            val mapping = KOTLIN_TO_JVM.entries.firstOrNull { (kotlinName, _) -> kotlinName.covers(rule.prefix) }
                ?: return@forEach
            problems += ConfigProblem(
                Severity.WARNING,
                "${rule.text}: Kotlin types under \"${mapping.key}\" compile to \"${mapping.value}\" in bytecode, " +
                    "so this rule only matches the Kotlin standard library helper classes; add a rule for \"${mapping.value}\"",
                rule.location,
            )
        }
    }

    private fun describe(rule: EffectiveRule): String = rule.origin?.let { "\"${it.name}\"" } ?: "the root block"

    private fun error(message: String, location: SourceLocation?) = ConfigProblem(Severity.ERROR, message, location)

    private val KOTLIN_TO_JVM: Map<Prefix, String> = listOf(
        "kotlin.collections" to "java.util",
        "kotlin.String" to "java.lang.String",
        "kotlin.Any" to "java.lang.Object",
        "kotlin.Throwable" to "java.lang.Throwable",
        "kotlin.Comparable" to "java.lang.Comparable",
        "kotlin.CharSequence" to "java.lang.CharSequence",
        "kotlin.Number" to "java.lang.Number",
        "kotlin.Enum" to "java.lang.Enum",
        "kotlin.Int" to "int or java.lang.Integer",
        "kotlin.Long" to "long or java.lang.Long",
        "kotlin.Short" to "short or java.lang.Short",
        "kotlin.Byte" to "byte or java.lang.Byte",
        "kotlin.Char" to "char or java.lang.Character",
        "kotlin.Boolean" to "boolean or java.lang.Boolean",
        "kotlin.Float" to "float or java.lang.Float",
        "kotlin.Double" to "double or java.lang.Double",
        "kotlin.annotation" to "java.lang.annotation",
    ).associate { (k, v) -> Prefix.parse(k) to v }
}

/** Tarjan's strongly connected components over a small adjacency list. */
private class Tarjan(private val size: Int, private val edges: List<List<Int>>) {
    private var counter = 0
    private val index = IntArray(size) { -1 }
    private val low = IntArray(size)
    private val onStack = BooleanArray(size)
    private val stack = ArrayDeque<Int>()
    private val result = mutableListOf<List<Int>>()

    fun components(): List<List<Int>> {
        for (v in 0 until size) if (index[v] < 0) visit(v)
        return result
    }

    private fun visit(v: Int) {
        index[v] = counter
        low[v] = counter
        counter++
        stack.addLast(v)
        onStack[v] = true
        for (w in edges[v]) {
            if (index[w] < 0) {
                visit(w)
                low[v] = minOf(low[v], low[w])
            } else if (onStack[w]) {
                low[v] = minOf(low[v], index[w])
            }
        }
        if (low[v] == index[v]) {
            val component = mutableListOf<Int>()
            while (true) {
                val w = stack.removeLast()
                onStack[w] = false
                component += w
                if (w == v) break
            }
            result += component
        }
    }
}
