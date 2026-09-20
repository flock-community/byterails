package community.flock.byterails.model

/** A rule together with the declaration it came from; a null origin means the root block. */
data class EffectiveRule(val rule: Rule, val origin: PackageDeclaration?) {
    val originName: String get() = origin?.name ?: "root"
}

/**
 * A [RuleSet] with inheritance resolved: which declaration owns a package, which rules apply to it,
 * and which exclusives exist anywhere.
 */
class ResolvedRuleSet(val ruleSet: RuleSet) {

    val declarations: List<PackageDeclaration> = ruleSet.packages.sortedBy { it.prefix.depth }

    /** Every exclusive rule in the configuration with the declaration that owns it. */
    val exclusives: List<EffectiveRule> = declarations.flatMap { declaration ->
        declaration.rules.filter { it.kind == RuleKind.EXCLUSIVE }.map { EffectiveRule(it, declaration) }
    }

    private val effectiveRulesByDeclaration = HashMap<PackageDeclaration, List<EffectiveRule>>()

    /** The most specific declaration covering [packageName], or null when the package is undeclared. */
    fun declarationFor(packageName: String): PackageDeclaration? =
        declarations.filter { it.prefix.coversPackage(packageName) }.maxByOrNull { it.prefix.depth }

    /** The declaration itself and every declaration enclosing it, outermost first. */
    fun chain(declaration: PackageDeclaration): List<PackageDeclaration> =
        declarations.filter { it.prefix.covers(declaration.prefix) }

    /** The nearest declaration, self included, that carries a naming block. */
    fun namingFor(declaration: PackageDeclaration): Pair<PackageDeclaration, NamingRules>? =
        chain(declaration).lastOrNull { it.naming != null }?.let { it to it.naming!! }

    /** Root rules first, then the rules of each enclosing declaration from the outermost in. */
    fun effectiveRules(declaration: PackageDeclaration): List<EffectiveRule> =
        effectiveRulesByDeclaration.getOrPut(declaration) {
            ruleSet.rootRules.map { EffectiveRule(it, null) } +
                chain(declaration).flatMap { enclosing -> enclosing.rules.map { EffectiveRule(it, enclosing) } }
        }

    /** True when [declaration] lies inside the subtree owned by [owner], the owner itself included. */
    fun isInside(declaration: PackageDeclaration?, owner: PackageDeclaration): Boolean =
        declaration != null && owner.prefix.covers(declaration.prefix)
}
