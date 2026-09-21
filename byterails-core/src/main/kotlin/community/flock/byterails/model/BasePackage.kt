package community.flock.byterails.model

/**
 * Applies a base package to a rule set, so a rules file can be written relative to the root
 * package of the project it describes.
 *
 * Every declaration is prefixed. A rule prefix is prefixed when the prefixed form points into the
 * declared package tree, that is when it covers a declaration or a declaration covers it;
 * otherwise it is left as written. With base `com.acme` and a declared `domain`, `allow("domain")`
 * becomes `allow("com.acme.domain")` while `allow("kotlin")` stays `allow("kotlin")`.
 */
fun RuleSet.withBasePackage(basePackage: String?): RuleSet {
    if (basePackage.isNullOrBlank()) return this
    val base = try {
        Prefix.parse(basePackage)
    } catch (e: IllegalArgumentException) {
        throw ConfigException("base package ${e.message}")
    }
    val declared = packages.map { Prefix.concat(base, it.prefix) }
    fun resolve(rule: Rule): Rule {
        val candidate = Prefix.concat(base, rule.prefix)
        val pointsIntoTree = declared.any { candidate.covers(it) || it.covers(candidate) }
        return if (pointsIntoTree) rule.copy(prefix = candidate) else rule
    }
    return copy(
        rootRules = rootRules.map(::resolve),
        packages = packages.zip(declared) { declaration, prefix ->
            declaration.copy(prefix = prefix, rules = declaration.rules.map(::resolve))
        },
    )
}
