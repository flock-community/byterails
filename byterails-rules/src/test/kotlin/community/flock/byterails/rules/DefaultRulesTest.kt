package community.flock.byterails.rules

import community.flock.byterails.Byterails
import community.flock.byterails.analysis.ClassDirScanner
import community.flock.byterails.check.Checker
import community.flock.byterails.check.ViolationKind
import community.flock.byterails.dsl.byterails
import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.ResolvedRuleSet
import community.flock.byterails.model.RuleKind
import community.flock.byterails.model.RuleSet
import community.flock.byterails.model.withBasePackage
import community.flock.byterails.model.withSlices
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultRulesTest {

    @Test
    fun `the shipped rule sets are found through the service loader, in documentation order`() {
        assertEquals(listOf("java", "kotlin", "hexagonal", "hexagonalSpring"), DefaultRuleSet.all.map { it.id })
        assertEquals(Hexagonal, DefaultRuleSet.byId(" hexagonal "))
    }

    @Test
    fun `every rule of a set carries the set's group and a slice block declares packages only`() {
        for (set in DefaultRuleSet.all) {
            val ruleSet = set.build()
            val rules = ruleSet.rootRules + ruleSet.packages.flatMap { it.rules } + ruleSet.sliceTemplate?.packages.orEmpty().flatMap { it.rules }
            assertTrue(rules.isNotEmpty(), "$set declares nothing")
            for (rule in rules) {
                assertEquals(set, DefaultRuleSet.of(rule), "$rule of $set")
                if (rule.kind == RuleKind.EXCLUSIVE) assertEquals("${set.group}:${rule.prefix.name}", rule.group, "an exclusive is its own claim")
            }
            assertEquals(null, ruleSet.sliceTemplate?.naming, "$set")
            assertEquals(emptyList(), ruleSet.sliceTemplate?.rules.orEmpty(), "$set")
        }
    }

    @Test
    fun `hexagonal domain inherits nothing, not even a generous root`() {
        val rules = byterails {
            allow("kotlin")
            allow("org.springframework")
            pkg("com.acme") {
                allow("org.jooq")
            }
            hexagonal()
        }.withBasePackage("com.acme")
        val domain = rules.packages.first { it.name == "com.acme.domain" }
        assertTrue(domain.isolated)
        val effective = ResolvedRuleSet(rules).effectiveRules(domain).map { it.rule.prefix.name }
        assertEquals(LANGUAGE_BASELINE, effective)
    }

    @Test
    fun `the build applies default rules into the slice template or under the base package`() {
        val sliced = RuleSet(emptyList(), emptyList()).withDefaultRules(listOf("hexagonal"), sliced = true)
        assertEquals(listOf("domain"), sliced.sliceTemplate?.packages?.map { it.name })
        val flat = RuleSet(emptyList(), emptyList()).withDefaultRules(listOf("hexagonal"), sliced = false).withBasePackage("com.acme")
        assertEquals(listOf("com.acme.domain"), flat.packages.map { it.name })
        val error = assertFailsWith<ConfigException> { RuleSet(emptyList(), emptyList()).withDefaultRules(listOf("onion"), sliced = false) }
        assertTrue(error.message!!.contains("known: java, kotlin, hexagonal"), error.message)
    }

    @Test
    fun `from the rules file hexagonal goes into the slice block when there is one, wherever it is called`() {
        val atTop = byterails {
            hexagonal()
            slice { pkg("api") }
        }
        val inside = byterails {
            slice {
                pkg("api")
                hexagonal()
            }
        }
        assertEquals(listOf("api", "domain"), atTop.sliceTemplate?.packages?.map { it.name })
        assertEquals(listOf("api", "domain"), inside.sliceTemplate?.packages?.map { it.name })
        assertEquals(emptyList(), atTop.packages)
    }

    @Test
    fun `a domain class using an external library is not allowed even though the slice allows it`() {
        val rules = byterails {
            allow("kotlin")
            allow("java.lang")
            allow("org.jetbrains.annotations")
            pkg("fixtures.lib")
            slice {
                allow("fixtures.lib.messaging")
                hexagonal()
            }
        }.withSlices(listOf("fixtures.slices.orders", "fixtures.slices.customers"))
        val result = Checker(rules).check(ClassDirScanner.scan(Fixtures.classDirs))
        val customer = result.violations.filter { it.className.name == "fixtures.slices.customers.domain.Customer" }
        assertTrue(customer.isNotEmpty())
        assertTrue(customer.all { it.kind == ViolationKind.NOT_ALLOWED && it.target?.name == "fixtures.lib.messaging.EventBus" }, customer.toString())
        assertEquals(listOf("[hexagonal]"), customer[0].allows)
        assertTrue(customer[0].hint!!.contains("[hexagonal] is the language baseline: kotlin, org.jetbrains.annotations, java.lang"), customer[0].hint)
        assertEquals(emptyList(), result.violations.filter { it.className.name == "fixtures.slices.orders.domain.Order" })
    }

    @Test
    fun `java and kotlin allow the standard libraries in every package`() {
        val javaOnly = byterails { java(); pkg("fixtures") }
        val kotlin = byterails { kotlin(); pkg("fixtures") }
        assertEquals(Java.STANDARD_LIBRARY, javaOnly.rootRules.map { it.prefix.name })
        assertEquals(listOf("kotlin", "org.jetbrains.annotations") + Java.STANDARD_LIBRARY, kotlin.rootRules.map { it.prefix.name })
        assertTrue(javaOnly.rootRules.all { DefaultRuleSet.of(it) == Java })

        val classes = ClassDirScanner.scan(Fixtures.classDirs)
        assertEquals(emptyList(), Checker(kotlin).check(classes).violations, "a Kotlin project needs nothing beyond the kotlin rule set")

        val withJavaOnly = Checker(javaOnly).check(ClassDirScanner.scan(Fixtures.classDirs))
        val metadata = withJavaOnly.violations.first { it.target?.name == "kotlin.Metadata" }
        assertEquals(ViolationKind.NOT_ALLOWED, metadata.kind)
        assertEquals(listOf("[java]"), metadata.allows)
        assertTrue(metadata.hint!!.contains("allow(\"kotlin\")") && metadata.hint!!.contains("[java] is the Java standard library"), metadata.hint)
        assertTrue(withJavaOnly.violations.none { it.target?.name?.startsWith("java.") == true })
    }

    @Test
    fun `narrowing a rule set with deny or exclusive is not a dead rule`() {
        val rules = byterails {
            kotlin()
            deny("javax.swing")
            pkg("com.acme.infra") { exclusive("javax.sql") }
            pkg("com.acme.domain") { deny("java.util.concurrent") }
        }
        assertEquals(emptyList(), Byterails.validate(rules))
    }

    @Test
    fun `the build applies root rule sets and deduplicates them`() {
        val ruleSet = RuleSet(emptyList(), emptyList()).withDefaultRules(listOf("kotlin", "java", "kotlin"), sliced = false)
        assertEquals(listOf("kotlin", "org.jetbrains.annotations") + Java.STANDARD_LIBRARY, ruleSet.rootRules.map { it.prefix.name })
    }

    @Test
    fun `load works without a rules file when default rules are configured`() {
        val dir = Files.createTempDirectory("byterails-rules").toFile()
        val loaded = Byterails.load(File(dir, "byterails.kts"), null, "com.acme", listOf("orders"), listOf("hexagonal"))
        assertEquals(listOf("com.acme.orders", "com.acme.orders.domain"), loaded.ruleSet.packages.map { it.name })
        val error = assertFailsWith<ConfigException> { Byterails.load(File(dir, "byterails.kts"), null, "com.acme", listOf("orders"), null) }
        assertTrue(error.message!!.contains("does not exist"), error.message)
    }

    @Test
    fun `a rules file applies a set by its keyword and gets the line it was applied on`() {
        val dir = Files.createTempDirectory("byterails-rules").toFile()
        val file = File(dir, "byterails.kts").apply { writeText("byterails {\n    kotlin()\n    hexagonalSpring()\n}\n") }
        val loaded = Byterails.load(file, null, "com.acme", null, null)
        val kotlin = loaded.ruleSet.rootRules.first { it.prefix.name == "kotlin" }
        assertEquals("byterails.kts:2", kotlin.location.toString())
        val ports = loaded.ruleSet.packages.first { it.name == "com.acme.domain.ports" }
        assertEquals("byterails.kts:3", ports.location.toString())
        assertEquals(HexagonalSpring, DefaultRuleSet.of(ports.rules.first()))
    }
}
