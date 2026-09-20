package community.flock.byterails.check

import community.flock.byterails.analysis.AnalyzedClass
import community.flock.byterails.analysis.Reference
import community.flock.byterails.analysis.Site
import community.flock.byterails.model.ClassName
import community.flock.byterails.model.ConfigProblem
import community.flock.byterails.model.EffectiveRule
import community.flock.byterails.model.PackageDeclaration
import community.flock.byterails.model.Prefix
import community.flock.byterails.model.ResolvedRuleSet
import community.flock.byterails.model.Rule
import community.flock.byterails.model.RuleKind
import community.flock.byterails.model.RuleSet

/**
 * Evaluates analysed classes against a rule set.
 *
 * For every reference from a class in package P to a type T the order is: T inside P's own
 * declaration, T exclusive to another package, a deny in P's effective rules, an allow in P's
 * effective rules, otherwise not allowed.
 */
class Checker(ruleSet: RuleSet, private val warnings: List<ConfigProblem> = emptyList()) {

    private val resolved = ResolvedRuleSet(ruleSet)

    fun check(classes: Sequence<AnalyzedClass>): CheckResult {
        val violations = mutableListOf<Violation>()
        var classCount = 0
        val packages = HashSet<String>()
        for (cls in classes) {
            classCount++
            packages += cls.name.packageName
            violations += checkClass(cls)
        }
        return CheckResult(violations.sortedWith(ORDER), classCount, packages.size, warnings)
    }

    fun checkClass(cls: AnalyzedClass): List<Violation> {
        val declaration = resolved.declarationFor(cls.name.packageName)
            ?: return listOf(undeclared(cls))
        val found = LinkedHashMap<Any, Violation>()
        naming(cls, declaration)?.let { found[it.kind] = it }
        val rules = resolved.effectiveRules(declaration)
        for (reference in cls.references) {
            val violation = evaluate(cls, declaration, rules, reference) ?: continue
            found.putIfAbsent(Triple(violation.kind, violation.target, violation.site), violation)
        }
        return found.values.toList()
    }

    private fun evaluate(cls: AnalyzedClass, declaration: PackageDeclaration, rules: List<EffectiveRule>, reference: Reference): Violation? {
        val target = reference.target
        if (declaration.prefix.covers(target)) return null

        resolved.exclusives.firstOrNull { it.rule.prefix.covers(target) && !resolved.isInside(declaration, it.origin!!) }
            ?.let { exclusive ->
                return violation(
                    ViolationKind.EXCLUSIVE, cls, reference, exclusive,
                    "${cls.name} references $target, which \"${exclusive.originName}\" owns through ${exclusive.rule.text}",
                )
            }

        rules.firstOrNull { it.rule.kind == RuleKind.DENY && it.rule.prefix.covers(target) }
            ?.let { deny ->
                return violation(ViolationKind.DENIED, cls, reference, deny, "${cls.name} references $target, denied by ${deny.rule.text}")
            }

        if (rules.any { it.rule.kind != RuleKind.DENY && it.rule.prefix.covers(target) }) return null

        val allows = rules.filter { it.rule.kind != RuleKind.DENY }.map { it.rule.prefix.name }.distinct().sorted()
        return Violation(
            ViolationKind.NOT_ALLOWED, cls.name, reference.site, reference.line, target, null, allows, cls.sourceFile,
            "${cls.name} references $target, which \"${declaration.name}\" is not allowed to use",
            hint = HINTS.firstOrNull { (prefix, _) -> prefix.covers(target) }?.second,
        )
    }

    private fun violation(kind: ViolationKind, cls: AnalyzedClass, reference: Reference, decided: EffectiveRule, message: String) =
        Violation(kind, cls.name, reference.site, reference.line, reference.target, ruleRef(decided.rule, decided.origin), emptyList(), cls.sourceFile, message)

    private fun naming(cls: AnalyzedClass, declaration: PackageDeclaration): Violation? {
        if (!isNamingCandidate(cls)) return null
        val (owner, naming) = resolved.namingFor(declaration) ?: return null
        if (naming.patterns.any { it.matches(cls.name.simpleName) }) return null
        val tried = naming.patterns.joinToString(", ") { it.text }
        return Violation(
            ViolationKind.NAMING, cls.name, null, null, null,
            RuleRef(naming.text, owner.name, naming.location), emptyList(), cls.sourceFile,
            "${cls.name} matches none of $tried",
        )
    }

    private fun isNamingCandidate(cls: AnalyzedClass): Boolean =
        !cls.name.isNested && !cls.isSynthetic && !cls.isGenerated &&
            cls.name.simpleName != "package-info" && cls.name.simpleName != "module-info"

    private fun undeclared(cls: AnalyzedClass): Violation {
        val nearest = resolved.declarations
            .filter { cls.name.packageName.startsWith(it.name + ".") || it.prefix.coversPackage(cls.name.packageName) }
            .maxByOrNull { it.prefix.depth }
        val hint = nearest?.let { "; the nearest declared package is \"${it.name}\"" } ?: ""
        return Violation(
            ViolationKind.UNDECLARED_PACKAGE, cls.name, null, null, null, null, emptyList(), cls.sourceFile,
            "package \"${cls.name.packageName.ifEmpty { "(default)" }}\" is not declared$hint",
        )
    }

    private fun ruleRef(rule: Rule, origin: PackageDeclaration?) = RuleRef(rule.text, origin?.name, rule.location)

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
            .thenBy { it.line ?: Int.MAX_VALUE }
            .thenBy { it.target?.name ?: "" }
            .thenBy { siteKey(it.site) }

        fun siteKey(site: Site?): String = when (site) {
            null -> ""
            Site.ClassHeader -> "0"
            is Site.Field -> "1${site.name}"
            is Site.Method -> "2${site.name}${site.descriptor}"
        }
    }
}

/** Helper for callers that want a violation's class name without the site. */
val Violation.packageName: String get() = className.packageName

/** Renders a class name for messages: the dotted name. */
fun ClassName.display(): String = name
