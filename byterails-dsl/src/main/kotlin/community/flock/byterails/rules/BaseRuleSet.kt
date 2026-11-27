package community.flock.byterails.rules

import community.flock.byterails.model.Rule
import community.flock.byterails.model.RuleSet
import community.flock.byterails.model.including

/**
 * A base rule set: what a language gives every class, such as the standard library of `java` or
 * `kotlin`. It is applied by [id] with `baseRules` in the build, or by its keyword in a rules file.
 *
 * A base rule set holds root allows and denies only, and they apply to every package: unlike the
 * root block of a rules file they also reach a package marked `isolated()`, because no class can
 * exist without them. Its rules carry the group `base:<id>`.
 */
abstract class BaseRuleSet(id: String, description: String, allowsLabel: String) :
    PredefinedRuleSet(id, description, allowsLabel, GROUP, "base rule set") {

    override fun verify(ruleSet: RuleSet) {
        check(ruleSet.packages.isEmpty() && ruleSet.sliceTemplate == null && ruleSet.exported.isEmpty()) {
            "base rule set $id: a base rule set holds root rules only; a set that declares packages is a default rule set"
        }
    }

    companion object {
        const val GROUP = Rule.BASE_GROUP

        /** Every base rule set on the classpath, in the order the providers list them. */
        val all: List<BaseRuleSet> get() = PredefinedRuleSet.all.filterIsInstance<BaseRuleSet>()

        fun byId(id: String): BaseRuleSet = byId(id, "base rule set", "baseRules", "defaultRules")
    }
}

/** Applies the base rule sets named in the build to this rule set. A name given twice is applied once. */
fun RuleSet.withBaseRules(ids: List<String>?): RuleSet {
    val names = ids.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    val sets = names.map { BaseRuleSet.byId(it) }.distinct()
    return sets.fold(this) { ruleSet, set -> ruleSet.including(set.build(), sliced = false) }
}
