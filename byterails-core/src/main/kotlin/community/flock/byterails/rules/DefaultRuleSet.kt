package community.flock.byterails.rules

import community.flock.byterails.dsl.ByterailsBuilder
import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.Rule
import community.flock.byterails.model.RuleSet
import community.flock.byterails.model.including
import java.util.ServiceLoader

/**
 * A rule set applied by name, written with the same DSL as a `byterails.kts` file: root allows,
 * declarations relative to the base package, and a `slice { }` block for what every slice gets.
 *
 * The rule sets byterails ships live in the `byterails-rules` module, one file each in this same
 * package, next to the keyword that applies the set from a rules file. They reach the core through
 * a [DefaultRuleSetProvider] registered as a service, so the core knows a set by its [id] only.
 *
 * A rule set is applied by [id] from the build, or by its keyword from a rules file, and expands
 * into ordinary rules and declarations, so inheritance, matching and reporting apply to it unchanged.
 * Its `slice { }` block declares packages only, because without configured slices they go under the
 * base package, where slice-root rules would have no home.
 *
 * Every rule of a set carries the group `default:<id>`. In a violation's list of allows such rules
 * show as one token, `[<id>]`, with a hint that spells the set out, and the validator never reports
 * them as dead: narrowing a rule set with a deny or an exclusive is expected.
 */
abstract class DefaultRuleSet(
    /** The name the build and the CLI use: `defaultRules.set(listOf("kotlin", "hexagonal"))`. */
    val id: String,
    /** One line on what the set declares, for documentation and error messages. */
    val description: String,
    /** What `[id]` stands for in a violation's hint: `[hexagonal] is the language baseline: ...`. */
    val allowsLabel: String,
) {
    /** The rules of the set, as a rules file would write them. */
    protected abstract fun ByterailsBuilder.rules()

    /** The group every rule of this set carries. */
    val group: String get() = "$GROUP$id"

    /**
     * The set as a rule set of its own. Built afresh on every call, so that rules applied from a script
     * carry the location of the line that applied them.
     */
    fun build(): RuleSet {
        val ruleSet = ByterailsBuilder(group).apply { rules() }.build()
        val template = ruleSet.sliceTemplate
        check(template == null || (template.rules.isEmpty() && template.exported.isEmpty() && template.naming == null)) {
            "default rule set $id: slice { } of a rule set declares packages only"
        }
        return ruleSet
    }

    override fun equals(other: Any?): Boolean = other is DefaultRuleSet && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = id

    companion object {
        const val GROUP = "default:"

        /** Every rule set on the classpath, in the order the providers list them. */
        val all: List<DefaultRuleSet> by lazy {
            ServiceLoader.load(DefaultRuleSetProvider::class.java, DefaultRuleSetProvider::class.java.classLoader)
                .flatMap { it.ruleSets }
                .distinct()
        }

        fun byId(id: String): DefaultRuleSet = all.firstOrNull { it.id == id.trim() }
            ?: throw ConfigException(
                "unknown default rule set \"$id\"; known: " +
                    all.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.id }.let { it ?: "none, is byterails-rules on the classpath?" },
            )

        /** The rule set a rule came from, or null for a rule the user wrote. */
        fun of(rule: Rule): DefaultRuleSet? =
            rule.group?.takeIf { it.startsWith(GROUP) }?.removePrefix(GROUP)?.substringBefore(':')?.let { id -> all.firstOrNull { it.id == id } }
    }
}

/**
 * Contributes rule sets to [DefaultRuleSet.all]. Registered in
 * `META-INF/services/community.flock.byterails.rules.DefaultRuleSetProvider`, with a public no-argument constructor.
 */
interface DefaultRuleSetProvider {
    val ruleSets: List<DefaultRuleSet>
}

/**
 * Applies the rule sets named in the build to this rule set, before `withSlices` and `withBasePackage`.
 * With slices configured the slice packages go into the slice template, which is created when the
 * rules file has none; otherwise they go under the base package. A name given twice is applied once.
 */
fun RuleSet.withDefaultRules(ids: List<String>?, sliced: Boolean): RuleSet {
    val names = ids.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    val sets = names.map { DefaultRuleSet.byId(it) }.distinct()
    return sets.fold(this) { ruleSet, set -> ruleSet.including(set.build(), sliced) }
}
