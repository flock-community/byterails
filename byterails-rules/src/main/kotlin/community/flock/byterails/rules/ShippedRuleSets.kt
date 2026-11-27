package community.flock.byterails.rules

/**
 * The rule sets byterails ships, handed to the core through `META-INF/services`. The base rule sets come first, then the default ones, in the
 * order the documentation lists them and the order an error message names them.
 */
class ShippedRuleSets : RuleSetProvider {
    override val ruleSets: List<PredefinedRuleSet> = listOf(Java, Kotlin, Hexagonal, HexagonalSpring)
}
