package community.flock.byterails.check

import community.flock.byterails.analysis.AnalyzedClass
import community.flock.byterails.model.ClassName
import community.flock.byterails.model.ConfigProblem
import community.flock.byterails.model.EffectiveRule
import community.flock.byterails.model.ExclusiveGroup
import community.flock.byterails.model.PackageDeclaration
import community.flock.byterails.model.Prefix
import community.flock.byterails.model.ResolvedRuleSet
import community.flock.byterails.model.Rule
import community.flock.byterails.model.RuleKind
import community.flock.byterails.model.RuleSet
import community.flock.byterails.rules.DefaultRuleSet

/**
 * Evaluates analysed classes against a rule set.
 *
 * For every reference from a class in package P to a type T the order is: T inside P's own
 * declaration, T exclusive to another package, a deny in P's effective rules, an allow in P's
 * effective rules, otherwise not allowed. Every place a class refers to T is one occurrence of the
 * same violation.
 */
class Checker(private val ruleSet: RuleSet, private val warnings: List<ConfigProblem> = emptyList()) {

    private val resolved = ResolvedRuleSet(ruleSet)

    private val currentModule = ruleSet.modules.firstOrNull { it.name == ruleSet.module }

    fun check(classes: Sequence<AnalyzedClass>): CheckResult {
        val violations = mutableListOf<Violation>()
        var classCount = 0
        val packages = HashSet<String>()
        for (cls in classes) {
            classCount++
            packages += cls.name.packageName
            violations += checkClass(cls)
        }
        val packageRules = violations.map { it.className.packageName }.distinct().sorted()
            .mapNotNull { packageName -> resolved.declarationFor(packageName)?.let { packageName to packageRules(it) } }
            .toMap()
        return CheckResult(violations.sortedWith(ORDER), classCount, packages.size, warnings, ruleSet.module, packageRules)
    }

    /** What a package may and may not use, rule by rule with where each was written. */
    private fun packageRules(declaration: PackageDeclaration) =
        PackageRules(declarationRef(declaration), resolved.effectiveRules(declaration).map { ruleRef(it.rule, it.origin) })

    fun checkClass(cls: AnalyzedClass): List<Violation> {
        wrongModule(cls)?.let { return listOf(it) }
        val declaration = resolved.declarationFor(cls.name.packageName)
            ?: return listOf(undeclared(cls))
        val owner = ownerOf(cls)
        val violations = mutableListOf<Violation>()
        naming(cls, declaration, owner)?.let { violations += it }
        val rules = resolved.effectiveRules(declaration)
        val decisions = LinkedHashMap<ClassName, Decision?>()
        val occurrences = HashMap<ClassName, MutableList<Occurrence>>()
        for (reference in cls.references) {
            val target = reference.target
            val decision = if (target in decisions) decisions[target] else evaluate(declaration, rules, target).also { decisions[target] = it }
            if (decision == null) continue
            occurrences.getOrPut(target) { mutableListOf() } += Occurrence(reference.site, reference.line, MemberNames.member(cls, reference.site))
        }
        for ((target, decision) in decisions) {
            if (decision != null) violations += violation(cls, owner, declaration, target, decision, occurrences.getValue(target))
        }
        return violations
    }

    /** What the rules say about a reference to [target] from a class under [declaration]; null when it is fine. */
    private fun evaluate(declaration: PackageDeclaration, rules: List<EffectiveRule>, target: ClassName): Decision? {
        if (declaration.covers(target)) return null
        resolved.exclusiveGroups.firstOrNull { it.prefix.covers(target) && !resolved.isInside(declaration, it) }
            ?.let { return Decision.Exclusive(it) }
        rules.firstOrNull { it.rule.kind == RuleKind.DENY && it.rule.prefix.covers(target) }
            ?.let { return Decision.Denied(it) }
        if (rules.any { it.rule.kind != RuleKind.DENY && it.rule.prefix.covers(target) }) return null
        val granted = rules.filter { it.rule.kind != RuleKind.DENY }
        val sets = granted.mapNotNull { DefaultRuleSet.of(it.rule) }.distinct()
        val allows = granted
            .map { effective -> DefaultRuleSet.of(effective.rule)?.let { "[${it.id}]" } ?: effective.rule.prefix.name }
            .distinct()
            .sorted()
        return Decision.NotAllowed(allows, sets.associate { it.id to it.allowsLabel }, HINTS.firstOrNull { (prefix, _) -> prefix.covers(target) }?.second)
    }

    /** The class a lambda or anonymous class is reported under, or null for a class reported under its own name. */
    private fun ownerOf(cls: AnalyzedClass): ClassName? = MemberNames.owner(cls).takeIf { it != cls.name }

    private fun violation(
        cls: AnalyzedClass,
        owner: ClassName?,
        declaration: PackageDeclaration,
        target: ClassName,
        decision: Decision,
        occurrences: List<Occurrence>,
    ): Violation {
        val declared = declarationRef(declaration)
        return when (decision) {
            is Decision.Exclusive -> {
                val group = decision.group
                val texts = Messages.exclusive(cls.name, target, group.ownerDescription, group.owners[0].name, declared)
                Violation(
                    ViolationKind.EXCLUSIVE, cls.name, target, ruleRef(group.rule, group.owners[0]), occurrences, emptyList(), declared, null,
                    cls.sourceFile, texts.message, texts.headline, texts.fix, owner = owner,
                )
            }
            is Decision.Denied -> {
                val rule = ruleRef(decision.rule.rule, decision.rule.origin)
                val texts = Messages.denied(cls.name, target, rule)
                Violation(
                    ViolationKind.DENIED, cls.name, target, rule, occurrences, emptyList(), declared, null,
                    cls.sourceFile, texts.message, texts.headline, texts.fix, owner = owner,
                )
            }
            is Decision.NotAllowed -> {
                val texts = Messages.notAllowed(cls.name, target, declared)
                Violation(
                    ViolationKind.NOT_ALLOWED, cls.name, target, null, occurrences, decision.allows, declared, null,
                    cls.sourceFile, texts.message, texts.headline, texts.fix, decision.hint, decision.ruleSets, owner,
                )
            }
        }
    }

    private fun naming(cls: AnalyzedClass, declaration: PackageDeclaration, owner: ClassName?): Violation? {
        if (!isNamingCandidate(cls)) return null
        val (holder, naming) = resolved.namingFor(declaration) ?: return null
        if (naming.patterns.any { it.matches(cls.name.simpleName) }) return null
        val texts = Messages.naming(cls.name, naming.patterns)
        return Violation(
            ViolationKind.NAMING, cls.name, null,
            RuleRef(naming.text, holder.name, naming.location, holder.text, ruleSetOf(holder)), emptyList(), emptyList(), null, null,
            cls.sourceFile, texts.message, texts.headline, texts.fix, owner = owner,
        )
    }

    private fun isNamingCandidate(cls: AnalyzedClass): Boolean =
        !cls.name.isNested && !cls.isSynthetic && !cls.isGenerated &&
            cls.name.simpleName != "package-info" && cls.name.simpleName != "module-info"

    /**
     * A class is compiled in the wrong place when it lies outside the module being checked, or inside a
     * module while another module or no module is being checked. Its rules are the wrong ones too, so
     * nothing else is reported for it.
     */
    private fun wrongModule(cls: AnalyzedClass): Violation? {
        if (ruleSet.modules.isEmpty()) return null
        val ownerModule = ruleSet.modules.firstOrNull { it.prefix.covers(cls.name) }
        if (ownerModule == currentModule) return null
        val mismatch = ModuleMismatch(currentModule, ownerModule)
        val texts = Messages.wrongModule(cls.name, mismatch)
        return Violation(
            ViolationKind.WRONG_MODULE, cls.name, null, null, emptyList(), emptyList(), null, mismatch,
            cls.sourceFile, texts.message, texts.headline, texts.fix, owner = ownerOf(cls),
        )
    }

    private fun undeclared(cls: AnalyzedClass): Violation {
        val nearest = resolved.declarations
            .filter { cls.name.packageName.startsWith(it.name + ".") || it.prefix.coversPackage(cls.name.packageName) }
            .maxByOrNull { it.prefix.depth }
            ?.let(::declarationRef)
        val texts = Messages.undeclared(cls.name, nearest)
        return Violation(
            ViolationKind.UNDECLARED_PACKAGE, cls.name, null, null, emptyList(), emptyList(), nearest, null,
            cls.sourceFile, texts.message, texts.headline, texts.fix, owner = ownerOf(cls),
        )
    }

    private fun ruleRef(rule: Rule, origin: PackageDeclaration?) =
        RuleRef(rule.text, origin?.name, rule.location, origin?.text, DefaultRuleSet.of(rule)?.id)

    private fun declarationRef(declaration: PackageDeclaration) =
        DeclarationRef(declaration.name, declaration.text, declaration.location, declaration.flat, declaration.isolated, ruleSetOf(declaration))

    /** The id of the rule set a declaration came from, read off its group. */
    private fun ruleSetOf(declaration: PackageDeclaration): String? =
        declaration.group?.takeIf { it.startsWith(DefaultRuleSet.GROUP) }?.removePrefix(DefaultRuleSet.GROUP)?.substringBefore(':')

    private sealed interface Decision {
        class Exclusive(val group: ExclusiveGroup) : Decision
        class Denied(val rule: EffectiveRule) : Decision
        class NotAllowed(val allows: List<String>, val ruleSets: Map<String, String>, val hint: String?) : Decision
    }

    private companion object {
        /** References the compilers write into every class, which a first-time user has not thought about. */
        val HINTS: List<Pair<Prefix, String>> = listOf(
            Prefix.parse("org.jetbrains.annotations") to
                "the Kotlin compiler puts these nullability annotations on every declaration; allow(\"org.jetbrains.annotations\") in the root block",
            Prefix.parse("kotlin") to "the Kotlin compiler references its runtime from every class; allow(\"kotlin\") in the root block",
            Prefix.parse("java.lang") to "every class references java.lang; allow(\"java.lang\") in the root block",
        )

        val ORDER: Comparator<Violation> = compareBy<Violation> { it.className.packageName }
            .thenBy { it.className.name }
            .thenBy { it.kind.ordinal }
            .thenBy { it.target?.name ?: "" }
    }
}

/** Helper for callers that want a violation's class name without the site. */
val Violation.packageName: String get() = className.packageName

/** Renders a class name for messages: the dotted name. */
fun ClassName.display(): String = name
