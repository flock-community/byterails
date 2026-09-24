package community.flock.byterails

import community.flock.byterails.analysis.ClassDirScanner
import community.flock.byterails.check.Checker
import community.flock.byterails.check.ViolationKind
import community.flock.byterails.dsl.ByterailsBuilder
import community.flock.byterails.dsl.byterails
import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.ModuleRoot
import community.flock.byterails.model.ModuleRules
import community.flock.byterails.model.Prefix
import community.flock.byterails.model.ResolvedRuleSet
import community.flock.byterails.model.RuleKind
import community.flock.byterails.model.RuleSet
import community.flock.byterails.model.including
import community.flock.byterails.model.withBasePackage
import community.flock.byterails.model.withModules
import community.flock.byterails.report.ConsoleReporter
import community.flock.byterails.validation.RuleSetValidator
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModulesTest {

    private val root = byterails {
        allow("kotlin")
        allow("java.lang")
        pkg("common")
    }

    private val orders = ModuleRules(
        "orders",
        byterails {
            exported("api")
            allow("org.slf4j")
            allow("common")
            pkg("api")
            pkg("domain") {
                allow("api")
                exclusive("org.jooq")
            }
        },
    )

    private val customers = ModuleRules("customers", byterails { pkg("domain") })

    private val expanded = root.withModules(listOf(orders, customers), "orders").withBasePackage("com.acme")

    private val resolved = ResolvedRuleSet(expanded)

    private fun declaration(name: String) = expanded.packages.first { it.name == name }

    @Test
    fun `every module is declared under the base package, its root first`() {
        assertEquals(
            listOf("com.acme.common", "com.acme.orders", "com.acme.orders.api", "com.acme.orders.domain", "com.acme.customers", "com.acme.customers.domain"),
            expanded.packages.map { it.name },
        )
        assertEquals(listOf(ModuleRoot("orders", Prefix.parse("com.acme.orders")), ModuleRoot("customers", Prefix.parse("com.acme.customers"))), expanded.modules)
        assertEquals("orders", expanded.module)
        assertEquals(emptyList(), RuleSetValidator.validate(expanded))
    }

    @Test
    fun `the top-level rules of a module file are the rules of its root, written relative to the module`() {
        val ordersRoot = declaration("com.acme.orders")
        assertEquals(listOf("org.slf4j", "com.acme.common"), ordersRoot.rules.map { it.prefix.name }, "common points at the root file's package, org.slf4j is taken as written")
        val domain = declaration("com.acme.orders.domain")
        assertEquals(listOf("com.acme.orders.api", "org.jooq"), domain.rules.map { it.prefix.name })
        assertEquals(
            listOf("kotlin", "java.lang", "org.slf4j", "com.acme.common", "com.acme.orders.api", "org.jooq"),
            resolved.effectiveRules(domain).map { it.rule.prefix.name },
            "the root block, then the module root, then the package itself",
        )
        assertEquals(ordersRoot, resolved.declarationFor("com.acme.orders.anything"), "the module root covers its whole subtree")
    }

    @Test
    fun `what a module exports, the other modules may use and nothing else may`() {
        val customersRoot = declaration("com.acme.customers")
        val exported = customersRoot.rules.single()
        assertEquals("com.acme.orders.api", exported.prefix.name)
        assertEquals(RuleKind.ALLOW, exported.kind)
        assertTrue(exported.isExported, "left out of cycle detection like a slice export")
        assertTrue(declaration("com.acme.orders").rules.none { it.isExported }, "customers exports nothing")
        assertTrue(resolved.effectiveRules(declaration("com.acme.common")).none { it.rule.prefix.name.startsWith("com.acme.orders") }, "a root-declared package gets no export")
    }

    @Test
    fun `a module without a rules file has an implicit root with the exports of the others`() {
        val shipping = ModuleRules("shipping", RuleSet(emptyList(), emptyList()))
        val withShipping = root.withModules(listOf(orders, shipping), null).withBasePackage("com.acme")
        val shippingRoot = withShipping.packages.first { it.name == "com.acme.shipping" }
        assertEquals(listOf("com.acme.orders.api"), shippingRoot.rules.map { it.prefix.name })
        assertEquals(null, withShipping.module)
        assertEquals(emptyList(), RuleSetValidator.validate(withShipping))
    }

    @Test
    fun `the slices of a module lie under the module and its slice block applies to them`() {
        val sales = ModuleRules(
            "sales",
            byterails {
                allow("shared")
                pkg("shared")
                slice {
                    exported("api")
                    pkg("api")
                    pkg("domain") { allow("api"); allow("shared") }
                }
            },
            slices = listOf("eu", "us"),
        )
        val withSales = root.withModules(listOf(sales), "sales").withBasePackage("com.acme")
        assertEquals(
            listOf("com.acme.common", "com.acme.sales", "com.acme.sales.shared", "com.acme.sales.eu", "com.acme.sales.eu.api", "com.acme.sales.eu.domain", "com.acme.sales.us", "com.acme.sales.us.api", "com.acme.sales.us.domain"),
            withSales.packages.map { it.name },
        )
        val euDomain = withSales.packages.first { it.name == "com.acme.sales.eu.domain" }
        assertEquals(listOf("com.acme.sales.eu.api", "com.acme.sales.shared"), euDomain.rules.map { it.prefix.name }, "relative to the slice, then to the module")
        val eu = withSales.packages.first { it.name == "com.acme.sales.eu" }
        assertEquals(listOf("com.acme.sales.us.api"), eu.rules.map { it.prefix.name })
        assertEquals(listOf("com.acme.sales.shared"), withSales.packages.first { it.name == "com.acme.sales" }.rules.map { it.prefix.name })
        assertEquals(emptyList(), RuleSetValidator.validate(withSales))
    }

    @Test
    fun `a rule set applied to a module lands under the module root, and one that declares the base describes the root`() {
        // Stands in for hexagonalSpring: a flat base package, a config package and a slice-part domain.
        val layout = ByterailsBuilder("default:layout").apply {
            allow("org.jetbrains.annotations")
            basePackage {
                flat()
                allowAnything()
                naming { endsWith("Application") }
            }
            pkg("config") { allow("org.springframework") }
            slice { pkg("domain") { isolated() } }
        }.build()
        val orders = ModuleRules("orders", byterails { allow("org.slf4j") }.including(layout, sliced = false))
        val withLayout = root.withModules(listOf(orders), "orders").withBasePackage("com.acme")
        assertEquals(listOf("com.acme.common", "com.acme.orders", "com.acme.orders.config", "com.acme.orders.domain"), withLayout.packages.map { it.name })
        val moduleRoot = withLayout.packages.first { it.name == "com.acme.orders" }
        assertTrue(moduleRoot.flat)
        assertEquals("default:layout", moduleRoot.group)
        assertEquals(listOf("endsWith(\"Application\")"), moduleRoot.naming?.patterns?.map { it.text })
        assertEquals(listOf("org.slf4j", "org.jetbrains.annotations", ""), moduleRoot.rules.map { it.prefix.name }, "the module's own rules, the set's root allows, then the set's rules for the root")
        assertEquals(null, ResolvedRuleSet(withLayout).declarationFor("com.acme.orders.stray"), "a flat module root leaves its sub-packages to the module's declarations")
        assertEquals(emptyList(), RuleSetValidator.validate(withLayout))
    }

    @Test
    fun `configuration errors name the module`() {
        fun error(block: () -> Unit) = assertFailsWith<ConfigException>(block = block).message!!

        assertTrue(error { root.withModules(listOf(ModuleRules("or ders", RuleSet(emptyList(), emptyList()))), null) }.contains("module name segment"))
        assertTrue(error { root.withModules(listOf(orders, ModuleRules("orders", RuleSet(emptyList(), emptyList()))), null) }.contains("configured more than once"))
        assertTrue(error { root.withModules(listOf(orders, ModuleRules("orders.billing", RuleSet(emptyList(), emptyList()))), null) }.contains("lies inside module \"orders\""))
        assertTrue(error { root.withModules(listOf(orders), "shipping") }.contains("not among the configured modules: orders"))
        assertTrue(error { root.withModules(emptyList(), "orders") }.contains("no modules are known"))

        val handWritten = error { root.withModules(listOf(ModuleRules("orders", byterails { basePackage { flat() } })), null) }
        assertTrue(handWritten.contains("basePackage { } is not allowed in the rules file of module \"orders\""), handWritten)

        val sliceless = error { root.withModules(listOf(ModuleRules("orders", byterails { slice { pkg("api") } })), null) }
        assertTrue(sliceless.contains("module \"orders\": the rules file has a slice { } block, but no slices are configured"), sliceless)

        val rootExports = byterails { exported("api"); pkg("com.acme") }
        assertTrue(RuleSetValidator.validate(rootExports).any { it.message.contains("exported(\"api\") is only meaningful in the rules file of a module") })

        val declaredTwice = byterails { allow("kotlin"); pkg("orders") }.withModules(listOf(orders), null).withBasePackage("com.acme")
        val problem = RuleSetValidator.validate(declaredTwice).single()
        assertTrue(problem.message.contains("\"com.acme.orders\" is the root of module \"orders\""), problem.message)
    }

    private val fixtureModules = listOf(
        ModuleRules(
            "slices.orders",
            byterails {
                exported("api")
                pkg("api")
                pkg("domain")
                pkg("application") { allow("domain"); allow("api") }
                pkg("infra") { allow("domain"); exclusive("fixtures.lib.messaging") }
            },
        ),
        ModuleRules(
            "slices.customers",
            byterails {
                exported("api")
                pkg("api")
                pkg("domain")
                pkg("application") { allow("domain"); allow("api") }
                pkg("infra") { allow("domain") }
            },
        ),
    )

    private fun fixtureRules(current: String?) = byterails {
        allow("kotlin")
        allow("java.lang")
        allow("java.util")
        allow("org.jetbrains.annotations")
        pkg("lib")
        pkg("app")
        pkg("app.domain") { deny("lib.persistence") }
        pkg("app.application") { allow("app.domain"); naming { endsWith("UseCase") } }
        pkg("app.infra.persistence") { allow("app.domain"); allow("lib.persistence"); exclusive("lib.jooq") }
        pkg("app.infra.web") { allow("app.application"); exclusive("lib.web"); naming { endsWith("Controller"); endsWith("Advice") } }
        pkg("app.javainterop")
    }.withModules(fixtureModules, current).withBasePackage("fixtures")

    @Test
    fun `a class outside the module being checked is in the wrong module, and nothing else is reported for it`() {
        val result = Checker(fixtureRules("slices.orders")).check(ClassDirScanner.scan(Fixtures.classDirs))
        assertEquals("slices.orders", result.module)
        val outside = result.violations.filter { !it.className.name.startsWith("fixtures.slices.orders") }
        assertTrue(outside.isNotEmpty())
        assertTrue(outside.all { it.kind == ViolationKind.WRONG_MODULE }, outside.filter { it.kind != ViolationKind.WRONG_MODULE }.toString())
        assertEquals(outside.size, outside.map { it.className }.distinct().size, "one violation per class")
        val app = outside.first { it.className.name == "fixtures.app.domain.Order" }
        assertEquals("fixtures.app.domain.Order is compiled in module \"slices.orders\", which owns \"fixtures.slices.orders\", but lies outside it", app.message)
        val other = outside.first { it.className.name == "fixtures.slices.customers.domain.Customer" }
        assertEquals("fixtures.slices.customers.domain.Customer belongs to module \"slices.customers\", which owns \"fixtures.slices.customers\", but is compiled in module \"slices.orders\"", other.message)
        assertTrue(result.violations.none { it.className.name.startsWith("fixtures.slices.orders") && it.kind == ViolationKind.WRONG_MODULE })
        assertEquals(
            listOf("byterails: WRONG MODULE fixtures.app.domain.Order", "  module   is compiled in module \"slices.orders\", which owns \"fixtures.slices.orders\", but lies outside it", "  source   Order.kt"),
            ConsoleReporter.render(app),
        )
    }

    @Test
    fun `outside the modules, a class of a module is in the wrong module and everything else is checked as usual`() {
        val result = Checker(fixtureRules(null)).check(ClassDirScanner.scan(Fixtures.classDirs))
        val inModules = result.violations.filter { it.className.name.startsWith("fixtures.slices") }
        assertTrue(inModules.all { it.kind == ViolationKind.WRONG_MODULE })
        assertTrue(inModules.first().message.endsWith("but is compiled outside the modules"), inModules.first().message)
        val baseline = Checker(Fixtures.rules()).check(ClassDirScanner.scan(Fixtures.classDirs))
        assertEquals(
            baseline.violations.filter { it.className.name.startsWith("fixtures.app") }.map { it.kind to it.className.name },
            result.violations.filter { it.className.name.startsWith("fixtures.app") }.map { it.kind to it.className.name },
        )
    }

    @Test
    fun `modules see each other only through exported packages, and an exclusive of one module binds the others`() {
        val result = Checker(fixtureRules("slices.customers")).check(ClassDirScanner.scan(Fixtures.classDirs))
        val own = result.violations.filter { it.className.name.startsWith("fixtures.slices.customers") }
        assertEquals(
            listOf(
                ViolationKind.NOT_ALLOWED to "fixtures.slices.customers.application.RegisterCustomer",
                ViolationKind.EXCLUSIVE to "fixtures.slices.customers.domain.Customer",
                ViolationKind.EXCLUSIVE to "fixtures.slices.customers.infra.CustomerStore",
            ),
            own.map { it.kind to it.className.name }.distinct(),
        )
        val crossModule = own.first { it.kind == ViolationKind.NOT_ALLOWED }
        assertEquals("fixtures.slices.orders.domain.Order", crossModule.target?.name)
        assertTrue(crossModule.allows.contains("fixtures.slices.orders.api"), crossModule.allows.toString())
        // Unlike a slice template's exclusive, which every slice owns together, a module's exclusive is its own.
        own.filter { it.kind == ViolationKind.EXCLUSIVE }.forEach { exclusive ->
            assertEquals("exclusive(\"fixtures.lib.messaging\")", exclusive.rule?.text)
            assertEquals("fixtures.slices.orders.infra", exclusive.rule?.declaringPackage)
        }
    }

    @Test
    fun `module rules files are loaded next to the root file and named by their path`() {
        val dir = Files.createTempDirectory("byterails-modules").toFile()
        File(dir, "byterails.kts").writeText(
            """
            byterails {
                allow("java.lang")
                pkg("common")
            }
            """.trimIndent(),
        )
        val ordersFile = File(dir, "orders/byterails.kts").apply { parentFile.mkdirs() }
        ordersFile.writeText(
            """
            byterails {
                exported("api")
                pkg("api")
                pkg("domain") { exclusive("java.util") }
            }
            """.trimIndent(),
        )
        val modules = listOf(ModuleConfiguration("orders", ordersFile), ModuleConfiguration("customers", File(dir, "customers/byterails.kts")))
        val loaded = Byterails.load(File(dir, "byterails.kts"), null, "com.acme", null, null, modules, "customers", dir)
        val domain = loaded.ruleSet.packages.first { it.name == "com.acme.orders.domain" }
        assertEquals("orders/byterails.kts:4", domain.rules.single().location.toString())
        assertEquals("orders/byterails.kts:4", domain.location.toString())
        val customersRoot = loaded.ruleSet.packages.first { it.name == "com.acme.customers" }
        assertEquals("orders/byterails.kts:2", customersRoot.rules.single().location.toString())
        assertEquals("byterails.kts:3", loaded.ruleSet.packages.first { it.name == "com.acme.common" }.location.toString())

        File(dir, "customers/byterails.kts").apply { parentFile.mkdirs() }.writeText("byterails {\n    pkg(\"domain\") { exclusive(\"java.util\") }\n}\n")
        val clash = assertFailsWith<ConfigException> { Byterails.load(File(dir, "byterails.kts"), null, "com.acme", null, null, modules, "customers", dir) }
        assertTrue(clash.message!!.contains("customers/byterails.kts:2: exclusive(\"java.util\") in \"com.acme.customers.domain\" clashes"), clash.message)

        val absentRoot = Byterails.load(File(dir, "missing.kts"), null, "com.acme", null, null, listOf(ModuleConfiguration("orders", ordersFile)), "orders", dir)
        assertEquals(listOf("com.acme.orders", "com.acme.orders.api", "com.acme.orders.domain"), absentRoot.ruleSet.packages.map { it.name }, "with modules the root file may be absent")
    }
}
