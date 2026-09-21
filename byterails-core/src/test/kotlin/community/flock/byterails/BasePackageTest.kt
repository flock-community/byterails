package community.flock.byterails

import community.flock.byterails.dsl.byterails
import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.withBasePackage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class BasePackageTest {

    private val relative = byterails {
        allow("kotlin")
        allow("domain")
        pkg("domain")
        pkg("application") {
            allow("domain.model")
            exclusive("org.jooq")
        }
        pkg("infra.web") { allow("application") }
    }

    @Test
    fun `declarations are prefixed and rules that point into the tree follow`() {
        val resolved = relative.withBasePackage("com.acme")
        assertEquals(listOf("com.acme.domain", "com.acme.application", "com.acme.infra.web"), resolved.packages.map { it.name })
        assertEquals(listOf("kotlin", "com.acme.domain"), resolved.rootRules.map { it.prefix.name })
        val application = resolved.packages[1]
        assertEquals(listOf("com.acme.domain.model", "org.jooq"), application.rules.map { it.prefix.name })
        assertEquals(listOf("com.acme.application"), resolved.packages[2].rules.map { it.prefix.name })
    }

    @Test
    fun `rule locations survive`() {
        val resolved = relative.withBasePackage("com.acme")
        assertEquals(relative.packages[1].rules[0].location, resolved.packages[1].rules[0].location)
    }

    @Test
    fun `no base package leaves the rule set alone`() {
        assertSame(relative, relative.withBasePackage(null))
        assertSame(relative, relative.withBasePackage(" "))
    }

    @Test
    fun `a malformed base package is a configuration error`() {
        val error = assertFailsWith<ConfigException> { relative.withBasePackage("com.acme.") }
        assertEquals(true, error.message!!.contains("base package"), error.message)
    }

    @Test
    fun `the resolved rule set checks classes under the base`() {
        val rules = byterails {
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
        }.withBasePackage("fixtures")
        val result = Byterails.check(rules, Fixtures.classDirs)
        val expected = Byterails.check(Fixtures.rules(), Fixtures.classDirs)
        assertEquals(expected.violations.map { it.kind to it.className.name }, result.violations.map { it.kind to it.className.name })
    }
}
