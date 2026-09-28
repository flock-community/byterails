package community.flock.byterails.check

import community.flock.byterails.analysis.Site
import community.flock.byterails.model.ClassName
import community.flock.byterails.model.ConfigProblem
import community.flock.byterails.model.ModuleRoot
import community.flock.byterails.model.SourceLocation

enum class ViolationKind(val label: String) {
    UNDECLARED_PACKAGE("UNDECLARED"),
    /** A class compiled in a module whose package lies outside it, or a class of a module compiled elsewhere. */
    WRONG_MODULE("WRONG MODULE"),
    NOT_ALLOWED("NOT ALLOWED"),
    DENIED("DENIED"),
    EXCLUSIVE("EXCLUSIVE"),
    NAMING("NAMING"),
}

/**
 * The rule that decided a violation, as written, with the block that holds it, where it was written and
 * the rule set it came from.
 */
data class RuleRef(
    val text: String,
    val declaringPackage: String?,
    val location: SourceLocation?,
    /** The block as a message names it, `pkg("com.acme.domain")`, `slice { }` or `basePackage { }`; null for the root block. */
    val block: String? = null,
    /** The id of the default rule set the rule came from, or null for a rule the user wrote. */
    val ruleSet: String? = null,
) {
    /** `in pkg("com.acme.domain") at byterails.kts:14`, `at the top of byterails.kts:4` or `from the hexagonal rule set`. */
    val where: String get() {
        val at = location?.let { " at $it" } ?: ""
        return when {
            ruleSet != null -> "from the $ruleSet rule set$at"
            block != null -> "in $block$at"
            location != null -> "at the top of $location"
            else -> "in the root block"
        }
    }
}

/**
 * A package declaration as a message refers to it: for `NOT_ALLOWED` the one the class's package falls
 * under, for `UNDECLARED` the flat declaration just above the undeclared package, when there is one.
 */
data class DeclarationRef(
    val name: String,
    /** `pkg("com.acme.domain")`, `basePackage { }`, `slice { }` or the module's rules file. */
    val text: String,
    val location: SourceLocation?,
    val flat: Boolean,
    val isolated: Boolean,
    /** The id of the default rule set that declared the package, or null for a declaration the user wrote. */
    val ruleSet: String?,
) {
    /** `pkg("com.acme.domain") at byterails.kts:12`, or `pkg("com.acme.sales.domain") from the hexagonal rule set, isolated`. */
    val description: String get() = buildString {
        append(text)
        if (ruleSet != null) append(" from the ").append(ruleSet).append(" rule set")
        if (location != null) append(" at ").append(location)
        if (isolated) append(", isolated")
    }
}

/**
 * One place in a class where the referenced type was found, and the member a developer would recognise
 * it by: the name of a field or method, `the constructor`, `the class declaration`, or, for a lambda or
 * anonymous class, the method it was written in. Null when the compiler generated the member.
 */
data class Occurrence(val site: Site, val line: Int?, val member: String?)

/** Which module a class was compiled in and which module owns its package, for `WRONG_MODULE`. */
data class ModuleMismatch(val compiledIn: ModuleRoot?, val owner: ModuleRoot?)

/**
 * One class using one type it may not use, or one class in the wrong place or with the wrong name. A
 * reference violation lists every place the type was found in [occurrences]; the kinds that report the
 * class as a whole have none.
 */
data class Violation(
    val kind: ViolationKind,
    val className: ClassName,
    val target: ClassName?,
    val rule: RuleRef?,
    val occurrences: List<Occurrence>,
    /** For NOT_ALLOWED: the effective allow prefixes of the package, so the reader sees what it may use. */
    val allows: List<String>,
    val declaration: DeclarationRef?,
    val modules: ModuleMismatch?,
    val sourceFile: String?,
    /** The sentence for this violation alone, naming the class: what the JSON report carries. */
    val message: String,
    /** The sentence every violation with the same root cause shares, naming the package: the console header. */
    val headline: String,
    /** The two ways out, as one sentence. */
    val fix: String,
    /** A pointer for references every compiler emits, so the first run on a Kotlin project explains itself. */
    val hint: String? = null,
    /** The default rule sets among the allows: id to what the id stands for. */
    val ruleSets: Map<String, String> = emptyMap(),
    /** The class a lambda or anonymous class was written in, when this class is one; null when the class is reported under its own name. */
    val owner: ClassName? = null,
) {
    /** The class this violation is reported under: [owner] when there is one, else the class itself. */
    val reportedClass: ClassName get() = owner ?: className

    /** The distinct source lines of the occurrences, in order. */
    val lines: List<Int> get() = occurrences.mapNotNull { it.line }.distinct().sorted()
}

data class CheckResult(
    val violations: List<Violation>,
    val classCount: Int,
    val packageCount: Int,
    val warnings: List<ConfigProblem>,
    /** The module whose classes were checked, when the build has modules. */
    val module: String? = null,
) {
    val isClean: Boolean get() = violations.isEmpty()
}
