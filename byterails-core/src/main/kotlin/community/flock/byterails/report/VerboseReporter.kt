package community.flock.byterails.report

import community.flock.byterails.analysis.Site
import community.flock.byterails.check.CheckResult
import community.flock.byterails.check.Occurrence
import community.flock.byterails.check.Violation
import community.flock.byterails.check.ViolationKind

/**
 * Renders a [CheckResult] for whoever debugs the rules: every reference on one line, unfolded and
 * uncapped, with the fully qualified names and the JVM descriptor as they stand in the class file,
 * followed by the effective rules of every package that has a violation. The build tools show these
 * lines with their own verbose switch or with byterails' `verbose` setting.
 */
object VerboseReporter {

    fun render(result: CheckResult): List<String> {
        val lines = mutableListOf<String>()
        result.violations.forEach { lines += render(it) }
        for ((packageName, rules) in result.packageRules) {
            lines += "byterails: rules of ${packageName.ifEmpty { "(default)" }}: ${rules.declaration.description}"
            if (rules.rules.isEmpty()) lines += "byterails:   (none)"
            rules.rules.forEach { lines += "byterails:   ${it.text} ${it.where}" }
        }
        return lines
    }

    /** One line per occurrence, or one line for a violation that reports the class as a whole. */
    fun render(violation: Violation): List<String> {
        if (violation.occurrences.isEmpty()) {
            val rule = violation.rule?.let { "; ${it.text} ${it.where}" } ?: ""
            return listOf(line(violation.kind, violation.sourceFile, null, "${violation.message}$rule"))
        }
        return violation.occurrences.map { occurrence ->
            val generated = if (occurrence.member == null) " (generated)" else ""
            line(violation.kind, violation.sourceFile, occurrence.line, "${site(violation, occurrence)} uses ${violation.target}$generated; ${reason(violation)}")
        }
    }

    private fun line(kind: ViolationKind, file: String?, line: Int?, text: String): String {
        val location = (file ?: "(no source file)") + (line?.let { ":$it" } ?: "")
        return "byterails: ${kind.label.padEnd(12)} $location  $text"
    }

    /** The class and member as the class file spells them: `com.acme.Order.place(Lcom/acme/Item;)V`. */
    private fun site(violation: Violation, occurrence: Occurrence): String = when (val site = occurrence.site) {
        Site.ClassHeader -> "${violation.className} (class declaration)"
        is Site.Field -> "${violation.className}.${site.name}"
        is Site.Method -> "${violation.className}.${site.name}${site.descriptor}"
    }

    private fun reason(violation: Violation): String = when (violation.kind) {
        ViolationKind.NOT_ALLOWED -> {
            val may = if (violation.allows.isEmpty()) "nothing" else violation.allows.joinToString(", ")
            "no rule allows it; ${violation.declaration?.description ?: "the package"} may use $may"
        }
        else -> violation.rule?.let { "${it.text} ${it.where}" } ?: violation.message
    }
}
