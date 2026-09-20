package community.flock.byterails

import community.flock.byterails.dsl.byterails
import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.Severity
import community.flock.byterails.validation.RuleSetValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuleSetValidatorTest {

    private fun errors(block: community.flock.byterails.dsl.ByterailsBuilder.() -> Unit) =
        RuleSetValidator.validate(byterails(block)).filter { it.severity == Severity.ERROR }.map { it.message }

    private fun warnings(block: community.flock.byterails.dsl.ByterailsBuilder.() -> Unit) =
        RuleSetValidator.validate(byterails(block)).filter { it.severity == Severity.WARNING }.map { it.message }

    @Test
    fun `a clean configuration has no problems`() {
        assertEquals(emptyList(), RuleSetValidator.validate(Fixtures.rules()))
    }

    @Test
    fun `duplicate declarations are errors`() {
        val errors = errors {
            pkg("com.acme.domain")
            pkg("com.acme.domain")
        }
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("declared twice"), errors[0])
    }

    @Test
    fun `two packages cannot own the same prefix`() {
        val errors = errors {
            pkg("com.acme.a") { exclusive("org.jooq") }
            pkg("com.acme.b") { exclusive("org.jooq.impl") }
        }
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("clashes"), errors[0])
    }

    @Test
    fun `a child may narrow its parent's exclusive`() {
        val errors = errors {
            pkg("com.acme.infra") { exclusive("org.jooq") }
            pkg("com.acme.infra.sql") { exclusive("org.jooq.impl") }
        }
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `an allow of something another package owns is dead`() {
        val errors = errors {
            pkg("com.acme.a") { exclusive("org.jooq") }
            pkg("com.acme.b") { allow("org.jooq.impl") }
        }
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("can never apply") && errors[0].contains("owns it"), errors[0])
    }

    @Test
    fun `an allow inside the owner's subtree is fine`() {
        val errors = errors {
            pkg("com.acme.a") { exclusive("org.jooq") }
            pkg("com.acme.a.sql") { allow("org.jooq") }
        }
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `a broader root allow next to an exclusive is fine`() {
        val errors = errors {
            allow("org.springframework")
            pkg("com.acme.web") { exclusive("org.springframework.web") }
        }
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `an allow shadowed by a deny in the same block is dead`() {
        val errors = errors {
            pkg("com.acme.a") {
                deny("java.util")
                allow("java.util.concurrent")
            }
        }
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("shadowed by deny(\"java.util\")"), errors[0])
    }

    @Test
    fun `a child cannot allow what an ancestor denies`() {
        val errors = errors {
            deny("java.util.concurrent")
            pkg("com.acme.a") { allow("java.util.concurrent.atomic") }
        }
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("the root block"), errors[0])
    }

    @Test
    fun `a child may narrow an inherited allow with a deny`() {
        val errors = errors {
            allow("java.util")
            pkg("com.acme.domain") { deny("java.util.concurrent") }
        }
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `cycles are warnings that spell out the cycle`() {
        val warnings = warnings {
            pkg("com.acme.a") { allow("com.acme.b") }
            pkg("com.acme.b") { allow("com.acme.c") }
            pkg("com.acme.c") { allow("com.acme.a") }
            pkg("com.acme.d") { allow("com.acme.a") }
        }
        assertEquals(1, warnings.size)
        assertEquals("packages depend on each other in a cycle: com.acme.a -> com.acme.b -> com.acme.c -> com.acme.a", warnings[0])
    }

    @Test
    fun `kotlin packages that compile to java ones are warned about`() {
        val warnings = warnings {
            allow("kotlin.collections")
            pkg("com.acme") { deny("kotlin.String") }
        }
        assertEquals(2, warnings.size)
        assertTrue(warnings[0].contains("java.util"), warnings[0])
        assertTrue(warnings[1].contains("java.lang.String"), warnings[1])
    }

    @Test
    fun `the dsl rejects malformed input where it is written`() {
        val error = assertFailsWith<ConfigException> { byterails { pkg("com.acme.") } }
        assertTrue(error.message!!.contains("must not start or end with a dot"), error.message)
        assertFailsWith<ConfigException> { byterails { pkg("com.acme") { naming { } } } }
        assertFailsWith<ConfigException> { byterails { pkg("com.acme") { naming { matches("(") } } } }
        assertFailsWith<ConfigException> { byterails { pkg("com.acme") { naming { endsWith("A") }; naming { endsWith("B") } } } }
    }

    @Test
    fun `validate throws on errors and returns warnings`() {
        val error = assertFailsWith<ConfigException> {
            Byterails.validate(byterails { pkg("a"); pkg("a") })
        }
        assertTrue(error.message!!.startsWith("byterails: invalid configuration"), error.message)
        val warnings = Byterails.validate(byterails { allow("kotlin.collections") })
        assertEquals(1, warnings.size)
    }
}
