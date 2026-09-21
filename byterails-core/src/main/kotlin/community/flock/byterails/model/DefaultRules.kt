package community.flock.byterails.model

/** Inlined by the compiler, so an enum entry can use it before the companion object exists. */
private const val LANGUAGE_BASELINE_TEXT = "kotlin, org.jetbrains.annotations, java.lang, java.util, java.time, java.math, java.text"

/**
 * Rule sets byterails ships. A default rule set is applied from the rules file, through the DSL, or
 * from the build, by name, and expands into ordinary root rules and declarations.
 *
 * Rules that come from a rule set carry the group `default:<id>`. In a violation's list of allows
 * they are shown as one token, `[<id>]`, with a hint that spells the set out, and the validator
 * never reports them as dead: narrowing a rule set with a deny or an exclusive is expected.
 */
enum class DefaultRules(val id: String, val description: String, val allowsLabel: String) {

    /**
     * The Java standard library, allowed everywhere: the `java` namespace, the `javax` and `com.sun`
     * packages the JDK itself exports, the `jdk` namespace, and the W3C DOM, SAX and JGSS packages.
     * Not `javax` as a whole, because `javax.persistence`, `javax.inject` and friends are libraries.
     */
    JAVA("java", "the Java standard library, allowed in every package", "the Java standard library") {
        override fun rootRules(location: SourceLocation?): List<Rule> = allows(JAVA_STANDARD_LIBRARY, location)
    },

    /**
     * The Kotlin standard library, allowed everywhere: `kotlin`, the nullability annotations the
     * compiler writes into every class, and the Java standard library, because Kotlin's collections,
     * strings and boxed numbers compile to `java.util` and `java.lang`.
     */
    KOTLIN("kotlin", "the Kotlin standard library and the Java one it compiles to, allowed in every package", "the Kotlin and Java standard libraries") {
        override fun rootRules(location: SourceLocation?): List<Rule> =
            allows(listOf("kotlin", "org.jetbrains.annotations") + JAVA_STANDARD_LIBRARY, location)
    },

    /**
     * Hexagonal architecture: a `domain` package, in every slice or under the base package, that
     * cannot have any external dependency. The package is isolated, so it inherits no allow from the
     * root block or an enclosing declaration, and may reference only the language baseline and itself.
     */
    HEXAGONAL("hexagonal", "a domain package without external dependencies, in every slice", "the language baseline: $LANGUAGE_BASELINE_TEXT") {
        override fun declarations(location: SourceLocation?): List<PackageDeclaration> = listOf(
            PackageDeclaration(
                prefix = Prefix.parse("domain"),
                rules = allows(LANGUAGE_BASELINE, location),
                naming = null,
                location = location,
                isolated = true,
            ),
        )
    };

    /** The group every rule of this set carries. */
    val group: String get() = "$GROUP$id"

    /** Rules for the root block, inherited by every declared package. */
    open fun rootRules(location: SourceLocation?): List<Rule> = emptyList()

    /** Declarations, relative to a slice or to the base package. */
    open fun declarations(location: SourceLocation?): List<PackageDeclaration> = emptyList()

    protected fun allows(prefixes: List<String>, location: SourceLocation?): List<Rule> =
        prefixes.map { Rule(RuleKind.ALLOW, Prefix.parse(it), location, group) }

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

        /** The packages JDK 21 exports without qualification, collapsed to the shortest prefixes, minus jdk.unsupported. */
        val JAVA_STANDARD_LIBRARY: List<String> = listOf(
            "java",
            "jdk",
            "javax.accessibility",
            "javax.annotation.processing",
            "javax.crypto",
            "javax.imageio",
            "javax.lang.model",
            "javax.management",
            "javax.naming",
            "javax.net",
            "javax.print",
            "javax.rmi.ssl",
            "javax.script",
            "javax.security.auth",
            "javax.security.cert",
            "javax.security.sasl",
            "javax.smartcardio",
            "javax.sound",
            "javax.sql",
            "javax.swing",
            "javax.tools",
            "javax.transaction.xa",
            "javax.xml",
            "com.sun.java.accessibility.util",
            "com.sun.jdi",
            "com.sun.management",
            "com.sun.net.httpserver",
            "com.sun.nio.sctp",
            "com.sun.security.auth",
            "com.sun.security.jgss",
            "com.sun.source",
            "com.sun.tools.attach",
            "com.sun.tools.javac",
            "com.sun.tools.jconsole",
            "netscape.javascript",
            "org.ietf.jgss",
            "org.w3c.dom",
            "org.xml.sax",
        )

        fun byId(id: String): DefaultRules = entries.firstOrNull { it.id == id.trim() }
            ?: throw ConfigException("unknown default rule set \"$id\"; known: ${entries.joinToString(", ") { it.id }}")

        /** The rule set a rule came from, or null for a rule the user wrote. */
        fun of(rule: Rule): DefaultRules? =
            rule.group?.takeIf { it.startsWith(GROUP) }?.removePrefix(GROUP)?.substringBefore(':')?.let { id -> entries.firstOrNull { it.id == id } }
    }
}

/**
 * Applies the named default rule sets to this rule set, before [withSlices] and [withBasePackage].
 * Root rules join the root block. With slices configured the declarations go into the slice template,
 * which is created when the rules file has none; otherwise they go under the base package.
 */
fun RuleSet.withDefaultRules(ids: List<String>?, sliced: Boolean): RuleSet {
    val names = ids.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    if (names.isEmpty()) return this
    val sets = names.map { DefaultRules.byId(it) }.distinct()
    val roots = sets.flatMap { it.rootRules(null) }.distinctBy { it.prefix }
    val added = sets.flatMap { it.declarations(null) }
    return if (sliced) {
        val template = sliceTemplate ?: SliceTemplate(emptyList(), emptyList(), null, emptyList(), null)
        copy(rootRules = rootRules + roots, sliceTemplate = template.copy(packages = template.packages + added))
    } else {
        copy(rootRules = rootRules + roots, packages = packages + added)
    }
}
