package community.flock.byterails.rules

/**
 * The rule sets byterails ships, handed to the core through `META-INF/services`. The order is the
 * order the documentation lists them and the order an error message names them.
 */
class ShippedRuleSets : DefaultRuleSetProvider {
    override val ruleSets: List<DefaultRuleSet> = listOf(Java, Kotlin, Hexagonal, HexagonalSpring)
}
