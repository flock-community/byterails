package community.flock.byterails

import community.flock.byterails.analysis.ClassDirScanner
import community.flock.byterails.check.Checker
import community.flock.byterails.check.ViolationKind
import community.flock.byterails.dsl.byterails
import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.RuleKind
import community.flock.byterails.validation.RuleSetValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SlicesTest {

    private val template = byterails {
        allow("kotlin")
        slices("com.acme") {
            slice("orders")
            slice("customers")
            exported("api")
            allow("org.slf4j")
            pkg("api")
            pkg("domain")
            pkg("application") { allow("domain"); allow("api") }
            pkg("infra") { allow("domain"); exclusive("org.jooq") }
        }
    }

    @Test
    fun `a template expands into declarations per slice`() {
        assertEquals(
            listOf(
                "com.acme.orders", "com.acme.orders.api", "com.acme.orders.domain", "com.acme.orders.application", "com.acme.orders.infra",
                "com.acme.customers", "com.acme.customers.api", "com.acme.customers.domain", "com.acme.customers.application", "com.acme.customers.infra",
            ),
            template.packages.map { it.name },
        )
    }

    @Test
    fun `template rules are relative to the slice unless they point outside it`() {
        val application = template.packages.first { it.name == "com.acme.orders.application" }
        assertEquals(listOf("com.acme.orders.domain", "com.acme.orders.api"), application.rules.map { it.prefix.name })
        val root = template.packages.first { it.name == "com.acme.orders" }
        assertEquals(listOf("org.slf4j", "com.acme.customers.api"), root.rules.map { it.prefix.name })
        assertTrue(root.rules[1].isExported)
    }

    @Test
    fun `exclusives of a template are one group owned by every slice`() {
        val groups = template.packages.flatMap { it.rules }.filter { it.kind == RuleKind.EXCLUSIVE }.map { it.group }.distinct()
        assertEquals(1, groups.size)
        assertNotNull(groups[0])
        assertEquals(emptyList(), RuleSetValidator.validate(template), "no clash between the slices' copies and no cycle through exported")
    }

    @Test
    fun `slices without a root sit at the top or under the base package`() {
        val ruleSet = byterails { slices { slice("orders"); pkg("domain") } }
        assertEquals(listOf("orders", "orders.domain"), ruleSet.packages.map { it.name })
        assertFailsWith<ConfigException> { byterails { slices("com.acme") { pkg("domain") } } }
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
