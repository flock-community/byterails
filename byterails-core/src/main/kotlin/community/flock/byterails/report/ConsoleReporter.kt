package community.flock.byterails.report

import community.flock.byterails.analysis.Site
import community.flock.byterails.check.CheckResult
import community.flock.byterails.check.Violation
import community.flock.byterails.check.ViolationKind
import java.util.Locale

/** Renders a [CheckResult] as the lines a developer reads in the build log. */
object ConsoleReporter {

    fun render(result: CheckResult): List<String> {
        val lines = mutableListOf<String>()
        result.warnings.forEach { lines += "byterails: warning: $it" }
        result.violations.forEach { lines += render(it); lines += "" }
        lines += summary(result)
        return lines
    }

    fun summary(result: CheckResult): String {
        val classes = String.format(Locale.ROOT, "%,d", result.classCount)
        val packages = String.format(Locale.ROOT, "%,d", result.packageCount)
        val count = result.violations.size
        val noun = if (count == 1) "violation" else "violations"
        return "byterails: $count $noun in $classes classes, $packages packages"
    }

    fun render(violation: Violation): List<String> {
        val lines = mutableListOf<String>()
        lines += "byterails: ${violation.kind.label.padEnd(12)} ${violation.className}"
        when (violation.kind) {
            ViolationKind.UNDECLARED_PACKAGE -> lines += row("package", violation.message.substringAfter("package "))
            ViolationKind.NAMING -> lines += row("class", violation.className.simpleName)
            else -> {
                lines += site(violation)
                if (violation.site !is Site.Field) lines += row("ref", violation.target?.name ?: "")
            }
        }
        violation.rule?.let { rule ->
            val where = listOfNotNull(rule.location?.toString(), rule.declaringPackage?.let { "in \"$it\"" }).joinToString("  ")
            lines += row("rule", if (where.isEmpty()) rule.text else "${rule.text.padEnd(40)} $where")
        }
        if (violation.kind == ViolationKind.NOT_ALLOWED) {
            lines += row("allows", violation.allows.ifEmpty { listOf("(nothing)") }.joinToString(", "))
        }
        violation.sourceFile?.let { file ->
            lines += row("source", if (violation.line != null) "$file:${violation.line}" else file)
        }
        violation.hint?.let { lines += row("hint", it) }
        return lines
    }

    private fun site(violation: Violation): String = when (val site = violation.site) {
        is Site.Field -> row("field", "${site.name} : ${violation.target?.name}")
        is Site.Method -> row("method", methodSignature(site))
        Site.ClassHeader, null -> row("class", "declaration")
    }

    private fun row(label: String, value: String) = "  ${label.padEnd(8)} $value"

    /** `place(Order) : void`, with simple names so the line stays short. */
    fun methodSignature(site: Site.Method): String {
        val (arguments, returnType) = Descriptors.parseMethod(site.descriptor)
        val name = when (site.name) {
            "<init>" -> "constructor"
            "<clinit>" -> "static initializer"
            else -> site.name
        }
        return "$name(${arguments.joinToString(", ")}) : $returnType"
    }
}
