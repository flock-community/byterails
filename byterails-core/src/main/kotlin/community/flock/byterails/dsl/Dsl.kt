package community.flock.byterails.dsl

import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.NamePattern
import community.flock.byterails.model.NamingRules
import community.flock.byterails.model.PackageDeclaration
import community.flock.byterails.model.Prefix
import community.flock.byterails.model.Rule
import community.flock.byterails.model.RuleKind
import community.flock.byterails.model.RuleSet
import community.flock.byterails.model.SourceLocation

@DslMarker
annotation class ByterailsDsl

/**
 * Builds a [RuleSet]. This is the entry point of a `byterails.kts` file and can equally be
 * called from compiled code, for example in a test.
 */
fun byterails(block: ByterailsBuilder.() -> Unit): RuleSet = ByterailsBuilder().apply(block).build()

@ByterailsDsl
class ByterailsBuilder internal constructor() {

    private val rootRules = mutableListOf<Rule>()
    private val packages = mutableListOf<PackageDeclaration>()

    /** Permits references to anything under [prefix] from every declared package. */
    fun allow(prefix: String) {
        rootRules += rule(RuleKind.ALLOW, prefix)
    }

    /** Forbids references to anything under [prefix] from every declared package. */
    fun deny(prefix: String) {
        rootRules += rule(RuleKind.DENY, prefix)
    }

    /** Declares that the package [name] and its sub-packages may exist, with their own rules. */
    fun pkg(name: String, block: PackageBuilder.() -> Unit = {}) {
        val location = SourceLocation.capture()
        packages += PackageBuilder(parsePrefix(name, location), location).apply(block).build()
    }

    fun build(): RuleSet = RuleSet(rootRules.toList(), packages.toList())
}

@ByterailsDsl
class PackageBuilder internal constructor(
    private val prefix: Prefix,
    private val location: SourceLocation?,
) {
    private val rules = mutableListOf<Rule>()
    private var naming: NamingRules? = null

    /** Permits references from this subtree to anything under [prefix]. */
    fun allow(prefix: String) {
        rules += rule(RuleKind.ALLOW, prefix)
    }

    /** Forbids references from this subtree to anything under [prefix]. Deny always wins over allow. */
    fun deny(prefix: String) {
        rules += rule(RuleKind.DENY, prefix)
    }

    /** Permits [prefix] here and forbids it in every package outside this subtree. */
    fun exclusive(prefix: String) {
        rules += rule(RuleKind.EXCLUSIVE, prefix)
    }

    /** Constrains the simple names of classes in this subtree. A class passes when one pattern matches. */
    fun naming(block: NamingBuilder.() -> Unit) {
        val location = SourceLocation.capture()
        if (naming != null) throw ConfigException("package \"${prefix.name}\" has more than one naming block", location)
        val patterns = NamingBuilder().apply(block).patterns
        if (patterns.isEmpty()) throw ConfigException("naming block of \"${prefix.name}\" has no patterns", location)
        naming = NamingRules(patterns, location)
    }

    internal fun build(): PackageDeclaration = PackageDeclaration(prefix, rules.toList(), naming, location)
}

@ByterailsDsl
class NamingBuilder internal constructor() {
    internal val patterns = mutableListOf<NamePattern>()

    fun endsWith(suffix: String) {
        patterns += NamePattern.EndsWith(requireText(suffix, "endsWith"))
    }

    fun startsWith(prefix: String) {
        patterns += NamePattern.StartsWith(requireText(prefix, "startsWith"))
    }

    fun matches(regex: Regex) {
        patterns += NamePattern.Matches(regex)
    }

    fun matches(pattern: String) {
        val location = SourceLocation.capture()
        val regex = try {
            Regex(pattern)
        } catch (e: IllegalArgumentException) {
            throw ConfigException("naming pattern \"$pattern\" is not a valid regular expression: ${e.message}", location)
        }
        patterns += NamePattern.Matches(regex)
    }

    private fun requireText(text: String, keyword: String): String {
        if (text.isEmpty()) throw ConfigException("naming pattern $keyword(\"\") is empty", SourceLocation.capture())
        return text
    }
}

private fun rule(kind: RuleKind, prefix: String): Rule {
    val location = SourceLocation.capture()
    return Rule(kind, parsePrefix(prefix, location), location)
}

private fun parsePrefix(text: String, location: SourceLocation?): Prefix =
    try {
        Prefix.parse(text)
    } catch (e: IllegalArgumentException) {
        throw ConfigException(e.message ?: "prefix \"$text\" is malformed", location)
    }
