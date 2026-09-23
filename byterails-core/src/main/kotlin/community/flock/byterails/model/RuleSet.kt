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

data class Rule(
    val kind: RuleKind,
    val prefix: Prefix,
    val location: SourceLocation?,
    /**
     * Rules that one template line expanded into share a group. Exclusives of one group are owned
     * together, so every slice's copy permits the others; groups starting with `exported:` are the
     * allows a slice template derives for exported packages and are left out of cycle detection.
     */
    val group: String? = null,
) {
    /** The rule as it was written, `deny("jakarta.persistence")`. */
    val text: String get() = "${kind.keyword}(\"${prefix.name}\")"

    val isExported: Boolean get() = group?.startsWith(EXPORTED_GROUP) == true

    companion object {
        const val EXPORTED_GROUP = "exported:"
    }
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

/**
 * A declared package subtree with its own rules.
 *
 * An [isolated] declaration inherits nothing: not the root block, not its enclosing declarations.
 * Its classes may reference only what the declaration itself lists and its own subtree, which is
 * how a domain package is kept free of every external dependency whatever the rest of the file allows.
 *
 * A [flat] declaration covers the package itself and nothing beneath it: a class in a sub-package
 * is undeclared unless another declaration covers it, and the flat declaration neither encloses
 * sub-package declarations nor passes its rules or naming down to them.
 */
data class PackageDeclaration(
    val prefix: Prefix,
    val rules: List<Rule>,
    val naming: NamingRules?,
    val location: SourceLocation?,
    val isolated: Boolean = false,
    val flat: Boolean = false,
) {
    val name: String get() = prefix.name

    /** True when [other] lies in the subtree this declaration covers; for a flat declaration, only the package itself. */
    fun covers(other: Prefix): Boolean = if (flat) other == prefix else prefix.covers(other)

    fun covers(className: ClassName): Boolean = if (flat) className.packageName == prefix.name else prefix.covers(className)

    fun coversPackage(packageName: String): Boolean = if (flat) packageName == prefix.name else prefix.coversPackage(packageName)

    /** True when a rule prefixed to [candidate] would point into this declaration's tree, either way round. */
    fun touches(candidate: Prefix): Boolean = candidate.covers(prefix) || covers(candidate)
}

/**
 * The whole configuration: root rules, inherited by everything, plus the declared packages.
 *
 * [sliceTemplate] is the structure of one slice as the rules file describes it. Which slices exist is
 * configured in the build, and [withSlices] turns the template into ordinary declarations; a rule set
 * that still carries a template cannot be checked.
 */
data class RuleSet(
    val rootRules: List<Rule>,
    val packages: List<PackageDeclaration>,
    val sliceTemplate: SliceTemplate? = null,
)
