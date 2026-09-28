package community.flock.byterails.model

enum class Severity { ERROR, WARNING }

/** Where the configuration failed, which decides the first line of the message. */
enum class ConfigPhase {
    /** The rules file does not compile. */
    COMPILE,
    /** The rules file threw while it ran. */
    RUN,
    /** The rules are inconsistent: what the DSL and the validator find. */
    RULES,
    /** The build's settings do not fit the rules file, or are wrong on their own. */
    SETTINGS,
}

data class ConfigProblem(
    val severity: Severity,
    val message: String,
    val location: SourceLocation?,
    /** The column within the line, when known; compiler diagnostics have one. */
    val column: Int? = null,
    /** The text of the source line, attached by whoever has the file, so the message can show it. */
    val sourceLine: String? = null,
) {
    override fun toString(): String = buildString {
        if (location != null) {
            append(location)
            if (column != null) append(':').append(column)
            append(": ")
        }
        append(message)
    }
}

/** Thrown when the configuration cannot be used. The message lists every problem found under one header. */
class ConfigException(
    val problems: List<ConfigProblem>,
    val phase: ConfigPhase = ConfigPhase.RULES,
    cause: Throwable? = null,
) : RuntimeException(render(problems, phase), cause) {

    constructor(message: String, location: SourceLocation? = null, phase: ConfigPhase = ConfigPhase.RULES) :
        this(listOf(ConfigProblem(Severity.ERROR, message, location)), phase)

    companion object {
        fun render(problems: List<ConfigProblem>, phase: ConfigPhase): String {
            // In file order, so the reader walks the file once; problems without a location come first.
            val errors = problems.filter { it.severity == Severity.ERROR }
                .sortedWith(compareBy({ it.location?.file ?: "" }, { it.location?.line ?: 0 }, { it.column ?: 0 }))
            return (listOf(header(errors, phase)) + errors.flatMap { lines(it) }).joinToString("\n")
        }

        private fun header(errors: List<ConfigProblem>, phase: ConfigPhase): String {
            val files = errors.mapNotNull { it.location?.file }.distinct()
            val count = "${errors.size} ${if (errors.size == 1) "problem" else "problems"}"
            return "byterails: " + when (phase) {
                ConfigPhase.COMPILE -> "${files.firstOrNull() ?: "the rules file"} does not compile"
                ConfigPhase.RUN -> "${files.firstOrNull() ?: "the rules file"} failed while running"
                ConfigPhase.RULES -> "$count in ${if (files.isEmpty()) "the rules" else names(files)}"
                ConfigPhase.SETTINGS -> "$count in the build settings"
            }
        }

        private fun names(files: List<String>): String =
            if (files.size == 1) files[0] else files.dropLast(1).joinToString(", ") + " and " + files.last()

        /** The problem, then the source line it is on and a caret under the column, when known. */
        private fun lines(problem: ConfigProblem): List<String> {
            val lines = mutableListOf("  $problem")
            val source = problem.sourceLine ?: return lines
            val trimmed = source.trimStart()
            lines += "      $trimmed"
            problem.column?.let { column ->
                val caret = (column - 1 - (source.length - trimmed.length)).coerceAtLeast(0)
                lines += "      " + " ".repeat(caret) + "^"
            }
            return lines
        }
    }
}
