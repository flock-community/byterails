package community.flock.byterails

import community.flock.byterails.analysis.Site
import community.flock.byterails.check.CheckResult
import community.flock.byterails.check.RuleRef
import community.flock.byterails.check.Violation
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

    private val result = CheckResult(listOf(denied, notAllowed), 1204, 17, emptyList())

    @Test
    fun `console output follows the documented shape`() {
        val lines = ConsoleReporter.render(result)
        assertEquals("byterails: DENIED       com.acme.domain.Order", lines[0])
        assertEquals("  field    entityManager : jakarta.persistence.EntityManager", lines[1])
        assertEquals("  rule     deny(\"jakarta.persistence\")              byterails.kts:14  in \"com.acme.domain\"", lines[2])
        assertEquals("  source   Order.kt", lines[3])
        assertEquals("", lines[4])
        assertEquals("byterails: NOT ALLOWED  com.acme.domain.OrderService", lines[5])
        assertEquals("  method   place(Order) : void", lines[6])
        assertEquals("  ref      org.springframework.web.client.RestTemplate", lines[7])
        assertEquals("  allows   com.acme.domain, java.lang, java.time, java.util, kotlin", lines[8])
        assertEquals("  source   OrderService.kt:42", lines[9])
        assertEquals("byterails: 2 violations in 1,204 classes, 17 packages", lines.last())
    }

    @Test
    fun `a clean result is one line`() {
        val lines = ConsoleReporter.render(CheckResult(emptyList(), 3, 1, emptyList()))
        assertEquals(listOf("byterails: 0 violations in 3 classes, 1 packages"), lines)
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
