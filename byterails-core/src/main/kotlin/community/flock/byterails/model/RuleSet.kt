package community.flock.byterails.model

/** Where a declaration or rule was written, when it came from a script. */
data class SourceLocation(val file: String, val line: Int) {
    override fun toString(): String = "$file:$line"

    companion object {
        /** The nearest script frame on the current stack, or null when the DSL is used from compiled code. */
        fun capture(): SourceLocation? =
            Thread.currentThread().stackTrace
                .firstOrNull { it.fileName?.endsWith(".kts") == true }
                ?.let { SourceLocation(it.fileName!!, it.lineNumber) }
    }
}

enum class RuleKind(val keyword: String) {
    ALLOW("allow"),
    DENY("deny"),
    EXCLUSIVE("exclusive"),
}

data class Rule(val kind: RuleKind, val prefix: Prefix, val location: SourceLocation?) {
    /** The rule as it was written, `deny("jakarta.persistence")`. */
    val text: String get() = "${kind.keyword}(\"${prefix.name}\")"
}

sealed interface NamePattern {
    val text: String

    fun matches(simpleName: String): Boolean

    data class EndsWith(val suffix: String) : NamePattern {
        override val text: String get() = "endsWith(\"$suffix\")"
        override fun matches(simpleName: String): Boolean = simpleName.endsWith(suffix)
    }

    data class StartsWith(val prefix: String) : NamePattern {
        override val text: String get() = "startsWith(\"$prefix\")"
        override fun matches(simpleName: String): Boolean = simpleName.startsWith(prefix)
    }

    class Matches(val regex: Regex) : NamePattern {
        override val text: String get() = "matches(\"${regex.pattern}\")"
        override fun matches(simpleName: String): Boolean = regex.matches(simpleName)
        override fun equals(other: Any?): Boolean = other is Matches && other.regex.pattern == regex.pattern
        override fun hashCode(): Int = regex.pattern.hashCode()
        override fun toString(): String = text
    }
}

/** A naming block: a class passes when at least one pattern matches its simple name. */
data class NamingRules(val patterns: List<NamePattern>, val location: SourceLocation?) {
    val text: String get() = "naming { ${patterns.joinToString("; ") { it.text }} }"
}

/** A declared package subtree with its own rules. */
data class PackageDeclaration(
    val prefix: Prefix,
    val rules: List<Rule>,
    val naming: NamingRules?,
    val location: SourceLocation?,
) {
    val name: String get() = prefix.name
}

/** The whole configuration: root rules, inherited by everything, plus the declared packages. */
data class RuleSet(
    val rootRules: List<Rule>,
    val packages: List<PackageDeclaration>,
)
