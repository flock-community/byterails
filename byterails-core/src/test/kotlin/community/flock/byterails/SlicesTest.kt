package community.flock.byterails

import community.flock.byterails.analysis.ClassDirScanner
import community.flock.byterails.check.Checker
import community.flock.byterails.check.ViolationKind
import community.flock.byterails.dsl.byterails
import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.RuleKind
import community.flock.byterails.model.withBasePackage
import community.flock.byterails.model.withSlices
import community.flock.byterails.validation.RuleSetValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SlicesTest {

    private val rulesFile = byterails {
        allow("kotlin")
        pkg("common")
        slice {
            exported("api")
            allow("org.slf4j")
            pkg("api")
            pkg("domain")
            pkg("application") { allow("domain"); allow("api") }
            pkg("infra") { allow("domain"); exclusive("org.jooq") }
        }
    }

    private val expanded = rulesFile.withSlices(listOf("orders", "customers")).withBasePackage("com.acme")

    @Test
    fun `the build names the slices and the template expands per slice`() {
        assertEquals(
            listOf(
                "com.acme.common",
                "com.acme.orders", "com.acme.orders.api", "com.acme.orders.domain", "com.acme.orders.application", "com.acme.orders.infra",
                "com.acme.customers", "com.acme.customers.api", "com.acme.customers.domain", "com.acme.customers.application", "com.acme.customers.infra",
            ),
            expanded.packages.map { it.name },
        )
        assertEquals(null, expanded.sliceTemplate)
    }

    @Test
    fun `template rules are relative to the slice unless they point outside it`() {
        val application = expanded.packages.first { it.name == "com.acme.orders.application" }
        assertEquals(listOf("com.acme.orders.domain", "com.acme.orders.api"), application.rules.map { it.prefix.name })
        val root = expanded.packages.first { it.name == "com.acme.orders" }
        assertEquals(listOf("org.slf4j", "com.acme.customers.api"), root.rules.map { it.prefix.name })
        assertTrue(root.rules[1].isExported)
    }

    @Test
    fun `exclusives of a template are one group owned by every slice`() {
        val groups = expanded.packages.flatMap { it.rules }.filter { it.kind == RuleKind.EXCLUSIVE }.map { it.group }.distinct()
        assertEquals(1, groups.size)
        assertNotNull(groups[0])
        assertEquals(emptyList(), RuleSetValidator.validate(expanded), "no clash between the slices' copies and no cycle through exported")
    }

    @Test
    fun `a slice block without configured slices, and slices without a block, are errors`() {
        val noSlices = assertFailsWith<ConfigException> { rulesFile.withSlices(emptyList()) }
        assertTrue(noSlices.message!!.contains("no slices are configured"), noSlices.message)
        assertTrue(RuleSetValidator.validate(rulesFile).any { it.message.contains("no slices are configured") })
        val noBlock = assertFailsWith<ConfigException> { byterails { pkg("a") }.withSlices(listOf("orders")) }
        assertTrue(noBlock.message!!.contains("no slice { } block"), noBlock.message)
        assertFailsWith<ConfigException> { byterails { slice { pkg("a") }; slice { pkg("b") } } }
    }

    @Test
    fun `slices see each other only through exported packages`() {
        val result = Checker(Fixtures.rules()).check(ClassDirScanner.scan(Fixtures.classDirs))
        val slices = result.violations.filter { it.className.packageName.startsWith("fixtures.slices") }
        assertEquals(
            listOf(
                ViolationKind.NOT_ALLOWED to "fixtures.slices.customers.application.RegisterCustomer",
                ViolationKind.EXCLUSIVE to "fixtures.slices.customers.domain.Customer",
            ),
            slices.map { it.kind to it.className.name }.distinct(),
        )
        val crossSlice = slices.first { it.kind == ViolationKind.NOT_ALLOWED }
        assertEquals("fixtures.slices.orders.domain.Order", crossSlice.target?.name)
        assertTrue(crossSlice.allows.contains("fixtures.slices.orders.api"), crossSlice.allows.toString())
        val exclusive = slices.first { it.kind == ViolationKind.EXCLUSIVE }
        assertEquals("exclusive(\"fixtures.lib.messaging\")", exclusive.rule?.text)
        assertTrue(exclusive.message.contains("and 1 more slices"), exclusive.message)
    }
}
