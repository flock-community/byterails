package community.flock.byterails.model

enum class Severity { ERROR, WARNING }

data class ConfigProblem(val severity: Severity, val message: String, val location: SourceLocation?) {
    override fun toString(): String = buildString {
        if (location != null) append(location).append(": ")
        append(message)
    }
}

/** Thrown when the configuration cannot be used. The message lists every problem found. */
class ConfigException(val problems: List<ConfigProblem>) : RuntimeException(render(problems)) {

    constructor(message: String, location: SourceLocation? = null) :
        this(listOf(ConfigProblem(Severity.ERROR, message, location)))

    companion object {
        private fun render(problems: List<ConfigProblem>): String {
            val errors = problems.filter { it.severity == Severity.ERROR }
            val header = if (errors.size == 1) "byterails: invalid configuration" else "byterails: ${errors.size} configuration errors"
            return errors.joinToString(separator = "\n", prefix = "$header\n") { "  $it" }
        }
    }
}
