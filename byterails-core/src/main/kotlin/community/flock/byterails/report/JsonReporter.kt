package community.flock.byterails.report

import community.flock.byterails.analysis.Site
import community.flock.byterails.check.CheckResult
import community.flock.byterails.check.Violation

/** Renders a [CheckResult] as JSON with a stable schema. Schema version 1. */
object JsonReporter {

    fun render(result: CheckResult): String = buildString {
        append("{\n")
        append("  \"schema\": 1,\n")
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
        v.site?.let { site ->
            fields += when (site) {
                Site.ClassHeader -> "\"site\": {\"kind\": \"class\"}"
                is Site.Field -> "\"site\": {\"kind\": \"field\", \"name\": ${quote(site.name)}}"
                is Site.Method -> "\"site\": {\"kind\": \"method\", \"name\": ${quote(site.name)}, \"descriptor\": ${quote(site.descriptor)}}"
            }
        }
        v.line?.let { fields += "\"line\": $it" }
        v.target?.let { fields += "\"target\": ${quote(it.name)}" }
        v.rule?.let { rule ->
            val parts = mutableListOf("\"text\": ${quote(rule.text)}")
            rule.declaringPackage?.let { parts += "\"package\": ${quote(it)}" }
            rule.location?.let { parts += "\"location\": ${quote(it.toString())}" }
            fields += "\"rule\": {${parts.joinToString(", ")}}"
        }
        if (v.allows.isNotEmpty()) fields += "\"allows\": [${v.allows.joinToString(", ") { quote(it) }}]"
        v.sourceFile?.let { fields += "\"sourceFile\": ${quote(it)}" }
        fields += "\"message\": ${quote(v.message)}"
        v.hint?.let { fields += "\"hint\": ${quote(it)}" }
        return "{${fields.joinToString(", ")}}"
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
