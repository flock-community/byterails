package community.flock.byterails

import community.flock.byterails.analysis.ClassDirScanner
import community.flock.byterails.check.Checker
import community.flock.byterails.check.ViolationKind
import community.flock.byterails.dsl.byterails
import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.DefaultRules
import community.flock.byterails.model.ResolvedRuleSet
import community.flock.byterails.model.RuleSet
import community.flock.byterails.model.withBasePackage
import community.flock.byterails.model.withDefaultRules
import community.flock.byterails.model.withSlices
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultRulesTest {

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
        assertEquals(DefaultRules.LANGUAGE_BASELINE, effective)
    }

    @Test
    fun `the build applies default rules into the slice template or under the base package`() {
        val sliced = RuleSet(emptyList(), emptyList()).withDefaultRules(listOf("hexagonal"), sliced = true)
        assertEquals(listOf("domain"), sliced.sliceTemplate?.packages?.map { it.name })
        val flat = RuleSet(emptyList(), emptyList()).withDefaultRules(listOf("hexagonal"), sliced = false).withBasePackage("com.acme")
        assertEquals(listOf("com.acme.domain"), flat.packages.map { it.name })
        val error = assertFailsWith<ConfigException> { RuleSet(emptyList(), emptyList()).withDefaultRules(listOf("onion"), sliced = false) }
        assertTrue(error.message!!.contains("known: hexagonal"), error.message)
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
        assertEquals(DefaultRules.LANGUAGE_BASELINE.sorted(), customer[0].allows)
        assertEquals(emptyList(), result.violations.filter { it.className.name == "fixtures.slices.orders.domain.Order" })
    }

    @Test
    fun `load works without a rules file when default rules are configured`() {
        val dir = Files.createTempDirectory("byterails-defaults").toFile()
        val loaded = Byterails.load(File(dir, "byterails.kts"), null, "com.acme", listOf("orders"), listOf("hexagonal"))
        assertEquals(listOf("com.acme.orders", "com.acme.orders.domain"), loaded.ruleSet.packages.map { it.name })
        val error = assertFailsWith<ConfigException> { Byterails.load(File(dir, "byterails.kts"), null, "com.acme", listOf("orders"), null) }
        assertTrue(error.message!!.contains("does not exist"), error.message)
    }
}
