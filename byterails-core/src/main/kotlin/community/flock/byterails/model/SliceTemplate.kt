package community.flock.byterails.model

/**
 * The structure of one slice, as the rules file's `slice { }` block describes it.
 *
 * Which slices exist is a build setting: the plugin's `slices` list names sibling packages, relative
 * to the base package, and [withSlices] applies the template to each of them. Template rules are
 * written relative to a slice: a prefix that points into the template's packages is prefixed with
 * the slice, anything else is taken as written. Slices do not see each other, because nothing allows
 * them to, except the [exported] packages, which every slice may use in every other slice. An
 * exclusive in the template is owned by that package of every slice together.
 */
data class SliceTemplate(
    val exported: List<Prefix>,
    val rules: List<Rule>,
    val naming: NamingRules?,
    val packages: List<PackageDeclaration>,
    val location: SourceLocation?,
) {
    /** The ordinary declarations this template stands for, one set per slice. */
    fun expand(slices: List<Prefix>): List<PackageDeclaration> {
        val id = location?.toString() ?: "slice"
        return slices.flatMap { sliceRoot ->
            // The slice root would cover any candidate, so only the template packages decide what is relative.
            val templatePackages = packages.map { Prefix.concat(sliceRoot, it.prefix) }
            fun resolve(rule: Rule): Rule {
                val candidate = Prefix.concat(sliceRoot, rule.prefix)
                val pointsIntoSlice = templatePackages.any { candidate.covers(it) || it.covers(candidate) }
                // A template exclusive is owned by every slice together; any other rule keeps the group it came with.
                val group = if (rule.kind == RuleKind.EXCLUSIVE) "$id:${rule.prefix.name}" else rule.group
                return rule.copy(prefix = if (pointsIntoSlice) candidate else rule.prefix, group = group)
            }
            val exportedAllows = slices.filter { it != sliceRoot }.flatMap { other ->
                exported.map { api ->
                    Rule(RuleKind.ALLOW, Prefix.concat(other, api), location, "${Rule.EXPORTED_GROUP}$id:${api.name}")
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

/**
 * Applies the rule set's slice template to the named [slices], which are package names relative to
 * the base package. Apply this before [withBasePackage].
 *
 * @throws ConfigException when slices are named but the rules file has no `slice { }` block, or the
 *   rules file has one and no slices are named.
 */
fun RuleSet.withSlices(slices: List<String>?): RuleSet {
    val names = slices.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    val template = sliceTemplate
    if (template == null) {
        if (names.isEmpty()) return this
        throw ConfigException("slices ${names.joinToString(", ")} are configured, but the rules file has no slice { } block")
    }
    if (names.isEmpty()) {
        throw ConfigException("the rules file has a slice { } block, but no slices are configured; name them in the build", template.location)
    }
    val prefixes = names.map { name ->
        try {
            Prefix.parse(name)
        } catch (e: IllegalArgumentException) {
            throw ConfigException("slice ${e.message}")
        }
    }
    return copy(packages = packages + template.expand(prefixes), sliceTemplate = null)
}
