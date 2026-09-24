package community.flock.byterails.check

import community.flock.byterails.analysis.Site
import community.flock.byterails.model.ClassName
import community.flock.byterails.model.ConfigProblem
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

/** The rule that decided a violation, as written, with where it was written and which package holds it. */
data class RuleRef(val text: String, val declaringPackage: String?, val location: SourceLocation?)

data class Violation(
    val kind: ViolationKind,
    val className: ClassName,
    val site: Site?,
    val line: Int?,
    val target: ClassName?,
    val rule: RuleRef?,
    /** For NOT_ALLOWED: the effective allow prefixes of the package, so the reader sees what it may use. */
    val allows: List<String>,
    val sourceFile: String?,
    val message: String,
    /** A pointer for references every compiler emits, so the first run on a Kotlin project explains itself. */
    val hint: String? = null,
)

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
