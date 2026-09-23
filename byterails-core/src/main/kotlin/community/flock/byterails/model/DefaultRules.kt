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
        override fun sliceDeclarations(location: SourceLocation?): List<PackageDeclaration> = listOf(
            PackageDeclaration(
                prefix = Prefix.parse("domain"),
                rules = allows(LANGUAGE_BASELINE, location),
                naming = null,
                location = location,
                isolated = true,
            ),
        )
    },

    /**
     * The vertically sliced hexagonal layout of a Spring Boot service, in whitelist form. Under the
     * base package only the application class and
     * the `config` package may exist; every slice has an isolated `domain` split into `model`, `ports`
     * and `services`, an `application` layer, and `adapters.inbound` and `adapters.outbound` with a fixed
     * place for controllers and for database code. Dependencies point inwards, `@Configuration` lives
     * in `config`, the Spring web annotations live in `controllers`, and the controllers may use only
     * the Spring packages a thin HTTP layer needs. The domain allows the standard libraries and Spring's
     * stereotypes. Everything else inherits the root block, so a rules file widens
     * a layer with a root or slice-root allow and refines one with a sub-package declaration.
     */
    HEXAGONAL_SPRING(
        "hexagonalSpring",
        "the hexagonal Spring Boot layout of a sliced service: an isolated domain, an application layer, inbound and outbound adapters, and a config package",
        "the layout's grant for this package; see the hexagonalSpring section of the README for what each package may use",
    ) {
        override fun baseDeclarations(location: SourceLocation?): List<PackageDeclaration> = listOf(
            // The base package holds the application class and nothing else, so the declaration is flat.
            declaration(Prefix.ROOT, location, flat = true, naming = names(location, "Application")) { allows(ANYTHING) },
            declaration("config", location, naming = names(location, "Config")) {
                allows(ANYTHING) + exclusives(listOf(SPRING_CONFIGURATION))
            },
        )

        override fun sliceDeclarations(location: SourceLocation?): List<PackageDeclaration> = listOf(
            declaration("domain.model", location, isolated = true, flat = true) { allows(DOMAIN_BASELINE) },
            declaration("domain.ports", location, isolated = true, flat = true, naming = names(location, "Port")) {
                allows(DOMAIN_BASELINE + "domain.model")
            },
            declaration("domain.services", location, isolated = true, flat = true, naming = names(location, "Service")) {
                allows(DOMAIN_BASELINE + "domain.model" + "domain.ports")
            },
            declaration("application", location) {
                allows(DOMAIN + SPRING_STEREOTYPE) + denies(listOf("adapters.outbound"))
            },
            declaration("adapters.inbound", location) {
                allows(listOf("domain.model", "domain.services", "application")) + denies(listOf("domain.ports", "adapters.outbound"))
            },
            declaration("adapters.inbound.controllers", location) {
                allows(CONTROLLER_SPRING) + exclusives(listOf(SPRING_WEB_BIND))
            },
            declaration("adapters.inbound.controllers.error", location) { allows(CONTROLLER_ERROR_SPRING) },
            declaration("adapters.outbound", location) {
                allows(listOf("domain.model", "domain.ports", SPRING_STEREOTYPE)) + denies(listOf("domain.services", "adapters.inbound"))
            },
            declaration("adapters.outbound.database", location, flat = true) {
                allows(DATABASE_LIBRARIES + "adapters.outbound.database.model" + "adapters.outbound.database.mappers")
            },
            declaration("adapters.outbound.database.mappers", location, flat = true) { allows(listOf("adapters.outbound.database.model")) },
            declaration("adapters.outbound.database.model", location, flat = true) { allows(DATABASE_LIBRARIES) },
        )
    };

    /** The group every rule of this set carries. */
    val group: String get() = "$GROUP$id"

    /** Rules for the root block, inherited by every declared package. */
    open fun rootRules(location: SourceLocation?): List<Rule> = emptyList()

    /** Declarations relative to the base package, whether or not slices are configured. */
    open fun baseDeclarations(location: SourceLocation?): List<PackageDeclaration> = emptyList()

    /** Declarations relative to a slice, or to the base package when no slices are configured. */
    open fun sliceDeclarations(location: SourceLocation?): List<PackageDeclaration> = emptyList()

    protected fun allows(prefixes: List<String>, location: SourceLocation?): List<Rule> =
        prefixes.map { Rule(RuleKind.ALLOW, Prefix.parse(it), location, group) }

    /** Builds one declaration of the set; [rules] runs with the location bound, so it reads as a list of grants. */
    protected fun declaration(
        prefix: String,
        location: SourceLocation?,
        isolated: Boolean = false,
        flat: Boolean = false,
        naming: NamingRules? = null,
        rules: RuleScope.() -> List<Rule>,
    ): PackageDeclaration = declaration(Prefix.parse(prefix), location, isolated, flat, naming, rules)

    protected fun declaration(
        prefix: Prefix,
        location: SourceLocation?,
        isolated: Boolean = false,
        flat: Boolean = false,
        naming: NamingRules? = null,
        rules: RuleScope.() -> List<Rule>,
    ): PackageDeclaration = PackageDeclaration(prefix, RuleScope(location).rules(), naming, location, isolated, flat)

    /**
     * A file-name convention as a class-name one: Kotlin compiles the top-level functions of
     * `OrderPort.kt` into `OrderPortKt`, so both spellings pass.
     */
    protected fun names(location: SourceLocation?, suffix: String): NamingRules =
        NamingRules(listOf(NamePattern.EndsWith(suffix), NamePattern.EndsWith(suffix + "Kt")), location)

    /** The rules of one declaration, all carrying the set's group. */
    protected inner class RuleScope(private val location: SourceLocation?) {
        fun allows(prefixes: List<String>): List<Rule> = prefixes.map { rule(RuleKind.ALLOW, Prefix.parse(it)) }

        fun denies(prefixes: List<String>): List<Rule> = prefixes.map { rule(RuleKind.DENY, Prefix.parse(it)) }

        /** Each exclusive is its own ownership group, so two of them are never taken for one claim. */
        fun exclusives(prefixes: List<String>): List<Rule> =
            prefixes.map { Rule(RuleKind.EXCLUSIVE, Prefix.parse(it), location, "$group:$it") }

        /** Anything at all, short of what the root block denies and what another package owns. */
        fun allows(anything: Prefix): List<Rule> = listOf(rule(RuleKind.ALLOW, anything))

        private fun rule(kind: RuleKind, prefix: Prefix) = Rule(kind, prefix, location, group)
    }

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

        /** The empty prefix as a rule: a grant of everything, used where the original layout leaves a package unrestricted. */
        val ANYTHING: Prefix = Prefix.ROOT

        /** What the hexagonalSpring domain may use: the standard libraries and Spring's stereotype annotations. */
        val DOMAIN_BASELINE: List<String> = listOf("kotlin", "org.jetbrains.annotations", "java", "org.springframework.stereotype")

        /** The three domain packages of hexagonalSpring, relative to a slice. */
        val DOMAIN: List<String> = listOf("domain.model", "domain.ports", "domain.services")

        /** The Spring packages a thin controller needs: HTTP types, method security, validation, the web annotations and multipart. */
        val CONTROLLER_SPRING: List<String> = listOf(
            "org.springframework.http",
            "org.springframework.security.access.prepost",
            "org.springframework.validation.annotation",
            "org.springframework.web.multipart",
        )

        /** What an error handler under controllers may use on top: data-access exceptions, security, stereotypes, validation and the whole of Spring web. */
        val CONTROLLER_ERROR_SPRING: List<String> = listOf(
            "org.springframework.dao",
            "org.springframework.security.access",
            "org.springframework.stereotype",
            "org.springframework.validation",
            "org.springframework.web",
        )

        /** The persistence libraries hexagonalSpring confines to `adapters.outbound.database`. */
        val DATABASE_LIBRARIES: List<String> = listOf(
            "org.springframework.data",
            "jakarta.persistence",
            "javax.persistence",
            "org.jooq",
            "com.mongodb",
            "io.r2dbc",
            "org.jetbrains.exposed",
        )

        const val SPRING_STEREOTYPE = "org.springframework.stereotype"
        const val SPRING_CONFIGURATION = "org.springframework.context.annotation.Configuration"
        const val SPRING_WEB_BIND = "org.springframework.web.bind.annotation"

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
 * Root rules join the root block. With slices configured the slice declarations go into the slice
 * template, which is created when the rules file has none; otherwise they go under the base package.
 */
fun RuleSet.withDefaultRules(ids: List<String>?, sliced: Boolean): RuleSet {
    val names = ids.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    if (names.isEmpty()) return this
    val sets = names.map { DefaultRules.byId(it) }.distinct()
    val roots = sets.flatMap { it.rootRules(null) }.distinctBy { it.prefix }
    return sets.fold(copy(rootRules = rootRules + roots)) { ruleSet, set -> ruleSet.withDeclarationsOf(set, null, sliced) }
}

/** Adds the declarations of one rule set: base declarations under the base package, slice declarations per [sliced]. */
fun RuleSet.withDeclarationsOf(set: DefaultRules, location: SourceLocation?, sliced: Boolean): RuleSet {
    val base = set.baseDeclarations(location)
    val perSlice = set.sliceDeclarations(location)
    return if (sliced) {
        val template = sliceTemplate ?: SliceTemplate(emptyList(), emptyList(), null, emptyList(), null)
        copy(packages = packages + base, sliceTemplate = template.copy(packages = template.packages + perSlice))
    } else {
        copy(packages = packages + base + perSlice)
    }
}
