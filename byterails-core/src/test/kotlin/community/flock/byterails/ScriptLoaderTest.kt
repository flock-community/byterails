package community.flock.byterails

import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.NamePattern
import community.flock.byterails.model.RuleKind
import community.flock.byterails.model.SourceLocation
import community.flock.byterails.script.ScriptLoader
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScriptLoaderTest {

    private fun script(text: String): File {
        val dir = Files.createTempDirectory("byterails-script").toFile()
        return File(dir, "byterails.kts").apply { writeText(text.trimIndent()) }
    }

    @Test
    fun `a script builds a rule set with the line of every rule`() {
        val file = script(
            """
            byterails {
                allow("kotlin")
                pkg("com.acme.domain") {
                    deny("jakarta.persistence")
                    naming { endsWith("Service") }
                }
            }
            """,
        )
        val ruleSet = ScriptLoader.load(file)
        assertEquals(listOf(RuleKind.ALLOW), ruleSet.rootRules.map { it.kind })
        assertEquals(SourceLocation("byterails.kts", 2), ruleSet.rootRules[0].location)
        val domain = ruleSet.packages.single()
        assertEquals("com.acme.domain", domain.name)
        assertEquals(SourceLocation("byterails.kts", 3), domain.location)
        assertEquals(SourceLocation("byterails.kts", 4), domain.rules.single().location)
        assertEquals(SourceLocation("byterails.kts", 5), domain.naming?.location)
        assertEquals(listOf(NamePattern.EndsWith("Service")), domain.naming?.patterns)
    }

    @Test
    fun `a compile error names the line`() {
        val file = script(
            """
            byterails {
                allow("kotlin"
            }
            """,
        )
        val error = assertFailsWith<ConfigException> { ScriptLoader.load(file) }
        assertTrue(error.problems.any { it.location?.file == "byterails.kts" }, error.message)
    }

    @Test
    fun `a malformed prefix is reported where it is written`() {
        val file = script(
            """
            byterails {
                pkg("com.acme.domain.")
            }
            """,
        )
        val error = assertFailsWith<ConfigException> { ScriptLoader.load(file) }
        assertEquals(SourceLocation("byterails.kts", 2), error.problems.single().location)
        assertTrue(error.message!!.contains("must not start or end with a dot"), error.message)
    }

    @Test
    fun `a script that never declares anything is an error`() {
        val file = script("val unused = 1")
        val error = assertFailsWith<ConfigException> { ScriptLoader.load(file) }
        assertTrue(error.message!!.contains("never calls byterails { }"), error.message)
    }

    @Test
    fun `compiled scripts are cached by content`() {
        val file = script(
            """
            byterails {
                allow("kotlin")
                pkg("com.acme")
            }
            """,
        )
        val cache = Files.createTempDirectory("byterails-cache").toFile()
        ScriptLoader.load(file, cache)
        val jars = cache.listFiles { f -> f.name.endsWith(".jar") }.orEmpty()
        assertEquals(1, jars.size, "one compiled script jar expected in $cache")
        val again = ScriptLoader.load(file, cache)
        assertEquals("com.acme", again.packages.single().name)
        assertEquals(1, cache.listFiles { f -> f.name.endsWith(".jar") }.orEmpty().size)
    }

    @Test
    fun `load validates and rejects an inconsistent script`() {
        val file = script(
            """
            byterails {
                pkg("com.acme.a") { exclusive("org.jooq") }
                pkg("com.acme.b") { exclusive("org.jooq") }
            }
            """,
        )
        val error = assertFailsWith<ConfigException> { Byterails.load(file) }
        assertTrue(error.message!!.contains("clashes"), error.message)
    }
}
