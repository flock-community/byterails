package community.flock.byterails

import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.ConfigPhase
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
    fun `a compile error names the line and the column and shows the source line`() {
        val file = script(
            """
            byterails {
                alow("kotlin")
            }
            """,
        )
        val error = assertFailsWith<ConfigException> { ScriptLoader.load(file) }
        assertEquals(ConfigPhase.COMPILE, error.phase)
        val problem = error.problems.single()
        assertEquals(SourceLocation("byterails.kts", 2), problem.location)
        assertEquals(5, problem.column)
        assertEquals("    alow(\"kotlin\")", problem.sourceLine)
        assertEquals(
            listOf(
                "byterails: byterails.kts does not compile",
                "  byterails.kts:2:5: ${problem.message}",
                "      alow(\"kotlin\")",
                "      ^",
            ),
            error.message!!.lines(),
        )
        assertTrue(problem.message.contains("alow") && !problem.message.endsWith("."), problem.message)
    }

    @Test
    fun `a malformed prefix is reported where it is written, with the line`() {
        val file = script(
            """
            byterails {
                pkg("com.acme.domain.")
            }
            """,
        )
        val ruleSet = ScriptLoader.load(file)
        assertEquals(emptyList(), ruleSet.packages, "the malformed declaration is left out")
        val error = assertFailsWith<ConfigException> { Byterails.load(file) }
        assertEquals(
            listOf(
                "byterails: 1 problem in byterails.kts",
                "  byterails.kts:2: pkg(\"com.acme.domain.\") must not start or end with a dot",
                "      pkg(\"com.acme.domain.\")",
            ),
            error.message!!.lines(),
        )
    }

    @Test
    fun `every problem of a file is reported in one run, what the DSL finds and what the validator finds`() {
        val file = script(
            """
            byterails {
                pkg("com.acme.")
                pkg("com.acme.a") { naming { endsWith("") } }
                pkg("com.acme.a")
            }
            """,
        )
        val error = assertFailsWith<ConfigException> { Byterails.load(file) }
        assertEquals(ConfigPhase.RULES, error.phase)
        assertEquals(
            listOf(
                "byterails.kts:2: pkg(\"com.acme.\") must not start or end with a dot",
                "byterails.kts:3: endsWith(\"\") is empty; give it some text",
                "byterails.kts:4: pkg(\"com.acme.a\") is declared twice; the first is at byterails.kts:3",
            ),
            error.problems.map { it.toString() },
        )
        assertTrue(error.message!!.startsWith("byterails: 3 problems in byterails.kts"), error.message)
    }

    @Test
    fun `a script that throws names the line and keeps the cause`() {
        val file = script(
            """
            val names = listOf("a", "b")
            byterails {
                pkg(names[5])
            }
            """,
        )
        val error = assertFailsWith<ConfigException> { ScriptLoader.load(file) }
        assertEquals(ConfigPhase.RUN, error.phase)
        assertEquals(SourceLocation("byterails.kts", 3), error.problems.single().location)
        assertTrue(error.cause is ArrayIndexOutOfBoundsException, error.cause.toString())
        assertEquals(
            listOf(
                "byterails: byterails.kts failed while running",
                "  byterails.kts:3: ArrayIndexOutOfBoundsException: Index 5 out of bounds for length 2",
                "      pkg(names[5])",
            ),
            error.message!!.lines(),
        )
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
        assertEquals(
            listOf(
                "byterails: 1 problem in byterails.kts",
                "  byterails.kts:3: exclusive(\"org.jooq\") in pkg(\"com.acme.b\") clashes with exclusive(\"org.jooq\") in pkg(\"com.acme.a\") at byterails.kts:2; only one package can own it",
                "      pkg(\"com.acme.b\") { exclusive(\"org.jooq\") }",
            ),
            error.message!!.lines(),
        )
    }
}
