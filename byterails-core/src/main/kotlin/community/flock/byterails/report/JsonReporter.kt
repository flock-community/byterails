package community.flock.byterails.report

import community.flock.byterails.analysis.Site
import community.flock.byterails.check.CheckResult
import community.flock.byterails.check.Occurrence
import community.flock.byterails.check.Violation

/**
 * Renders a [CheckResult] as JSON with a stable schema. Schema version 2: a violation is one class
 * and one referenced type, with every place the type was found under `sites`.
 */
object JsonReporter {

    const val SCHEMA = 2

    fun render(result: CheckResult): String = buildString {
        append("{\n")
        append("  \"schema\": ").append(SCHEMA).append(",\n")
        append("  \"classes\": ").append(result.classCount).append(",\n")
        append("  \"packages\": ").append(result.packageCount).append(",\n")
        result.module?.let { append("  \"module\": ").append(quote(it)).append(",\n") }
        append("  \"warnings\": [")
        result.warnings.forEachIndexed { i, warning ->
            if (i > 0) append(",")
            append("\n    ").append(quote(warning.toString()))
        }
        if (result.warnings.isNotEmpty()) append("\n  ")
        append("],\n")
        append("  \"violations\": [")
        result.violations.forEachIndexed { i, violation ->
            if (i > 0) append(",")
            append("\n    ").append(render(violation))
        }
        if (result.violations.isNotEmpty()) append("\n  ")
        append("]\n")
        append("}\n")
    }

    private fun render(v: Violation): String {
        val fields = mutableListOf<String>()
        fields += "\"kind\": ${quote(v.kind.name)}"
        fields += "\"class\": ${quote(v.className.name)}"
        fields += "\"package\": ${quote(v.className.packageName)}"
        v.owner?.let { fields += "\"owner\": ${quote(it.name)}" }
        v.sourceFile?.let { fields += "\"sourceFile\": ${quote(it)}" }
        v.target?.let { fields += "\"target\": ${quote(it.name)}" }
        if (v.occurrences.isNotEmpty()) fields += "\"sites\": [${v.occurrences.joinToString(", ") { render(it) }}]"
        v.rule?.let { rule ->
            val parts = mutableListOf("\"text\": ${quote(rule.text)}")
            rule.declaringPackage?.let { parts += "\"package\": ${quote(it)}" }
            rule.block?.let { parts += "\"block\": ${quote(it)}" }
            rule.location?.let { parts += "\"location\": ${quote(it.toString())}" }
            rule.ruleSet?.let { parts += "\"ruleSet\": ${quote(it)}" }
            fields += "\"rule\": {${parts.joinToString(", ")}}"
        }
        if (v.allows.isNotEmpty()) fields += "\"allows\": [${v.allows.joinToString(", ") { quote(it) }}]"
        if (v.ruleSets.isNotEmpty()) fields += "\"ruleSets\": {${v.ruleSets.entries.joinToString(", ") { (id, label) -> "${quote(id)}: ${quote(label)}" }}}"
        v.declaration?.let { declaration ->
            val parts = mutableListOf("\"package\": ${quote(declaration.name)}", "\"text\": ${quote(declaration.text)}")
            declaration.location?.let { parts += "\"location\": ${quote(it.toString())}" }
            parts += "\"flat\": ${declaration.flat}"
            parts += "\"isolated\": ${declaration.isolated}"
            declaration.ruleSet?.let { parts += "\"ruleSet\": ${quote(it)}" }
            fields += "\"declaration\": {${parts.joinToString(", ")}}"
        }
        v.modules?.let { modules ->
            val parts = mutableListOf<String>()
            modules.compiledIn?.let { parts += "\"compiledIn\": ${quote(it.name)}" }
            modules.owner?.let { parts += "\"owner\": ${quote(it.name)}" }
            fields += "\"module\": {${parts.joinToString(", ")}}"
        }
        fields += "\"message\": ${quote(v.message)}"
        fields += "\"fix\": ${quote(v.fix)}"
        v.hint?.let { fields += "\"hint\": ${quote(it)}" }
        return "{${fields.joinToString(", ")}}"
    }

    private fun render(occurrence: Occurrence): String {
        val parts = mutableListOf<String>()
        when (val site = occurrence.site) {
            Site.ClassHeader -> parts += "\"kind\": \"class\""
            is Site.Field -> {
                parts += "\"kind\": \"field\""
                parts += "\"name\": ${quote(site.name)}"
            }
            is Site.Method -> {
                parts += "\"kind\": \"method\""
                parts += "\"name\": ${quote(site.name)}"
                parts += "\"descriptor\": ${quote(site.descriptor)}"
            }
        }
        occurrence.line?.let { parts += "\"line\": $it" }
        occurrence.member?.let { parts += "\"member\": ${quote(it)}" } ?: run { parts += "\"generated\": true" }
        return "{${parts.joinToString(", ")}}"
    }

    private fun quote(text: String): String = buildString {
        append('"')
        for (c in text) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }
}
