package community.flock.byterails

import community.flock.byterails.analysis.ClassDirScanner
import community.flock.byterails.check.Checker
import community.flock.byterails.check.ViolationKind
import community.flock.byterails.dsl.byterails
import community.flock.byterails.model.Prefix
import community.flock.byterails.model.ResolvedRuleSet
import community.flock.byterails.model.RuleKind
import community.flock.byterails.model.withBasePackage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DslTest {

    @Test
    fun `a flat declaration covers the package itself and nothing beneath it`() {
        val rules = byterails {
            allow("kotlin")
            allow("java.lang")
            allow("java.util")
            allow("org.jetbrains.annotations")
            pkg("fixtures.lib")
            pkg("fixtures.app") { flat() }
            pkg("fixtures.app.domain") { allow("fixtures.lib") }
        }
        val result = Checker(rules).check(ClassDirScanner.scan(Fixtures.classDirs))
        val undeclared = result.violations.filter { it.kind == ViolationKind.UNDECLARED_PACKAGE }.map { it.className.packageName }.distinct()
        assertTrue("fixtures.app.application" in undeclared && "fixtures.app.infra.web" in undeclared, undeclared.toString())
        assertFalse("fixtures.app" in undeclared, "the flat package itself is declared")
        assertFalse("fixtures.app.domain" in undeclared, "a declared sub-package is not affected")
        val domainToInfra = result.violations.filter { it.className.packageName == "fixtures.app.domain" && it.target?.name?.startsWith("fixtures.app.infra") == true }
        assertTrue(domainToInfra.all { it.kind == ViolationKind.NOT_ALLOWED }, "the flat parent no longer makes the whole app one subtree")
    }

    @Test
    fun `basePackage declares the base package itself and allowAnything grants the empty prefix`() {
        val rules = byterails {
            basePackage {
                flat()
                allowAnything()
                naming { endsWith("Application") }
            }
            pkg("domain")
        }.withBasePackage("com.acme")
        val base = rules.packages.first { it.name == "com.acme" }
        assertTrue(base.flat)
        assertEquals(listOf("endsWith(\"Application\")"), base.naming?.patterns?.map { it.text })
        assertTrue(base.rules.single().let { it.kind == RuleKind.ALLOW && it.prefix == Prefix.ROOT }, "the base package rewrite leaves the empty prefix alone")
        val resolved = ResolvedRuleSet(rules)
        assertEquals("com.acme.domain", resolved.declarationFor("com.acme.domain")?.name)
        assertEquals(null, resolved.declarationFor("com.acme.shipping"), "a flat base package does not cover its siblings")
    }

    @Test
    fun `an included rule set joins the root block and lands in the slice block only when there is one`() {
        val set = byterails {
            allow("org.slf4j")
            pkg("config")
            slice { pkg("domain") { isolated() } }
        }
        val flat = byterails { allow("org.slf4j"); include(set) }
        assertEquals(listOf("org.slf4j"), flat.rootRules.map { it.prefix.name }, "a root rule the file already has is not repeated")
        assertEquals(listOf("config", "domain"), flat.packages.map { it.name })
        val sliced = byterails { include(set); slice { pkg("api") } }
        assertEquals(listOf("config"), sliced.packages.map { it.name })
        assertEquals(listOf("api", "domain"), sliced.sliceTemplate?.packages?.map { it.name })
    }
}
