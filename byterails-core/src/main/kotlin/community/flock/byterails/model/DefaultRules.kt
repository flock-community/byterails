package community.flock.byterails.model

/**
 * Rule sets byterails ships. A default rule set is applied from the rules file, through the DSL, or
 * from the build, by name, and expands into ordinary declarations.
 */
enum class DefaultRules(val id: String, val description: String) {

    /**
     * Hexagonal architecture: a `domain` package, in every slice or under the base package, that
     * cannot have any external dependency. The package is isolated, so it inherits no allow from the
     * root block or an enclosing declaration, and may reference only the language baseline and itself.
     */
    HEXAGONAL("hexagonal", "a domain package without external dependencies, in every slice") {
        override fun declarations(location: SourceLocation?): List<PackageDeclaration> = listOf(
            PackageDeclaration(
                prefix = Prefix.parse("domain"),
                rules = LANGUAGE_BASELINE.map { Rule(RuleKind.ALLOW, Prefix.parse(it), location, "$GROUP$id:baseline") },
                naming = null,
                location = location,
                isolated = true,
            ),
        )
    };

    /** The declarations this rule set stands for, relative to a slice or to the base package. */
    abstract fun declarations(location: SourceLocation?): List<PackageDeclaration>

    companion object {
        const val GROUP = "default:"

        /**
         * What a class needs from the JDK and the Kotlin runtime to exist at all, plus the value
         * types a domain model is made of. Nothing here talks to the outside world.
         */
        val LANGUAGE_BASELINE: List<String> = listOf(
            "kotlin",
            "org.jetbrains.annotations",
            "java.lang",
            "java.util",
            "java.time",
            "java.math",
            "java.text",
        )

        fun byId(id: String): DefaultRules = entries.firstOrNull { it.id == id.trim() }
            ?: throw ConfigException("unknown default rule set \"$id\"; known: ${entries.joinToString(", ") { it.id }}")
    }
}

/**
 * Applies the named default rule sets to this rule set, before [withSlices] and [withBasePackage].
 * With slices configured the declarations go into the slice template, which is created when the
 * rules file has none; otherwise they go under the base package.
 */
fun RuleSet.withDefaultRules(ids: List<String>?, sliced: Boolean): RuleSet {
    val names = ids.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    if (names.isEmpty()) return this
    val added = names.map { DefaultRules.byId(it) }.flatMap { it.declarations(null) }
    return if (sliced) {
        val template = sliceTemplate ?: SliceTemplate(emptyList(), emptyList(), null, emptyList(), null)
        copy(sliceTemplate = template.copy(packages = template.packages + added))
    } else {
        copy(packages = packages + added)
    }
}
