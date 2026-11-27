package community.flock.byterails.report

import community.flock.byterails.analysis.Site
import community.flock.byterails.check.CheckResult
import community.flock.byterails.check.Violation
import community.flock.byterails.check.ViolationGroup
import community.flock.byterails.check.ViolationKind
import community.flock.byterails.model.ClassName
import java.util.Locale

/**
 * Renders a [CheckResult] as the lines a developer reads in the build log. Every undeclared package
 * goes into one table. The other violations are grouped by where they come from: a heading per slice,
 * under it a heading per package with the facts its violations share, the declaration and what the
 * package may use, and under that one block per [ViolationGroup] with a sentence, the fix and one line
 * per class. Violations marked [Violation.unlisted] are counted and not printed.
 */
object ConsoleReporter {

    /** Member lines printed per group before the rest is left to the verbose output and the JSON report. */
    const val MAX_MEMBERS = 10

    /** Packages printed in the undeclared table before the rest is left to the verbose output. */
    const val MAX_PACKAGES = 20

    /** Distinct source lines named per member line. */
    const val MAX_LINES = 6

    /** Members named per member line. */
    const val MAX_NAMES = 3

    /**
     * @param verboseSwitch how the tool that is running turns verbose output on, for the line that says
     *   what was left out: `--verbose`, `-Pbyterails.verbose=true` or `-Dbyterails.verbose=true`.
     */
    fun render(result: CheckResult, verboseSwitch: String = "--verbose"): List<String> {
        val lines = mutableListOf<String>()
        val groups = listed(result)
        val undeclared = groups.filter { it.kind == ViolationKind.UNDECLARED_PACKAGE }
        if (undeclared.isNotEmpty()) {
            lines += undeclaredTable(undeclared, verboseSwitch)
            lines += ""
        }
        val declared = groups.filter { it.kind != ViolationKind.UNDECLARED_PACKAGE }
        val sliced = declared.any { it.slice != null }
        // Packages outside the slices first, then slice by slice.
        declared.groupBy { it.slice }.entries.sortedBy { it.key ?: "" }.forEach { (slice, inSlice) ->
            val packages = inSlice.groupBy { it.packageName }
            if (sliced) {
                val count = inSlice.sumOf { it.members.size }
                val name = slice?.let { "slice $it" } ?: "outside the slices"
                lines += "byterails: $name: $count ${plural(count, "violation")} in ${packages.size} ${plural(packages.size, "package")}"
                lines += ""
            }
            packages.values.forEach {
                lines += packageSection(it, verboseSwitch)
                lines += ""
            }
        }
        lines += legend(groups)
        result.warnings.forEach { lines += "byterails: warning: $it" }
        lines += summary(result)
        return lines
    }

    fun summary(result: CheckResult): String {
        val count = result.violations.size
        val unlisted = result.violations.count { it.unlisted }
        val hidden = if (unlisted == 0) "" else " (${number(unlisted)} not listed)"
        val blocks = blocks(result)
        val grouped = if (blocks == 0) "" else " $blocks ${plural(blocks, "group")},"
        val warnings = if (result.warnings.isEmpty()) "" else ", ${result.warnings.size} ${plural(result.warnings.size, "warning")}"
        return "byterails: ${number(count)} ${plural(count, "violation")}$hidden in$grouped ${number(result.classCount)} ${plural(result.classCount, "class", "classes")}, " +
            "${number(result.packageCount)} ${plural(result.packageCount, "package")}$warnings"
    }

    /** The blocks the console shows: one per group, with every undeclared package in one table. */
    fun blocks(result: CheckResult): Int {
        val groups = listed(result)
        val undeclared = groups.count { it.kind == ViolationKind.UNDECLARED_PACKAGE }
        return groups.size - undeclared + if (undeclared > 0) 1 else 0
    }

    /** The groups of the violations the console prints. */
    private fun listed(result: CheckResult): List<ViolationGroup> = ViolationGroup.of(result.violations.filterNot { it.unlisted })

    /**
     * Every group of one package under one heading. What the groups share is said once: the package,
     * its declaration, and what it may use when a reference no rule allows is among them.
     */
    private fun packageSection(groups: List<ViolationGroup>, verboseSwitch: String): List<String> {
        val first = groups[0]
        val count = groups.sumOf { it.members.size }
        val lines = mutableListOf("byterails: package ${first.packageName.ifEmpty { "(default)" }}: $count ${plural(count, "violation")}")
        groups.firstNotNullOfOrNull { it.declaration }?.let { lines += row("declared", it.description) }
        groups.firstOrNull { it.kind == ViolationKind.NOT_ALLOWED }?.let { lines += mayUse(it, "  ") }
        groups.forEach { lines += block(it, verboseSwitch, nested = true) }
        return lines
    }

    /** One group on its own, with the package in its sentence and the facts about the package as rows. */
    fun render(group: ViolationGroup, verboseSwitch: String = "--verbose"): List<String> {
        if (group.kind == ViolationKind.UNDECLARED_PACKAGE) return undeclaredTable(listOf(group), verboseSwitch)
        return block(group, verboseSwitch, nested = false)
    }

    private fun block(group: ViolationGroup, verboseSwitch: String, nested: Boolean): List<String> {
        val indent = if (nested) "    " else "  "
        val lines = mutableListOf(if (nested) "  ${group.kind.label.padEnd(12)} ${group.clause}" else header(group.kind, group.headline))
        if (!nested && group.kind == ViolationKind.NOT_ALLOWED) {
            group.declaration?.let { lines += row("package", it.description) }
            lines += mayUse(group, indent)
        }
        group.rule?.let { lines += row("rule", "${it.text} ${it.where}", indent) }
        lines += row("fix", group.fix, indent)
        group.hint?.let { lines += row("hint", it, indent) }
        lines += members(group, verboseSwitch, indent)
        return lines
    }

    private fun mayUse(group: ViolationGroup, indent: String): List<String> {
        val allows = if (group.allows.isEmpty()) {
            "nothing" + if (group.declaration?.isolated == true) "; the package is isolated" else ""
        } else {
            group.allows.joinToString(", ")
        }
        return wrapped("may use", allows, indent)
    }

    private fun members(group: ViolationGroup, verboseSwitch: String, indent: String): List<String> {
        val rows = if (group.perClass) {
            group.members.groupBy { it.reportedClass }.map { (owner, members) ->
                Row(members.firstNotNullOfOrNull { it.sourceFile }, emptyList(), display(owner))
            }
        } else {
            group.members.groupBy { it.reportedClass to it.target }.map { (key, members) ->
                val (owner, target) = key
                val occurrences = members.flatMap { it.occurrences }
                // Members in the order a reader finds them in the source; a member without a line number comes last.
                val named = occurrences.filter { it.member != null }.groupBy { it.member!! }
                    .entries.sortedBy { (_, at) -> at.mapNotNull { it.line }.minOrNull() ?: Int.MAX_VALUE }
                    .map { it.key }
                val generated = occurrences.filter { it.member == null }.map { it.site }.distinct().size
                val where = if (named.isNotEmpty()) {
                    list(named) + if (generated > 0) " (+$generated generated ${plural(generated, "member")})" else ""
                } else {
                    list(occurrences.map { siteName(it.site) }.distinct())
                }
                Row(members.firstNotNullOfOrNull { it.sourceFile }, members.flatMap { it.lines }.distinct().sorted(), "${display(owner)} uses ${display(target!!)} in $where")
            }
        }
        val sorted = rows.sortedWith(compareBy<Row> { it.file ?: "" }.thenBy { it.lines.firstOrNull() ?: Int.MAX_VALUE }.thenBy { it.text })
        val shown = sorted.take(MAX_MEMBERS)
        val width = shown.maxOf { it.location.length }
        val lines = shown.map { "$indent${it.location.padEnd(width)}  ${it.text}".trimEnd() }.toMutableList()
        val rest = sorted.size - shown.size
        if (rest > 0) lines += "$indent... and $rest more; $verboseSwitch prints them all"
        return lines
    }

    private class Row(val file: String?, val lines: List<Int>, val text: String) {
        val location: String get() {
            val name = file ?: "(no source file)"
            if (lines.isEmpty()) return name
            val shown = lines.take(MAX_LINES).joinToString(",")
            return "$name:$shown" + if (lines.size > MAX_LINES) ",..." else ""
        }
    }

    private fun undeclaredTable(groups: List<ViolationGroup>, verboseSwitch: String): List<String> {
        val packages = groups.sortedBy { it.packageName }
        val single = packages.size == 1
        val lines = mutableListOf<String>()
        lines += header(ViolationKind.UNDECLARED_PACKAGE, if (single) packages[0].headline else "${packages.size} packages are not declared")
        lines += row(
            "fix",
            if (single) packages[0].fix else "declare each with pkg(\"...\") in byterails.kts, or move its classes into a declared package; only declared packages may exist",
        )
        val shown = packages.take(MAX_PACKAGES)
        val names = shown.map { it.packageName.ifEmpty { "(default)" } }
        val counts = shown.map { "${it.members.size} ${plural(it.members.size, "class", "classes")}" }
        val nameWidth = names.maxOf { it.length }
        val countWidth = counts.maxOf { it.length }
        shown.forEachIndexed { i, group ->
            val files = list(group.members.mapNotNull { it.sourceFile }.distinct())
            val note = if (single) "" else group.declaration?.let { near ->
                val at = near.location?.let { " at $it" } ?: ""
                if (near.flat) "   under flat ${near.text}$at, which covers no sub-packages" else "   nearest: ${near.text}$at"
            }.orEmpty()
            lines += "  ${names[i].padEnd(nameWidth)}  ${counts[i].padEnd(countWidth)}  $files$note".trimEnd()
        }
        val rest = packages.size - shown.size
        if (rest > 0) lines += "  ... and $rest more ${plural(rest, "package")}; $verboseSwitch lists every package and class"
        return lines
    }

    /** One line per base or default rule set that appeared in an allows list, saying what its token stands for. */
    private fun legend(groups: List<ViolationGroup>): List<String> =
        groups.flatMap { it.ruleSets.entries }.distinctBy { it.key }.sortedBy { it.key }
            .map { (id, label) -> "byterails: [$id] stands for $label" }

    private fun header(kind: ViolationKind, headline: String) = "byterails: ${kind.label.padEnd(12)} $headline"

    private fun row(label: String, value: String, indent: String = "  ") = "$indent${label.padEnd(8)} $value"

    /** A row whose value is wrapped at commas, so a long list of allows stays readable. */
    private fun wrapped(label: String, value: String, indent: String, width: Int = 100): List<String> {
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (part in value.split(", ")) {
            if (current.isNotEmpty() && current.length + part.length + 2 > width) {
                lines += current.append(",").toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(", ")
            current.append(part)
        }
        lines += current.toString()
        return lines.mapIndexed { i, line -> if (i == 0) row(label, line, indent) else "$indent${"".padEnd(8)} $line" }
    }

    /** `a, b, c and 2 more`. */
    private fun list(names: List<String>): String {
        val shown = names.take(MAX_NAMES)
        val rest = names.size - shown.size
        return shown.joinToString(", ") + if (rest > 0) " and $rest more" else ""
    }

    /** `JavaUser.Point` for a nested class, `OrderService` otherwise. */
    private fun display(name: ClassName): String = name.simpleName.replace('$', '.')

    private fun siteName(site: Site): String = when (site) {
        Site.ClassHeader -> "the class declaration"
        is Site.Field -> site.name
        is Site.Method -> when (site.name) {
            "<init>" -> "the constructor"
            "<clinit>" -> "the static initializer"
            else -> site.name
        }
    }

    private fun number(n: Int) = String.format(Locale.ROOT, "%,d", n)

    private fun plural(n: Int, singular: String, plural: String = singular + "s") = if (n == 1) singular else plural

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
