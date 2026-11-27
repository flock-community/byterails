package community.flock.byterails.rules

import community.flock.byterails.dsl.ByterailsBuilder
import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.ConfigPhase
import community.flock.byterails.model.Rule
import community.flock.byterails.model.RuleSet
import java.util.ServiceLoader

/**
 * A rule set applied by name, written with the same DSL as a `byterails.kts` file. There are two
 * kinds, and a set is one or the other:
 *
 * - a [BaseRuleSet] is what a language gives every class, such as `java` and `kotlin`. It holds root
 *   rules only and reaches every package, an isolated one included.
 * - a [DefaultRuleSet] is a template for an architecture, such as `hexagonal`. It declares packages,
 *   and its rules are inherited like those of a rules file.
 *
 * The rule sets byterails ships live in the `byterails-rules` module, one file each in this same
 * package, next to the keyword that applies the set from a rules file. They reach the core through
 * a [RuleSetProvider] registered as a service, so the core knows a set by its [id] only, and a rule
 * set of a user's own needs this module alone.
 *
 * A rule set is applied by [id] from the build, or by its keyword from a rules file, and expands
 * into ordinary rules and declarations, so matching and reporting apply to it unchanged.
 *
 * Every rule of a set carries the set's [group]. In a violation's list of allows such rules show as
 * one token, `[<id>]`, with a hint that spells the set out, and the validator never reports them as
 * dead: narrowing a rule set with a deny or an exclusive is expected.
 */
sealed class PredefinedRuleSet(
    /** The name the build and the CLI use: `baseRules.set(listOf("kotlin"))`, `defaultRules.set(listOf("hexagonal"))`. */
    val id: String,
    /** One line on what the set declares, for documentation and error messages. */
    val description: String,
    /** What `[id]` stands for in a violation's hint: `[hexagonal] is the language baseline: ...`. */
    val allowsLabel: String,
    private val groupPrefix: String,
    /** How messages name the kind: `base rule set` or `default rule set`. */
    internal val kind: String,
) {
    /** The rules of the set, as a rules file would write them. */
    protected abstract fun ByterailsBuilder.rules()

    /** The group every rule of this set carries. */
    val group: String get() = "$groupPrefix$id"

    /**
     * The set as a rule set of its own. Built afresh on every call, so that rules applied from a script
     * carry the location of the line that applied them.
     */
    fun build(): RuleSet {
        val ruleSet = ByterailsBuilder(group).apply { rules() }.build()
        check(ruleSet.problems.isEmpty()) { "$kind $id: ${ruleSet.problems.joinToString("; ")}" }
        verify(ruleSet)
        return ruleSet
    }

    /** What this kind of set may hold, checked on every build. */
    protected abstract fun verify(ruleSet: RuleSet)

    override fun equals(other: Any?): Boolean = other is PredefinedRuleSet && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = id

    companion object {
        /** Every rule set on the classpath, base and default, in the order the providers list them. */
        val all: List<PredefinedRuleSet> by lazy {
            ServiceLoader.load(RuleSetProvider::class.java, RuleSetProvider::class.java.classLoader)
                .flatMap { it.ruleSets }
                .distinct()
        }

        /** The rule set a rule came from, or null for a rule the user wrote. */
        fun of(rule: Rule): PredefinedRuleSet? {
            val group = rule.group ?: return null
            return all.firstOrNull { group == it.group || group.startsWith(it.group + ":") }
        }

        /**
         * The set of kind [T] named [id], for the build setting [setting]. A name of the other kind is
         * answered with the setting it belongs in, a near miss with the name that was meant.
         */
        internal inline fun <reified T : PredefinedRuleSet> byId(id: String, kind: String, setting: String, otherSetting: String): T {
            val name = id.trim()
            val known = all.filterIsInstance<T>()
            known.firstOrNull { it.id == name }?.let { return it }
            all.firstOrNull { it.id == name }?.let {
                throw ConfigException("\"$name\" is a ${it.kind}, not a $kind; set it in $otherSetting instead of $setting", phase = ConfigPhase.SETTINGS)
            }
            val hint = if (all.isEmpty()) {
                "; is byterails-rules on the classpath?"
            } else {
                val close = known.firstOrNull { it.id.equals(name, ignoreCase = true) || distance(it.id.lowercase(), name.lowercase()) <= 2 }
                "; the known sets are ${known.joinToString(", ") { it.id }}" + (close?.let { "; did you mean \"${it.id}\"?" } ?: "")
            }
            throw ConfigException("\"$name\" is not a $kind$hint", phase = ConfigPhase.SETTINGS)
        }

        /** The edit distance between two ids, for the did-you-mean. */
        internal fun distance(a: String, b: String): Int {
            var previous = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val current = IntArray(b.length + 1)
                current[0] = i
                for (j in 1..b.length) {
                    val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
                }
                previous = current
            }
            return previous[b.length]
        }
    }
}

/**
 * Contributes rule sets, base and default, to [PredefinedRuleSet.all]. Registered in
 * `META-INF/services/community.flock.byterails.rules.RuleSetProvider`, with a public no-argument constructor.
 */
interface RuleSetProvider {
    val ruleSets: List<PredefinedRuleSet>
}
