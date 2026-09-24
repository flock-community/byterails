package community.flock.byterails.report

import community.flock.byterails.analysis.Site
import community.flock.byterails.check.CheckResult
import community.flock.byterails.check.Violation
import community.flock.byterails.check.ViolationGroup
import community.flock.byterails.check.ViolationKind
import java.util.Locale

/**
 * Renders a [CheckResult] as the lines a developer reads in the build log: one block per
 * [ViolationGroup], with the facts shared by the group once at the top and one line per member.
 */
object ConsoleReporter {

    /** Members printed per group before the rest is left to the JSON report. */
    const val MAX_MEMBERS = 10

    fun render(result: CheckResult): List<String> {
        val lines = mutableListOf<String>()
        result.warnings.forEach { lines += "byterails: warning: $it" }
        ViolationGroup.of(result.violations).forEach { lines += render(it); lines += "" }
        lines += summary(result)
        return lines
    }

    fun summary(result: CheckResult): String {
        val classes = String.format(Locale.ROOT, "%,d", result.classCount)
        val packages = String.format(Locale.ROOT, "%,d", result.packageCount)
        val count = result.violations.size
        val noun = if (count == 1) "violation" else "violations"
        val groups = ViolationGroup.of(result.violations).size
        val grouped = if (groups == 0) "" else " $groups ${if (groups == 1) "group" else "groups"},"
        return "byterails: $count $noun in$grouped $classes classes, $packages packages"
    }

    fun render(group: ViolationGroup): List<String> {
        val lines = mutableListOf<String>()
        val target = group.targetPackage?.let { " -> ${it.ifEmpty { "(default)" }}" } ?: ""
        lines += "byterails: ${group.kind.label.padEnd(12)} ${group.packageName.ifEmpty { "(default)" }}$target"
        when (group.kind) {
            ViolationKind.UNDECLARED_PACKAGE -> lines += row("package", group.detail ?: "")
            ViolationKind.WRONG_MODULE -> lines += row("module", group.detail ?: "")
            else -> {}
        }
        group.rule?.let { rule ->
            val where = listOfNotNull(rule.location?.toString(), rule.declaringPackage?.let { "in \"$it\"" }).joinToString("  ")
            lines += row("rule", if (where.isEmpty()) rule.text else "${rule.text.padEnd(40)} $where")
        }
        if (group.kind == ViolationKind.NOT_ALLOWED) {
            lines += row("allows", group.allows.ifEmpty { listOf("(nothing)") }.joinToString(", "))
        }
        group.hint?.let { lines += row("hint", it) }
        lines += members(group)
        return lines
    }

    private fun members(group: ViolationGroup): List<String> {
        val shown = group.members.take(MAX_MEMBERS)
        val columns = shown.map { violation ->
            listOfNotNull(
                member(violation),
                if (group.perClass) null else violation.target?.simpleName ?: "",
                source(violation) ?: "",
            )
        }
        val widths = columns[0].indices.map { column -> columns.maxOf { it[column].length } }
        val lines = columns.map { cells ->
            "  " + cells.mapIndexed { i, cell -> cell.padEnd(widths[i]) }.joinToString("  ").trimEnd()
        }.toMutableList()
        val rest = group.members.size - shown.size
        if (rest > 0) lines += "  ... and $rest more; the JSON report lists them all"
        return lines
    }

    /** `OrderService.place(Order) : void`, `Order.entityManager` or `Order`, with the package left to the header. */
    private fun member(violation: Violation): String {
        val cls = violation.className.simpleName
        return when (val site = violation.site) {
            is Site.Field -> "$cls.${site.name}"
            is Site.Method -> "$cls.${methodSignature(site)}"
            Site.ClassHeader, null -> cls
        }
    }

    private fun source(violation: Violation): String? =
        violation.sourceFile?.let { file -> if (violation.line != null) "$file:${violation.line}" else file }

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
