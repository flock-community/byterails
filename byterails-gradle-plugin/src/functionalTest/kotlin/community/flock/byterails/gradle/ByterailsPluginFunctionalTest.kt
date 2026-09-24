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

    private fun project(rules: String?, vararg sources: Pair<String, String>, extra: String = ""): File {
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
                $extra
            }
            """.trimIndent(),
        )
        if (rules != null) File(dir, "byterails.kts").writeText(rules.trimIndent())
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
    fun `basePackage makes the rules file relative to the project's root package`() {
        val dir = project(
            """
            byterails {
                allow("java.lang")
                pkg("domain")
                pkg("infra") {
                    allow("domain")
                    exclusive("java.util")
                }
            }
            """,
            domain, infra, offending,
            extra = "basePackage.set(\"com.acme\")",
        )
        val result = runner(dir).buildAndFail()
        assertTrue(result.output.contains("byterails: EXCLUSIVE    com.acme.domain.OrderList"), result.output)
        assertTrue(result.output.contains("byterails: 1 violation in 3 classes, 2 packages"), result.output)
        assertTrue(!result.output.contains("UNDECLARED"), result.output)
    }

    @Test
    fun `slices are configured in the build and the rules file describes one slice`() {
        val dir = project(
            """
            byterails {
                allow("java.lang")
                slice {
                    exported("api")
                    pkg("api")
                    pkg("domain") { allow("api") }
                }
            }
            """,
            "com/acme/sales/api/SalesApi.java" to """
                package com.acme.sales.api;
                public class SalesApi { public String id() { return "s"; } }
            """,
            "com/acme/sales/domain/Sale.java" to """
                package com.acme.sales.domain;
                public class Sale { com.acme.sales.api.SalesApi api; }
            """,
            "com/acme/billing/domain/Invoice.java" to """
                package com.acme.billing.domain;
                public class Invoice {
                    com.acme.sales.api.SalesApi allowedThroughExport;
                    com.acme.sales.domain.Sale notAllowed;
                }
            """,
            extra = "basePackage.set(\"com.acme\")\n    slices.set(listOf(\"sales\", \"billing\"))",
        )
        val result = runner(dir).buildAndFail()
        assertTrue(result.output.contains("byterails: NOT ALLOWED  com.acme.billing.domain.Invoice"), result.output)
        assertTrue(result.output.contains("field    notAllowed : com.acme.sales.domain.Sale"), result.output)
        assertTrue(!result.output.contains("allowedThroughExport"), "the exported api is allowed: " + result.output)
        assertTrue(result.output.contains("allows   com.acme.billing.api, com.acme.sales.api, java.lang"), result.output)
        assertTrue(result.output.contains("byterails: 1 violation in 3 classes, 3 packages"), result.output)
    }

    @Test
    fun `default rules work without a rules file`() {
        val dir = project(
            null,
            "com/acme/sales/domain/Sale.java" to """
                package com.acme.sales.domain;
                public class Sale { java.util.List<String> lines; java.math.BigDecimal total; }
            """,
            "com/acme/sales/domain/Leak.java" to """
                package com.acme.sales.domain;
                public class Leak { java.net.URI endpoint; }
            """,
            extra = "basePackage.set(\"com.acme\")\n    slices.set(listOf(\"sales\"))\n    defaultRules.set(listOf(\"java\", \"hexagonal\"))",
        )
        val result = runner(dir).buildAndFail()
        assertTrue(result.output.contains("byterails: NOT ALLOWED  com.acme.sales.domain.Leak"), result.output)
        assertTrue(result.output.contains("field    endpoint : java.net.URI"), result.output)
        assertTrue(result.output.contains("allows   [hexagonal]"), result.output)
        assertTrue(result.output.contains("hint     [hexagonal] is the language baseline: kotlin, org.jetbrains.annotations, java.lang, java.util, java.time, java.math, java.text"), result.output)
        assertTrue(result.output.contains("byterails: 1 violation in 2 classes, 1 packages"), result.output)

        val without = project(null, "com/acme/sales/domain/Sale.java" to "package com.acme.sales.domain; public class Sale {}")
        val missing = runner(without).buildAndFail()
        assertTrue(missing.output.contains("does not exist and no defaultRules are set"), missing.output)
    }

    /** A build with modules: the root project sets the base package, three subprojects, two of them modules. */
    private fun modularProject(): File {
        val dir = Files.createTempDirectory("byterails-modules").toFile()
        val files = toolClasspath.split(File.pathSeparator).joinToString(", ") { "\"${it.replace("\\", "\\\\")}\"" }
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"sample\"\ninclude(\"common\", \"orders\", \"customers\", \"billing\")\n")
        File(dir, "build.gradle.kts").writeText(
            """
            plugins {
                id("community.flock.byterails")
            }

            byterails {
                toolClasspath.setFrom(files($files))
                basePackage.set("com.acme")
            }
            """.trimIndent(),
        )
        fun subproject(name: String, module: String?, dependency: String?, rules: String?, vararg sources: Pair<String, String>) {
            val project = File(dir, name).apply { mkdirs() }
            File(project, "build.gradle.kts").writeText(
                """
                plugins {
                    java
                    id("community.flock.byterails")
                }

                byterails {
                    toolClasspath.setFrom(files($files))
                    ${if (module != null) "module.set(\"$module\")" else ""}
                }

                dependencies {
                    ${if (dependency != null) "implementation(project(\":$dependency\"))" else ""}
                }
                """.trimIndent(),
            )
            if (rules != null) File(project, "byterails.kts").writeText(rules.trimIndent())
            sources.forEach { (path, text) ->
                File(project, "src/main/java/$path").apply { parentFile.mkdirs() }.writeText(text.trimIndent())
            }
        }
        File(dir, "byterails.kts").writeText(
            """
            byterails {
                allow("java.lang")
                pkg("common")
            }
            """.trimIndent(),
        )
        subproject(
            "common", null, null, null,
            "com/acme/common/Money.java" to "package com.acme.common; public class Money { public long cents() { return 0; } }",
            "com/acme/orders/Stray.java" to "package com.acme.orders; public class Stray {}",
        )
        subproject(
            "orders", "orders", "common",
            """
            byterails {
                exported("api")
                allow("common")
                pkg("api")
                pkg("domain") {
                    allow("api")
                    exclusive("java.util")
                }
            }
            """,
            "com/acme/orders/api/OrderApi.java" to "package com.acme.orders.api; public interface OrderApi { String id(); }",
            "com/acme/orders/domain/Order.java" to """
                package com.acme.orders.domain;
                public class Order implements com.acme.orders.api.OrderApi {
                    java.util.List<com.acme.common.Money> lines;
                    public String id() { return "1"; }
                }
            """,
        )
        subproject(
            "customers", "customers", "orders",
            """
            byterails {
                pkg("domain")
            }
            """,
            "com/acme/customers/domain/Customer.java" to """
                package com.acme.customers.domain;
                public class Customer {
                    com.acme.orders.api.OrderApi api;
                    com.acme.orders.domain.Order order;
                    java.util.List<String> names;
                }
            """,
            "com/acme/shared/Misplaced.java" to "package com.acme.shared; public class Misplaced {}",
        )
        subproject("billing", null, null, "byterails {\n    pkg(\"domain\")\n}\n", "com/acme/billing/domain/Invoice.java" to "package com.acme.billing.domain; public class Invoice {}")
        return dir
    }

    @Test
    fun `modules have their own rules files, see each other through exports and keep their classes in their package`() {
        val dir = modularProject()
        val result = runner(dir, "--continue").buildAndFail()
        val output = result.output
        assertEquals(TaskOutcome.SUCCESS, result.task(":orders:byterailsCheck")?.outcome, output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":byterailsCheck")?.outcome, "the root project has no classes and validates the whole configuration: " + output)

        assertEquals(TaskOutcome.FAILED, result.task(":customers:byterailsCheck")?.outcome, output)
        assertTrue(output.contains("byterails: NOT ALLOWED  com.acme.customers.domain.Customer"), output)
        assertTrue(output.contains("field    order : com.acme.orders.domain.Order"), output)
        assertTrue(output.contains("allows   com.acme.orders.api, java.lang"), output)
        assertTrue(!output.contains("field    api"), "the exported api package is allowed: " + output)
        assertTrue(output.contains("byterails: EXCLUSIVE    com.acme.customers.domain.Customer"), output)
        assertTrue(output.contains("field    names : java.util.List"), output)
        assertTrue(output.contains("rule     exclusive(\"java.util\")"), output)
        assertTrue(output.contains("orders/byterails.kts:7  in \"com.acme.orders.domain\""), output)
        assertTrue(output.contains("byterails: WRONG MODULE com.acme.shared.Misplaced"), output)
        assertTrue(output.contains("module   is compiled in module \"customers\", which owns \"com.acme.customers\", but lies outside it"), output)

        assertEquals(TaskOutcome.FAILED, result.task(":common:byterailsCheck")?.outcome, output)
        assertTrue(output.contains("byterails: WRONG MODULE com.acme.orders.Stray"), output)
        assertTrue(output.contains("module   belongs to module \"orders\", which owns \"com.acme.orders\", but is compiled outside the modules"), output)

        assertEquals(TaskOutcome.FAILED, result.task(":billing:byterailsCheck")?.outcome, output)
        assertTrue(output.contains("is a module rules file, but the project sets no module name"), output)

        val report = File(dir, "customers/build/reports/byterails/violations.json").readText()
        assertTrue(report.contains("\"module\": \"customers\""), report)
        assertTrue(report.contains("\"kind\": \"WRONG_MODULE\""), report)
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
