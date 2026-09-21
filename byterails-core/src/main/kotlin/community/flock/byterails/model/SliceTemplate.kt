package community.flock.byterails.model

/**
 * One structure applied to several sibling packages, the slices.
 *
 * `slices("com.acme") { slice("orders"); slice("customers"); pkg("domain"); ... }` declares
 * `com.acme.orders`, `com.acme.orders.domain`, `com.acme.customers`, `com.acme.customers.domain`
 * and so on. Template rules are written relative to a slice: a prefix that points into the slice's
 * own tree is prefixed with the slice, anything else is taken as written. Slices do not see each
 * other, because nothing allows them to, except the [exported] packages, which every slice may use
 * in every other slice. An exclusive in the template is owned by that package of every slice.
 */
data class SliceTemplate(
    val root: Prefix,
    val slices: List<Prefix>,
    val exported: List<Prefix>,
    val rules: List<Rule>,
    val naming: NamingRules?,
    val packages: List<PackageDeclaration>,
    val location: SourceLocation?,
) {
    /** The ordinary declarations this template stands for. */
    fun expand(): List<PackageDeclaration> {
        val id = location?.toString() ?: "slices(\"${root.name}\")"
        return slices.flatMap { slice ->
            val sliceRoot = Prefix.concat(root, slice)
            // The slice root would cover any candidate, so only the template packages decide what is relative.
            val templatePackages = packages.map { Prefix.concat(sliceRoot, it.prefix) }
            fun resolve(rule: Rule): Rule {
                val candidate = Prefix.concat(sliceRoot, rule.prefix)
                val pointsIntoSlice = templatePackages.any { candidate.covers(it) || it.covers(candidate) }
                val group = if (rule.kind == RuleKind.EXCLUSIVE) "$id:${rule.prefix.name}" else null
                return rule.copy(prefix = if (pointsIntoSlice) candidate else rule.prefix, group = group)
            }
            val exportedAllows = slices.filter { it != slice }.flatMap { other ->
                exported.map { api ->
                    Rule(RuleKind.ALLOW, Prefix.concat(Prefix.concat(root, other), api), location, "${Rule.EXPORTED_GROUP}$id:${api.name}")
                }
            }
            val sliceDeclaration = PackageDeclaration(sliceRoot, rules.map(::resolve) + exportedAllows, naming, location)
            val packageDeclarations = packages.map { template ->
                template.copy(prefix = Prefix.concat(sliceRoot, template.prefix), rules = template.rules.map(::resolve))
            }
            listOf(sliceDeclaration) + packageDeclarations
        }
    }
}
