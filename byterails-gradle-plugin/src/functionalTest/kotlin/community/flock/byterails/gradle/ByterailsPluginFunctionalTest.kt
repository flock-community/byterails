package community.flock.byterails.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ByterailsPluginFunctionalTest {

    private val toolClasspath: String = System.getProperty("byterails.toolClasspath")
        ?: error("system property byterails.toolClasspath is not set; run through Gradle")

    private fun project(rules: String, vararg sources: Pair<String, String>): File {
        val dir = Files.createTempDirectory("byterails-functional").toFile()
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"sample\"\n")
        val files = toolClasspath.split(File.pathSeparator).joinToString(", ") { "\"${it.replace("\\", "\\\\")}\"" }
        File(dir, "build.gradle.kts").writeText(
            """
            plugins {
                java
                id("community.flock.byterails")
            }

            byterails {
                toolClasspath.setFrom(files($files))
            }
            """.trimIndent(),
        )
        File(dir, "byterails.kts").writeText(rules.trimIndent())
        sources.forEach { (path, text) ->
            File(dir, "src/main/java/$path").apply { parentFile.mkdirs() }.writeText(text.trimIndent())
        }
        return dir
    }

    private fun runner(dir: File, vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(listOf("byterailsCheck", "--configuration-cache", "--stacktrace") + args)

    private val rules = """
        byterails {
            allow("java.lang")
            pkg("com.acme.domain")
            pkg("com.acme.infra") {
                allow("com.acme.domain")
                exclusive("java.util")
            }
        }
    """

    private val domain = "com/acme/domain/Order.java" to """
        package com.acme.domain;

        public class Order {
            public String id() { return "1"; }
        }
    """

    private val infra = "com/acme/infra/OrderRepository.java" to """
        package com.acme.infra;

        import com.acme.domain.Order;
        import java.util.ArrayList;

        public class OrderRepository {
            private final ArrayList<Order> orders = new ArrayList<>();
            public void save(Order order) { orders.add(order); }
        }
    """

    private val offending = "com/acme/domain/OrderList.java" to """
        package com.acme.domain;

        import java.util.List;

        public class OrderList {
            private List<Order> orders;
        }
    """

    @Test
    fun `a clean project passes and the task is cacheable and up to date`() {
        val dir = project(rules, domain, infra)
        val first = runner(dir).build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":byterailsCheck")?.outcome)
        assertTrue(first.output.contains("byterails: 0 violations in 2 classes, 2 packages"), first.output)
        assertTrue(File(dir, "build/reports/byterails/violations.json").isFile)

        val second = runner(dir).build()
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":byterailsCheck")?.outcome)
        assertTrue(second.output.contains("Reusing configuration cache") || second.output.contains("Configuration cache entry reused"), second.output)
    }

    @Test
    fun `a violation fails the build with the documented message`() {
        val dir = project(rules, domain, infra, offending)
        val result: BuildResult = runner(dir).buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":byterailsCheck")?.outcome)
        assertTrue(result.output.contains("byterails: EXCLUSIVE    com.acme.domain.OrderList"), result.output)
        assertTrue(result.output.contains("field    orders : java.util.List"), result.output)
        assertTrue(result.output.contains("rule     exclusive(\"java.util\")"), result.output)
        assertTrue(result.output.contains("byterails.kts:6  in \"com.acme.infra\""), result.output)
        assertTrue(result.output.contains("byterails: 1 violation in 3 classes, 2 packages"), result.output)
        assertTrue(result.output.contains("byterails found 1 violation"), result.output)
    }

    @Test
    fun `report-only mode keeps the build green`() {
        val dir = project(rules, domain, infra, offending)
        val result = runner(dir, "-Pbyterails.reportOnly=true").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":byterailsCheck")?.outcome)
        assertTrue(result.output.contains("byterails: 1 violation in 3 classes, 2 packages"), result.output)
        val report = File(dir, "build/reports/byterails/violations.json").readText()
        assertTrue(report.contains("\"kind\": \"EXCLUSIVE\""), report)
    }

    @Test
    fun `a broken rules file fails before any class is read`() {
        val dir = project(
            """
            byterails {
                pkg("com.acme.a") { exclusive("java.util") }
                pkg("com.acme.b") { exclusive("java.util") }
            }
            """,
            domain,
        )
        val result = runner(dir).buildAndFail()
        assertTrue(result.output.contains("byterails: invalid configuration"), result.output)
        assertTrue(result.output.contains("byterails.kts:3: exclusive(\"java.util\") in \"com.acme.b\" clashes"), result.output)
    }
}
