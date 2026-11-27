package community.flock.byterails.rules

import community.flock.byterails.model.RuleSet
import community.flock.byterails.model.including

/**
 * A default rule set: a template for an architecture, such as `hexagonal`. It is applied by [id]
 * with `defaultRules` in the build, or by its keyword in a rules file.
 *
 * It is written like a rules file: root allows, declarations relative to the base package, and a
 * `slice { }` block for what every slice gets. Its `slice { }` block declares packages only, because
 * without configured slices they go under the base package, where slice-root rules would have no
 * home. Its rules are inherited like those of a rules file, so they do not reach an isolated
 * package; what every package needs belongs in a [BaseRuleSet]. Its rules carry the group
 * `default:<id>`.
 */
abstract class DefaultRuleSet(id: String, description: String, allowsLabel: String) :
    PredefinedRuleSet(id, description, allowsLabel, GROUP, "default rule set") {

    override fun verify(ruleSet: RuleSet) {
        val template = ruleSet.sliceTemplate
        check(template == null || (template.rules.isEmpty() && template.exported.isEmpty() && template.naming == null)) {
            "default rule set $id: slice { } of a rule set declares packages only"
        }
    }

    companion object {
        const val GROUP = "default:"

        /** Every default rule set on the classpath, in the order the providers list them. */
        val all: List<DefaultRuleSet> get() = PredefinedRuleSet.all.filterIsInstance<DefaultRuleSet>()

        fun byId(id: String): DefaultRuleSet = byId(id, "default rule set", "defaultRules", "baseRules")
    }
}

/**
 * Applies the default rule sets named in the build to this rule set, before `withSlices` and `withBasePackage`.
 * With slices configured the slice packages go into the slice template, which is created when the
 * rules file has none; otherwise they go under the base package. A name given twice is applied once.
 */
fun RuleSet.withDefaultRules(ids: List<String>?, sliced: Boolean): RuleSet {
    val names = ids.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    val sets = names.map { DefaultRuleSet.byId(it) }.distinct()
    return sets.fold(this) { ruleSet, set -> ruleSet.including(set.build(), sliced) }
}
