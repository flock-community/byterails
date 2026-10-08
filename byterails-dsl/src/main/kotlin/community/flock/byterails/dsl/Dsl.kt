package community.flock.byterails.dsl

import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.ConfigProblem
import community.flock.byterails.model.DeclarationRole
import community.flock.byterails.model.Export
import community.flock.byterails.model.NamePattern
import community.flock.byterails.model.NamingRules
import community.flock.byterails.model.PackageDeclaration
import community.flock.byterails.model.Prefix
import community.flock.byterails.model.Rule
import community.flock.byterails.model.RuleKind
import community.flock.byterails.model.RuleSet
import community.flock.byterails.model.Severity
import community.flock.byterails.model.SliceTemplate
import community.flock.byterails.model.SourceLocation
import community.flock.byterails.model.including

@DslMarker
annotation class ByterailsDsl

/**
 * Builds a [RuleSet]. This is the entry point of a `byterails.kts` file and can equally be
 * called from compiled code, for example in a test.
 *
 * @throws ConfigException for a malformed prefix, an empty or invalid naming pattern, a second naming
 *   block or a second slice block, with every such problem in one exception. A rules file collects the
 *   same problems into the rule set instead, so the validator adds its own before anything is reported.
 */
fun byterails(block: ByterailsBuilder.() -> Unit): RuleSet {
    val ruleSet = ByterailsBuilder().apply(block).build()
    if (ruleSet.problems.isNotEmpty()) throw ConfigException(ruleSet.problems)
    return ruleSet
}

/**
 * @param group the group every rule written through this builder carries, or null for a rules file.
 *   The default rule sets are written with this builder and stamp their rules with `default:<id>`.
 */
@ByterailsDsl
class ByterailsBuilder internal constructor(private val group: String? = null) {

    private val rootRules = mutableListOf<Rule>()
    private val packages = mutableListOf<PackageDeclaration>()
    private var sliceTemplate: SliceTemplate? = null
    private val exported = mutableListOf<Export>()
    /** Rule sets applied by name, merged at [build] once it is known whether the file has a slice block. */
    private val included = mutableListOf<RuleSet>()
    /** What was wrong in what the file said, in the order found; a malformed line is left out of the rules and reported. */
    private val problems = mutableListOf<ConfigProblem>()

    /** Permits references to anything under [prefix] from every declared package. */
    fun allow(prefix: String) {
        rule(RuleKind.ALLOW, prefix, group, problems)?.let { rootRules += it }
    }

    /** Forbids references to anything under [prefix] from every declared package. */
    fun deny(prefix: String) {
        rule(RuleKind.DENY, prefix, group, problems)?.let { rootRules += it }
    }

    /** Declares that the package [name] and its sub-packages may exist, with their own rules. */
    fun pkg(name: String, block: PackageBuilder.() -> Unit = {}) {
        val location = SourceLocation.capture()
        val prefix = parsePrefix(name, location, "pkg", problems)
        // The block still runs when the name is malformed, so its own problems are found in the same run.
        val declaration = PackageBuilder(prefix ?: Prefix.ROOT, "pkg(\"$name\")", location, group, DeclarationRole.PACKAGE, problems).apply(block).build()
        if (prefix != null) packages += declaration
    }

    /**
     * Declares the base package itself: the package every declaration in this file is relative to, as
     * configured in the build. Usually [PackageBuilder.flat], so that it holds the entry point and
     * nothing else and its siblings stay separate subtrees.
     */
    fun basePackage(block: PackageBuilder.() -> Unit = {}) {
        val location = SourceLocation.capture()
        packages += PackageBuilder(Prefix.ROOT, "basePackage { }", location, group, DeclarationRole.BASE_PACKAGE, problems).apply(block).build()
    }

    /**
     * In the rules file of a module: a package of this module, relative to the module, that every other
     * module may reference, for example `api`. Nothing else of a module can be referenced from another
     * module. A root rules file cannot export anything.
     */
    fun exported(name: String) {
        val location = SourceLocation.capture()
        parsePrefix(name, location, "exported", problems)?.let { exported += Export(it, location) }
    }

    /**
     * Describes the structure every slice of the application has. Which slices exist is configured in
     * the build, as package names relative to the base package.
     */
    fun slice(block: SliceBuilder.() -> Unit) {
        val location = SourceLocation.capture()
        val template = SliceBuilder(location, group, problems).apply(block).build()
        val first = sliceTemplate
        if (first == null) {
            sliceTemplate = template
        } else {
            problems += problem("slice { } appears twice; the first is at ${first.location ?: "an unknown location"}, and a rules file describes one slice", location)
        }
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

    /** The rule set as written, with what was wrong in it under [RuleSet.problems]. */
    fun build(): RuleSet = included.fold(RuleSet(rootRules.toList(), packages.toList(), sliceTemplate, exported.toList(), problems = problems.toList())) { ruleSet, set ->
        ruleSet.including(set, sliced = ruleSet.sliceTemplate != null)
    }
}

@ByterailsDsl
class SliceBuilder internal constructor(
    private val location: SourceLocation?,
    private val group: String? = null,
    private val problems: MutableList<ConfigProblem>,
) {
    private val exported = mutableListOf<Prefix>()
    private val rules = mutableListOf<Rule>()
    private var naming: NamingRules? = null
    private val packages = mutableListOf<PackageDeclaration>()

    /** A template package every slice may reference in every other slice, for example `api`. */
    fun exported(name: String) {
        parsePrefix(name, SourceLocation.capture(), "exported", problems)?.let { exported += it }
    }

    /** Rules of the slice root itself, inherited by the slice's packages. */
    fun allow(prefix: String) {
        rule(RuleKind.ALLOW, prefix, group, problems)?.let { rules += it }
    }

    fun deny(prefix: String) {
        rule(RuleKind.DENY, prefix, group, problems)?.let { rules += it }
    }

    /** Owned by the root of every slice together, and by nothing outside the slices. */
    fun exclusive(prefix: String) {
        rule(RuleKind.EXCLUSIVE, prefix, group, problems)?.let { rules += it }
    }

    /** Naming for classes directly in a slice root. */
    fun naming(block: NamingBuilder.() -> Unit) {
        naming = namingBlock("slice { }", naming, block, problems)
    }

    /** A package of the template, relative to each slice: `pkg("domain")` is `<slice>.domain`. */
    fun pkg(name: String, block: PackageBuilder.() -> Unit = {}) {
        val location = SourceLocation.capture()
        val prefix = parsePrefix(name, location, "pkg", problems)
        val declaration = PackageBuilder(prefix ?: Prefix.ROOT, "pkg(\"$name\")", location, group, DeclarationRole.PACKAGE, problems).apply(block).build()
        if (prefix != null) packages += declaration
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
    /** The declaration as a message names it: `pkg("com.acme.domain")` or `basePackage { }`. */
    private val text: String,
    private val location: SourceLocation?,
    private val group: String? = null,
    private val role: DeclarationRole = DeclarationRole.PACKAGE,
    private val problems: MutableList<ConfigProblem>,
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
        rule(RuleKind.ALLOW, prefix, group, problems)?.let { rules += it }
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
        rule(RuleKind.DENY, prefix, group, problems)?.let { rules += it }
    }

    /** Permits [prefix] here and forbids it in every package outside this subtree. */
    fun exclusive(prefix: String) {
        rule(RuleKind.EXCLUSIVE, prefix, group, problems)?.let { rules += it }
    }

    /** Constrains the simple names of classes in this subtree. A class passes when one pattern matches. */
    fun naming(block: NamingBuilder.() -> Unit) {
        naming = namingBlock(text, naming, block, problems)
    }

    internal fun build(): PackageDeclaration = PackageDeclaration(prefix, rules.toList(), naming, location, isolated, flat, group, role)
}

@ByterailsDsl
class NamingBuilder internal constructor(private val problems: MutableList<ConfigProblem>) {
    internal val patterns = mutableListOf<NamePattern>()

    /** How many patterns were written, valid or not, so an empty block is told apart from a block of invalid ones. */
    internal var written = 0

    fun endsWith(suffix: String) {
        written++
        if (hasText(suffix, "endsWith")) patterns += NamePattern.EndsWith(suffix)
    }

    fun startsWith(prefix: String) {
        written++
        if (hasText(prefix, "startsWith")) patterns += NamePattern.StartsWith(prefix)
    }

    fun matches(regex: Regex) {
        written++
        patterns += NamePattern.Matches(regex)
    }

    fun matches(pattern: String) {
        written++
        val location = SourceLocation.capture()
        try {
            patterns += NamePattern.Matches(Regex(pattern))
        } catch (e: IllegalArgumentException) {
            val reason = e.message?.lineSequence()?.firstOrNull()?.trim() ?: "it does not parse"
            problems += problem("matches(\"$pattern\") is not a valid regular expression: $reason", location)
        }
    }

    private fun hasText(text: String, keyword: String): Boolean {
        if (text.isNotEmpty()) return true
        problems += problem("$keyword(\"\") is empty; give it some text", SourceLocation.capture())
        return false
    }
}

/** Runs a naming block for [owner] and returns the rules to keep: the first block wins, and an empty block is a problem. */
private fun namingBlock(owner: String, existing: NamingRules?, block: NamingBuilder.() -> Unit, problems: MutableList<ConfigProblem>): NamingRules? {
    val location = SourceLocation.capture()
    val builder = NamingBuilder(problems).apply(block)
    return when {
        existing != null -> {
            problems += problem("$owner has two naming blocks; the first is at ${existing.location ?: "an unknown location"}, and one block takes several patterns", location)
            existing
        }
        builder.written == 0 -> {
            problems += problem("the naming block of $owner has no patterns; add endsWith, startsWith or matches", location)
            null
        }
        builder.patterns.isEmpty() -> null
        else -> NamingRules(builder.patterns.toList(), location)
    }
}

/**
 * One rule as written, or null with a problem recorded when the prefix is malformed. Rules of a grouped
 * builder carry the group; an exclusive gets a group of its own, `<group>:<prefix>`, so that two
 * exclusives of one rule set are never taken for one claim.
 */
private fun rule(kind: RuleKind, prefix: String, group: String?, problems: MutableList<ConfigProblem>): Rule? {
    val location = SourceLocation.capture()
    val parsed = parsePrefix(prefix, location, kind.keyword, problems) ?: return null
    val ruleGroup = if (kind == RuleKind.EXCLUSIVE) group?.let { "$it:${parsed.name}" } else group
    return Rule(kind, parsed, location, ruleGroup)
}

/** The prefix, or null with a problem recorded that reads as the line was written: `pkg("com.acme.") must not start or end with a dot`. */
private fun parsePrefix(text: String, location: SourceLocation?, keyword: String, problems: MutableList<ConfigProblem>): Prefix? =
    try {
        Prefix.parse(text)
    } catch (e: IllegalArgumentException) {
        problems += problem("$keyword(\"$text\") ${e.message}", location)
        null
    }

private fun problem(message: String, location: SourceLocation?) = ConfigProblem(Severity.ERROR, message, location)
