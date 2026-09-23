package community.flock.byterails.dsl

import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.NamePattern
import community.flock.byterails.model.NamingRules
import community.flock.byterails.model.PackageDeclaration
import community.flock.byterails.model.Prefix
import community.flock.byterails.model.Rule
import community.flock.byterails.model.RuleKind
import community.flock.byterails.model.RuleSet
import community.flock.byterails.model.SliceTemplate
import community.flock.byterails.model.SourceLocation
import community.flock.byterails.model.including

@DslMarker
annotation class ByterailsDsl

/**
 * Builds a [RuleSet]. This is the entry point of a `byterails.kts` file and can equally be
 * called from compiled code, for example in a test.
 */
fun byterails(block: ByterailsBuilder.() -> Unit): RuleSet = ByterailsBuilder().apply(block).build()

/**
 * @param group the group every rule written through this builder carries, or null for a rules file.
 *   The default rule sets are written with this builder and stamp their rules with `default:<id>`.
 */
@ByterailsDsl
class ByterailsBuilder internal constructor(private val group: String? = null) {

    private val rootRules = mutableListOf<Rule>()
    private val packages = mutableListOf<PackageDeclaration>()
    private var sliceTemplate: SliceTemplate? = null
    /** Rule sets applied by name, merged at [build] once it is known whether the file has a slice block. */
    private val included = mutableListOf<RuleSet>()

    /** Permits references to anything under [prefix] from every declared package. */
    fun allow(prefix: String) {
        rootRules += rule(RuleKind.ALLOW, prefix, group)
    }

    /** Forbids references to anything under [prefix] from every declared package. */
    fun deny(prefix: String) {
        rootRules += rule(RuleKind.DENY, prefix, group)
    }

    /** Declares that the package [name] and its sub-packages may exist, with their own rules. */
    fun pkg(name: String, block: PackageBuilder.() -> Unit = {}) {
        val location = SourceLocation.capture()
        packages += PackageBuilder(parsePrefix(name, location), location, group).apply(block).build()
    }

    /**
     * Declares the base package itself: the package every declaration in this file is relative to, as
     * configured in the build. Usually [PackageBuilder.flat], so that it holds the entry point and
     * nothing else and its siblings stay separate subtrees.
     */
    fun basePackage(block: PackageBuilder.() -> Unit = {}) {
        val location = SourceLocation.capture()
        packages += PackageBuilder(Prefix.ROOT, location, group).apply(block).build()
    }

    /**
     * Describes the structure every slice of the application has. Which slices exist is configured in
     * the build, as package names relative to the base package.
     */
    fun slice(block: SliceBuilder.() -> Unit) {
        val location = SourceLocation.capture()
        if (sliceTemplate != null) throw ConfigException("slice { } may appear only once", location)
        sliceTemplate = SliceBuilder(location, group).apply(block).build()
    }

    /**
     * Adds a rule set written with this DSL, which is how the default rule sets are applied. Its root
     * rules join the root block, its declarations go under the base package, and its `slice { }`
     * packages go into this file's `slice { }` block when there is one and under the base package
     * otherwise. Applied at [build], so it does not matter where in the file it is called.
     */
    fun include(ruleSet: RuleSet) {
        included += ruleSet
    }

    fun build(): RuleSet = included.fold(RuleSet(rootRules.toList(), packages.toList(), sliceTemplate)) { ruleSet, set ->
        ruleSet.including(set, sliced = ruleSet.sliceTemplate != null)
    }
}

@ByterailsDsl
class SliceBuilder internal constructor(private val location: SourceLocation?, private val group: String? = null) {
    private val exported = mutableListOf<Prefix>()
    private val rules = mutableListOf<Rule>()
    private var naming: NamingRules? = null
    private val packages = mutableListOf<PackageDeclaration>()

    /** A template package every slice may reference in every other slice, for example `api`. */
    fun exported(name: String) {
        exported += parsePrefix(name, SourceLocation.capture())
    }

    /** Rules of the slice root itself, inherited by the slice's packages. */
    fun allow(prefix: String) {
        rules += rule(RuleKind.ALLOW, prefix, group)
    }

    fun deny(prefix: String) {
        rules += rule(RuleKind.DENY, prefix, group)
    }

    /** Owned by the root of every slice together, and by nothing outside the slices. */
    fun exclusive(prefix: String) {
        rules += rule(RuleKind.EXCLUSIVE, prefix, group)
    }

    /** Naming for classes directly in a slice root. */
    fun naming(block: NamingBuilder.() -> Unit) {
        val location = SourceLocation.capture()
        if (naming != null) throw ConfigException("slice { } has more than one naming block", location)
        val patterns = NamingBuilder().apply(block).patterns
        if (patterns.isEmpty()) throw ConfigException("naming block of slice { } has no patterns", location)
        naming = NamingRules(patterns, location)
    }

    /** A package of the template, relative to each slice: `pkg("domain")` is `<slice>.domain`. */
    fun pkg(name: String, block: PackageBuilder.() -> Unit = {}) {
        val location = SourceLocation.capture()
        packages += PackageBuilder(parsePrefix(name, location), location, group).apply(block).build()
    }

    /** Adds the slice packages of a rule set written with this DSL, which is how a default rule set is applied inside `slice { }`. */
    fun include(ruleSet: RuleSet) {
        packages += ruleSet.sliceTemplate?.packages.orEmpty()
    }

    internal fun build(): SliceTemplate = SliceTemplate(exported.toList(), rules.toList(), naming, packages.toList(), location)
}

@ByterailsDsl
class PackageBuilder internal constructor(
    private val prefix: Prefix,
    private val location: SourceLocation?,
    private val group: String? = null,
) {
    private val rules = mutableListOf<Rule>()
    private var naming: NamingRules? = null
    private var isolated = false
    private var flat = false

    /**
     * Inherit nothing: not the root block, not enclosing declarations. Classes in this subtree may
     * reference only what this block lists and the subtree itself.
     */
    fun isolated() {
        isolated = true
    }

    /**
     * The package itself only. A class in a sub-package is undeclared unless another declaration covers
     * it, and such a declaration inherits nothing from this one.
     */
    fun flat() {
        flat = true
    }

    /** Permits references from this subtree to anything under [prefix]. */
    fun allow(prefix: String) {
        rules += rule(RuleKind.ALLOW, prefix, group)
    }

    /**
     * Permits references from this subtree to anything at all, short of what the root block denies and
     * what another package owns. For the entry point and the wiring of an application, which touch
     * every layer and every library.
     */
    fun allowAnything() {
        rules += Rule(RuleKind.ALLOW, Prefix.ROOT, SourceLocation.capture(), group)
    }

    /** Forbids references from this subtree to anything under [prefix]. Deny always wins over allow. */
    fun deny(prefix: String) {
        rules += rule(RuleKind.DENY, prefix, group)
    }

    /** Permits [prefix] here and forbids it in every package outside this subtree. */
    fun exclusive(prefix: String) {
        rules += rule(RuleKind.EXCLUSIVE, prefix, group)
    }

    /** Constrains the simple names of classes in this subtree. A class passes when one pattern matches. */
    fun naming(block: NamingBuilder.() -> Unit) {
        val location = SourceLocation.capture()
        if (naming != null) throw ConfigException("package \"${prefix.name}\" has more than one naming block", location)
        val patterns = NamingBuilder().apply(block).patterns
        if (patterns.isEmpty()) throw ConfigException("naming block of \"${prefix.name}\" has no patterns", location)
        naming = NamingRules(patterns, location)
    }

    internal fun build(): PackageDeclaration = PackageDeclaration(prefix, rules.toList(), naming, location, isolated, flat)
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

/**
 * One rule as written. Rules of a grouped builder carry the group; an exclusive gets a group of its
 * own, `<group>:<prefix>`, so that two exclusives of one rule set are never taken for one claim.
 */
private fun rule(kind: RuleKind, prefix: String, group: String?): Rule {
    val location = SourceLocation.capture()
    val parsed = parsePrefix(prefix, location)
    val ruleGroup = if (kind == RuleKind.EXCLUSIVE) group?.let { "$it:${parsed.name}" } else group
    return Rule(kind, parsed, location, ruleGroup)
}

private fun parsePrefix(text: String, location: SourceLocation?): Prefix =
    try {
        Prefix.parse(text)
    } catch (e: IllegalArgumentException) {
        throw ConfigException(e.message ?: "prefix \"$text\" is malformed", location)
    }
