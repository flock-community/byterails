package community.flock.byterails

import community.flock.byterails.analysis.Site
import community.flock.byterails.check.CheckResult
import community.flock.byterails.check.RuleRef
import community.flock.byterails.check.Violation
import community.flock.byterails.check.ViolationGroup
import community.flock.byterails.check.ViolationKind
import community.flock.byterails.model.ClassName
import community.flock.byterails.model.SourceLocation
import community.flock.byterails.report.ConsoleReporter
import community.flock.byterails.report.JsonReporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportersTest {

    private val denied = Violation(
        ViolationKind.DENIED,
        ClassName.fromDotted("com.acme.domain.Order"),
        Site.Field("entityManager"),
        null,
        ClassName.fromDotted("jakarta.persistence.EntityManager"),
        RuleRef("deny(\"jakarta.persistence\")", "com.acme.domain", SourceLocation("byterails.kts", 14)),
        emptyList(),
        "Order.kt",
        "com.acme.domain.Order references jakarta.persistence.EntityManager, denied by deny(\"jakarta.persistence\")",
    )

    private val notAllowed = Violation(
        ViolationKind.NOT_ALLOWED,
        ClassName.fromDotted("com.acme.domain.OrderService"),
        Site.Method("place", "(Lcom/acme/domain/Order;)V"),
        42,
        ClassName.fromDotted("org.springframework.web.client.RestTemplate"),
        null,
        listOf("com.acme.domain", "java.lang", "java.time", "java.util", "kotlin"),
        "OrderService.kt",
        "not allowed",
    )

    private val notAllowedAgain = notAllowed.copy(
        className = ClassName.fromDotted("com.acme.domain.Shipping"),
        site = Site.Field("client"),
        line = null,
        target = ClassName.fromDotted("org.springframework.web.client.RestClient"),
        sourceFile = "Shipping.kt",
    )

    private val result = CheckResult(listOf(denied, notAllowed, notAllowedAgain), 1204, 17, emptyList())

    @Test
    fun `console output follows the documented shape`() {
        val lines = ConsoleReporter.render(result)
        assertEquals("byterails: NOT ALLOWED  com.acme.domain -> org.springframework.web.client", lines[0])
        assertEquals("  allows   com.acme.domain, java.lang, java.time, java.util, kotlin", lines[1])
        assertEquals("  OrderService.place(Order) : void  RestTemplate  OrderService.kt:42", lines[2])
        assertEquals("  Shipping.client                   RestClient    Shipping.kt", lines[3])
        assertEquals("", lines[4])
        assertEquals("byterails: DENIED       com.acme.domain -> jakarta.persistence", lines[5])
        assertEquals("  rule     deny(\"jakarta.persistence\")              byterails.kts:14  in \"com.acme.domain\"", lines[6])
        assertEquals("  Order.entityManager  EntityManager  Order.kt", lines[7])
        assertEquals("", lines[8])
        assertEquals("byterails: 3 violations in 2 groups, 1,204 classes, 17 packages", lines.last())
        assertEquals(10, lines.size)
    }

    @Test
    fun `a clean result is one line`() {
        val lines = ConsoleReporter.render(CheckResult(emptyList(), 3, 1, emptyList()))
        assertEquals(listOf("byterails: 0 violations in 3 classes, 1 packages"), lines)
    }

    @Test
    fun `a group is cut after ten members`() {
        val many = (1..23).map { i ->
            notAllowed.copy(className = ClassName.fromDotted("com.acme.domain.Service$i"), sourceFile = "Service$i.kt", line = i)
        }
        val lines = ConsoleReporter.render(CheckResult(many, 30, 1, emptyList()))
        assertEquals("byterails: NOT ALLOWED  com.acme.domain -> org.springframework.web.client", lines[0])
        assertEquals("  allows   com.acme.domain, java.lang, java.time, java.util, kotlin", lines[1])
        assertEquals("  Service1.place(Order) : void   RestTemplate  Service1.kt:1", lines[2])
        assertEquals("  Service10.place(Order) : void  RestTemplate  Service10.kt:10", lines[11])
        assertEquals("  ... and 13 more; the JSON report lists them all", lines[12])
        assertEquals("", lines[13])
        assertEquals("byterails: 23 violations in 1 group, 30 classes, 1 packages", lines[14])
        assertEquals(15, lines.size)
    }

    @Test
    fun `a different deciding rule or target package is another group`() {
        val otherRule = denied.copy(
            className = ClassName.fromDotted("com.acme.domain.Line"),
            target = ClassName.fromDotted("jakarta.persistence.Entity"),
            rule = RuleRef("deny(\"jakarta\")", null, SourceLocation("byterails.kts", 3)),
        )
        val otherTarget = denied.copy(target = ClassName.fromDotted("jakarta.validation.Valid"))
        val groups = ViolationGroup.of(listOf(denied, otherRule, otherTarget))
        assertEquals(
            listOf("jakarta.persistence" to "deny(\"jakarta\")", "jakarta.persistence" to "deny(\"jakarta.persistence\")", "jakarta.validation" to "deny(\"jakarta.persistence\")"),
            groups.map { it.targetPackage to it.rule?.text },
        )
    }

    @Test
    fun `the per-class kinds group by package`() {
        val stray = Violation(
            ViolationKind.UNDECLARED_PACKAGE, ClassName.fromDotted("com.acme.stray.One"), null, null, null, null, emptyList(), "One.kt",
            "package \"com.acme.stray\" is not declared; the nearest declared package is \"com.acme\"",
        )
        val misnamed = Violation(
            ViolationKind.NAMING, ClassName.fromDotted("com.acme.domain.Order"), null, null, null,
            RuleRef("naming { endsWith(\"UseCase\") }", "com.acme.domain", SourceLocation("byterails.kts", 9)), emptyList(), "Order.kt",
            "com.acme.domain.Order matches none of endsWith(\"UseCase\")",
        )
        val violations = listOf(stray, stray.copy(className = ClassName.fromDotted("com.acme.stray.Two"), sourceFile = "Two.kt"), misnamed)
        val lines = ConsoleReporter.render(CheckResult(violations, 3, 2, emptyList()))
        assertEquals(
            listOf(
                "byterails: NAMING       com.acme.domain",
                "  rule     naming { endsWith(\"UseCase\") }           byterails.kts:9  in \"com.acme.domain\"",
                "  Order  Order.kt",
                "",
                "byterails: UNDECLARED   com.acme.stray",
                "  package  \"com.acme.stray\" is not declared; the nearest declared package is \"com.acme\"",
                "  One  One.kt",
                "  Two  Two.kt",
                "",
                "byterails: 3 violations in 2 groups, 3 classes, 2 packages",
            ),
            lines,
        )
    }

    @Test
    fun `json carries the same facts`() {
        val json = JsonReporter.render(result)
        assertTrue(json.contains("\"schema\": 1"))
        assertTrue(json.contains("\"classes\": 1204"))
        assertTrue(json.contains("\"kind\": \"DENIED\""))
        assertTrue(json.contains("\"site\": {\"kind\": \"field\", \"name\": \"entityManager\"}"))
        assertTrue(json.contains("\"rule\": {\"text\": \"deny(\\\"jakarta.persistence\\\")\", \"package\": \"com.acme.domain\", \"location\": \"byterails.kts:14\"}"))
        assertTrue(json.contains("\"line\": 42"))
        assertTrue(json.contains("\"allows\": [\"com.acme.domain\", \"java.lang\", \"java.time\", \"java.util\", \"kotlin\"]"))
        assertTrue(json.trim().endsWith("}"))
    }
}
