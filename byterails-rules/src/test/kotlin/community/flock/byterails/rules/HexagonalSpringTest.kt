package community.flock.byterails.rules

import community.flock.byterails.dsl.byterails
import community.flock.byterails.model.ClassName
import community.flock.byterails.model.Prefix
import community.flock.byterails.model.ResolvedRuleSet
import community.flock.byterails.model.RuleKind
import community.flock.byterails.model.RuleSet
import community.flock.byterails.model.withBasePackage
import community.flock.byterails.model.withSlices
import community.flock.byterails.validation.RuleSetValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HexagonalSpringTest {

    /** The build alone: kotlin plus the layout, two slices, base package `com.acme`. */
    private val fromBuild = RuleSet(emptyList(), emptyList())
        .withDefaultRules(listOf("kotlin", "hexagonalSpring"), sliced = true)
        .withSlices(listOf("orders", "customers"))
        .withBasePackage("com.acme")

    private val resolved = ResolvedRuleSet(fromBuild)

    private fun declaration(name: String) = fromBuild.packages.first { it.name == name }

    private fun effective(name: String) = resolved.effectiveRules(declaration(name)).map { it.rule }

    @Test
    fun `the layout declares the base package, config and the slice packages`() {
        val perSlice = listOf(
            "", ".domain.model", ".domain.ports", ".domain.services", ".application",
            ".adapters.inbound", ".adapters.inbound.controllers", ".adapters.inbound.controllers.error",
            ".adapters.outbound", ".adapters.outbound.database", ".adapters.outbound.database.mappers", ".adapters.outbound.database.model",
        )
        assertEquals(
            listOf("com.acme", "com.acme.config") + perSlice.map { "com.acme.orders$it" } + perSlice.map { "com.acme.customers$it" },
            fromBuild.packages.map { it.name },
        )
    }

    @Test
    fun `the base package is flat, so slices are not covered by it and inherit nothing from it`() {
        val base = declaration("com.acme")
        assertTrue(base.flat)
        assertEquals(base, resolved.declarationFor("com.acme"))
        assertEquals("com.acme.orders", resolved.declarationFor("com.acme.orders")?.name)
        assertEquals(null, resolved.declarationFor("com.acme.shipping"), "an undeclared sibling stays undeclared")
        assertFalse(resolved.chain(declaration("com.acme.orders.application")).contains(base))
        assertEquals(null, resolved.namingFor(declaration("com.acme.orders"))?.second, "the Application naming stays at the base")
        assertEquals(listOf("endsWith(\"Application\")", "endsWith(\"ApplicationKt\")"), base.naming?.patterns?.map { it.text })
    }

    @Test
    fun `the application class and config may reference anything except what another package owns`() {
        val config = declaration("com.acme.config")
        assertTrue(effective("com.acme.config").any { it.kind == RuleKind.ALLOW && it.prefix.isRoot })
        assertTrue(effective("com.acme").any { it.kind == RuleKind.ALLOW && it.prefix.isRoot }, "the base package rewrite must not turn the root allow into allow(\"com.acme\")")
        assertTrue(config.rules.any { it.kind == RuleKind.EXCLUSIVE && it.prefix.name == HexagonalSpring.SPRING_CONFIGURATION })
        val configuration = ClassName.fromDotted(HexagonalSpring.SPRING_CONFIGURATION)
        val owner = resolved.exclusiveGroups.single { it.prefix.covers(configuration) }
        assertEquals(listOf(config), owner.owners)
        assertTrue(Prefix.ROOT.covers(ClassName.fromDotted("org.apache.kafka.clients.producer.KafkaProducer")))
    }

    @Test
    fun `the domain is isolated and flat and allows the standard libraries and stereotypes`() {
        val model = declaration("com.acme.orders.domain.model")
        assertTrue(model.isolated && model.flat)
        assertEquals(HexagonalSpring.DOMAIN_BASELINE, effective("com.acme.orders.domain.model").map { it.prefix.name })
        assertEquals(
            HexagonalSpring.DOMAIN_BASELINE + "com.acme.orders.domain.model" + "com.acme.orders.domain.ports",
            effective("com.acme.orders.domain.services").map { it.prefix.name },
        )
        assertEquals(listOf("endsWith(\"Port\")", "endsWith(\"PortKt\")"), declaration("com.acme.orders.domain.ports").naming?.patterns?.map { it.text })
        // The slice root is deep because it carries the slice's rules, so a class directly in domain falls
        // back to it and gets the slice root's rules: no domain access, no layer of its own.
        val sliceRoot = declaration("com.acme.orders")
        assertEquals(sliceRoot, resolved.declarationFor("com.acme.orders.domain"))
        assertEquals(sliceRoot, resolved.declarationFor("com.acme.orders.domain.model.money"), "a flat package has no sub-packages")
        assertFalse(resolved.effectiveRules(sliceRoot).any { it.rule.prefix.name.startsWith("com.acme.orders.domain") })
    }

    @Test
    fun `adapters point inwards and the controllers own the Spring web annotations in every slice`() {
        val inbound = effective("com.acme.orders.adapters.inbound")
        assertTrue(inbound.any { it.kind == RuleKind.DENY && it.prefix.name == "com.acme.orders.domain.ports" })
        assertTrue(inbound.any { it.kind == RuleKind.DENY && it.prefix.name == "com.acme.orders.adapters.outbound" })
        assertTrue(inbound.any { it.kind == RuleKind.ALLOW && it.prefix.name == "com.acme.orders.application" })
        val controllers = effective("com.acme.orders.adapters.inbound.controllers")
        assertTrue(controllers.any { it.kind == RuleKind.ALLOW && it.prefix.name == "org.springframework.http" }, "an external prefix is never prefixed with the slice")
        val webBind = resolved.exclusiveGroups.single { it.prefix.name == HexagonalSpring.SPRING_WEB_BIND }
        assertEquals(listOf("com.acme.orders.adapters.inbound.controllers", "com.acme.customers.adapters.inbound.controllers"), webBind.owners.map { it.name })
        assertTrue(effective("com.acme.orders.adapters.inbound.controllers.error").any { it.prefix.name == "org.springframework.web" })
        val database = declaration("com.acme.orders.adapters.outbound.database")
        assertTrue(database.flat)
        assertTrue(database.rules.any { it.prefix.name == "com.acme.orders.adapters.outbound.database.model" })
        assertTrue(effective("com.acme.orders.adapters.outbound.database.model").any { it.prefix.name == "org.springframework.data" })
    }

    @Test
    fun `the layout validates cleanly with the standard library sets and a widening rules file`() {
        assertEquals(emptyList(), RuleSetValidator.validate(fromBuild))
        val widened = byterails {
            kotlin()
            deny("javax.swing")
            hexagonalSpring()
            slice {
                allow("org.slf4j")
                pkg("adapters.inbound.kafka") {
                    allow("org.springframework.kafka")
                    allow("org.springframework.stereotype")
                }
            }
        }.withSlices(listOf("orders")).withBasePackage("com.acme")
        assertEquals(emptyList(), RuleSetValidator.validate(widened))
        val kafka = ResolvedRuleSet(widened)
        val listener = kafka.declarationFor("com.acme.orders.adapters.inbound.kafka")!!
        val names = kafka.effectiveRules(listener).map { it.rule.prefix.name }
        assertTrue("org.springframework.kafka" in names && "org.slf4j" in names && "com.acme.orders.domain.services" in names, names.toString())
        assertFalse("org.slf4j" in kafka.effectiveRules(kafka.declarationFor("com.acme.orders.domain.model")!!).map { it.rule.prefix.name }, "the domain stays isolated")
    }

    @Test
    fun `from the rules file the layout goes under the base package without a slice block and into the template with one`() {
        val plain = byterails { hexagonalSpring() }.withBasePackage("com.acme")
        assertEquals(listOf("com.acme", "com.acme.config", "com.acme.domain.model"), plain.packages.map { it.name }.take(3))
        assertEquals(null, plain.sliceTemplate)
        val sliced = byterails {
            hexagonalSpring()
            slice { pkg("api") }
        }
        assertEquals(listOf("", "config"), sliced.packages.map { it.name })
        assertEquals(listOf("api", "domain.model"), sliced.sliceTemplate?.packages?.map { it.name }?.take(2))
    }
}
