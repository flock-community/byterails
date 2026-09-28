package community.flock.byterails

import community.flock.byterails.analysis.Site
import community.flock.byterails.check.CheckResult
import community.flock.byterails.check.DeclarationRef
import community.flock.byterails.check.Occurrence
import community.flock.byterails.check.RuleRef
import community.flock.byterails.check.Violation
import community.flock.byterails.check.ViolationGroup
import community.flock.byterails.check.ViolationKind
import community.flock.byterails.model.ClassName
import community.flock.byterails.model.ConfigProblem
import community.flock.byterails.model.Severity
import community.flock.byterails.model.SourceLocation
import community.flock.byterails.report.ConsoleReporter
import community.flock.byterails.report.JsonReporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportersTest {

    private val domain = DeclarationRef("com.acme.domain", "pkg(\"com.acme.domain\")", SourceLocation("byterails.kts", 12), flat = false, isolated = false, ruleSet = null)

    private val denied = Violation(
        ViolationKind.DENIED,
        ClassName.fromDotted("com.acme.domain.Order"),
        ClassName.fromDotted("jakarta.persistence.EntityManager"),
        RuleRef("deny(\"jakarta.persistence\")", "com.acme.domain", SourceLocation("byterails.kts", 14), "pkg(\"com.acme.domain\")"),
        listOf(Occurrence(Site.Field("entityManager"), null, "entityManager")),
        emptyList(),
        domain,
        null,
        "Order.kt",
        "com.acme.domain.Order uses jakarta.persistence.EntityManager, which deny(\"jakarta.persistence\") forbids",
        "com.acme.domain uses jakarta.persistence, which deny(\"jakarta.persistence\") forbids",
        "move the code, or lift the deny",
    )

    private val notAllowed = Violation(
        ViolationKind.NOT_ALLOWED,
        ClassName.fromDotted("com.acme.domain.OrderService"),
        ClassName.fromDotted("org.springframework.web.client.RestTemplate"),
        null,
        listOf(
            Occurrence(Site.Method("place", "(Lcom/acme/domain/Order;)V"), 42, "place"),
            Occurrence(Site.Method("place", "(Lcom/acme/domain/Order;)V"), 44, "place"),
            Occurrence(Site.Method("getClient", "()Lorg/springframework/web/client/RestTemplate;"), null, null),
            Occurrence(Site.Field("client"), null, "client"),
        ),
        listOf("com.acme.domain", "java.lang", "java.time", "java.util", "kotlin"),
        domain,
        null,
        "OrderService.kt",
        "com.acme.domain.OrderService uses org.springframework.web.client.RestTemplate, which no rule allows",
        "com.acme.domain uses org.springframework.web.client, which no rule allows",
        "move the code, or add allow(\"org.springframework.web.client\") to pkg(\"com.acme.domain\")",
    )

    private val notAllowedAgain = notAllowed.copy(
        className = ClassName.fromDotted("com.acme.domain.Shipping"),
        occurrences = listOf(Occurrence(Site.Field("client"), null, "client")),
        target = ClassName.fromDotted("org.springframework.web.client.RestClient"),
        sourceFile = "Shipping.kt",
        message = "com.acme.domain.Shipping uses org.springframework.web.client.RestClient, which no rule allows",
    )

    /** A suspend lambda written in Shipping.send: reported under Shipping, in the member send. */
    private val lambda = notAllowedAgain.copy(
        className = ClassName.fromDotted("com.acme.domain.Shipping\$send\$1"),
        owner = ClassName.fromDotted("com.acme.domain.Shipping"),
        occurrences = listOf(Occurrence(Site.Method("invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;"), 61, "send")),
    )

    private val result = CheckResult(listOf(denied, notAllowed, notAllowedAgain, lambda), 1204, 17, emptyList())

    @Test
    fun `console output follows the documented shape`() {
        assertEquals(
            listOf(
                "byterails: NOT ALLOWED  com.acme.domain uses org.springframework.web.client, which no rule allows",
                "  package  pkg(\"com.acme.domain\") at byterails.kts:12",
                "  may use  com.acme.domain, java.lang, java.time, java.util, kotlin",
                "  fix      move the code, or add allow(\"org.springframework.web.client\") to pkg(\"com.acme.domain\")",
                "  OrderService.kt:42,44  OrderService uses RestTemplate in place, client (+1 generated member)",
                "  Shipping.kt:61         Shipping uses RestClient in send, client",
                "",
                "byterails: DENIED       com.acme.domain uses jakarta.persistence, which deny(\"jakarta.persistence\") forbids",
                "  rule     deny(\"jakarta.persistence\") in pkg(\"com.acme.domain\") at byterails.kts:14",
                "  fix      move the code, or lift the deny",
                "  Order.kt  Order uses EntityManager in entityManager",
                "",
                "byterails: 4 violations in 2 groups, 1,204 classes, 17 packages",
            ),
            ConsoleReporter.render(result),
        )
    }

    @Test
    fun `a clean result is one line`() {
        val lines = ConsoleReporter.render(CheckResult(emptyList(), 3, 1, emptyList()))
        assertEquals(listOf("byterails: 0 violations in 3 classes, 1 package"), lines)
    }

    @Test
    fun `a group is cut after ten members and names the switch that prints the rest`() {
        val many = (1..23).map { i ->
            notAllowed.copy(
                className = ClassName.fromDotted("com.acme.domain.Service$i"),
                sourceFile = "Service$i.kt",
                occurrences = listOf(Occurrence(Site.Method("place", "(Lcom/acme/domain/Order;)V"), i, "place")),
            )
        }
        val lines = ConsoleReporter.render(CheckResult(many, 30, 1, emptyList()), "-Pbyterails.verbose=true")
        assertEquals("byterails: NOT ALLOWED  com.acme.domain uses org.springframework.web.client, which no rule allows", lines[0])
        assertEquals("  Service1.kt:1    Service1 uses RestTemplate in place", lines[4])
        assertEquals("  Service18.kt:18  Service18 uses RestTemplate in place", lines[13], "rows are ordered by source file")
        assertEquals("  ... and 13 more; -Pbyterails.verbose=true prints them all", lines[14])
        assertEquals("", lines[15])
        assertEquals("byterails: 23 violations in 1 group, 30 classes, 1 package", lines[16])
        assertEquals(17, lines.size)
    }

    @Test
    fun `a different deciding rule or target package is another group`() {
        val otherRule = denied.copy(
            className = ClassName.fromDotted("com.acme.domain.Line"),
            target = ClassName.fromDotted("jakarta.persistence.Entity"),
            rule = RuleRef("deny(\"jakarta\")", null, SourceLocation("byterails.kts", 3)),
            headline = "com.acme.domain uses jakarta.persistence, which deny(\"jakarta\") forbids",
        )
        val otherTarget = denied.copy(target = ClassName.fromDotted("jakarta.validation.Valid"), headline = "com.acme.domain uses jakarta.validation, which deny(\"jakarta.persistence\") forbids")
        val groups = ViolationGroup.of(listOf(denied, otherRule, otherTarget))
        assertEquals(
            listOf("jakarta.persistence" to "deny(\"jakarta\")", "jakarta.persistence" to "deny(\"jakarta.persistence\")", "jakarta.validation" to "deny(\"jakarta.persistence\")"),
            groups.map { it.targetPackage to it.rule?.text },
        )
        assertEquals("  rule     deny(\"jakarta\") at the top of byterails.kts:3", ConsoleReporter.render(groups[0])[1])
    }

    private val stray = Violation(
        ViolationKind.UNDECLARED_PACKAGE, ClassName.fromDotted("com.acme.stray.One"), null, null, emptyList(), emptyList(),
        DeclarationRef("com.acme", "pkg(\"com.acme\")", SourceLocation("byterails.kts", 5), flat = true, isolated = false, ruleSet = null), null, "One.kt",
        "com.acme.stray.One lies in package com.acme.stray, which is not declared; pkg(\"com.acme\") at byterails.kts:5 is flat and covers no sub-packages",
        "package com.acme.stray is not declared; pkg(\"com.acme\") at byterails.kts:5 is flat and covers no sub-packages",
        "declare it with pkg(\"com.acme.stray\") in byterails.kts, or move its classes into a declared package",
    )

    private val misnamed = Violation(
        ViolationKind.NAMING, ClassName.fromDotted("com.acme.domain.Order"), null,
        RuleRef("naming { endsWith(\"UseCase\") }", "com.acme.domain", SourceLocation("byterails.kts", 9), "pkg(\"com.acme.domain\")"), emptyList(), emptyList(), null, null, "Order.kt",
        "com.acme.domain.Order must end with \"UseCase\"",
        "classes in com.acme.domain must end with \"UseCase\"",
        "rename the classes, or move them out of com.acme.domain",
    )

    @Test
    fun `the per-class kinds list their classes, and one undeclared package is one block`() {
        val violations = listOf(stray, stray.copy(className = ClassName.fromDotted("com.acme.stray.Two"), sourceFile = "Two.kt"), misnamed)
        val lines = ConsoleReporter.render(CheckResult(violations, 3, 2, emptyList()))
        assertEquals(
            listOf(
                "byterails: UNDECLARED   package com.acme.stray is not declared; pkg(\"com.acme\") at byterails.kts:5 is flat and covers no sub-packages",
                "  fix      declare it with pkg(\"com.acme.stray\") in byterails.kts, or move its classes into a declared package",
                "  com.acme.stray  2 classes  One.kt, Two.kt",
                "",
                "byterails: NAMING       classes in com.acme.domain must end with \"UseCase\"",
                "  rule     naming { endsWith(\"UseCase\") } in pkg(\"com.acme.domain\") at byterails.kts:9",
                "  fix      rename the classes, or move them out of com.acme.domain",
                "  Order.kt  Order",
                "",
                "byterails: 3 violations in 2 groups, 3 classes, 2 packages",
            ),
            lines,
        )
    }

    @Test
    fun `several undeclared packages are one table, capped at twenty`() {
        val packages = (1..23).map { i ->
            val name = "com.acme.p${i.toString().padStart(2, '0')}"
            stray.copy(
                className = ClassName.fromDotted("$name.Thing"), sourceFile = "Thing.kt", declaration = if (i == 1) stray.declaration else null,
                headline = "package $name is not declared", fix = "declare it with pkg(\"$name\") in byterails.kts, or move its classes into a declared package",
            )
        }
        val lines = ConsoleReporter.render(CheckResult(packages, 23, 23, emptyList()))
        assertEquals("byterails: UNDECLARED   23 packages are not declared", lines[0])
        assertEquals("  fix      declare each with pkg(\"...\") in byterails.kts, or move its classes into a declared package; only declared packages may exist", lines[1])
        assertEquals("  com.acme.p01  1 class  Thing.kt   under flat pkg(\"com.acme\") at byterails.kts:5, which covers no sub-packages", lines[2])
        assertEquals("  com.acme.p20  1 class  Thing.kt", lines[21])
        assertEquals("  ... and 3 more packages; --verbose lists every package and class", lines[22])
        assertEquals("byterails: 23 violations in 1 group, 23 classes, 23 packages", lines.last())
    }

    @Test
    fun `rule sets get a legend once, warnings come last and are counted`() {
        val fromSet = notAllowed.copy(
            allows = listOf("[hexagonal]"),
            ruleSets = mapOf("hexagonal" to "the language baseline: kotlin, java.lang"),
            declaration = DeclarationRef("com.acme.domain", "pkg(\"com.acme.domain\")", null, flat = false, isolated = true, ruleSet = "hexagonal"),
            fix = "move the code; pkg(\"com.acme.domain\") comes from the hexagonal rule set and is isolated, so the rules file cannot widen it",
        )
        val warning = ConfigProblem(Severity.WARNING, "packages depend on each other in a cycle: a -> b -> a", SourceLocation("byterails.kts", 7))
        val lines = ConsoleReporter.render(CheckResult(listOf(fromSet, fromSet.copy(target = ClassName.fromDotted("org.springframework.web.client.RestClient"))), 2, 1, listOf(warning)))
        assertEquals(
            listOf(
                "byterails: NOT ALLOWED  com.acme.domain uses org.springframework.web.client, which no rule allows",
                "  package  pkg(\"com.acme.domain\") from the hexagonal rule set, isolated",
                "  may use  [hexagonal]",
                "  fix      move the code; pkg(\"com.acme.domain\") comes from the hexagonal rule set and is isolated, so the rules file cannot widen it",
                "  OrderService.kt:42,44  OrderService uses RestClient in place, client (+1 generated member)",
                "  OrderService.kt:42,44  OrderService uses RestTemplate in place, client (+1 generated member)",
                "",
                "byterails: [hexagonal] stands for the language baseline: kotlin, java.lang",
                "byterails: warning: byterails.kts:7: packages depend on each other in a cycle: a -> b -> a",
                "byterails: 2 violations in 1 group, 2 classes, 1 package, 1 warning",
            ),
            lines,
        )
    }

    @Test
    fun `a long list of allows wraps at commas`() {
        val wide = notAllowed.copy(allows = (1..12).map { "org.springframework.module$it.sub$it" })
        val lines = ConsoleReporter.render(ViolationGroup.of(listOf(wide)).single())
        assertTrue(lines[2].startsWith("  may use  org.springframework.module1.sub1, ") && lines[2].endsWith(","), lines[2])
        assertTrue(lines[3].startsWith("           org.springframework.module"), lines[3])
        assertTrue(lines.all { it.length <= 112 }, lines.joinToString("\n"))
    }

    @Test
    fun `json carries the same facts, one violation per class and type with every site`() {
        val json = JsonReporter.render(result)
        assertTrue(json.contains("\"schema\": 2"))
        assertTrue(json.contains("\"classes\": 1204"))
        assertTrue(json.contains("\"kind\": \"DENIED\""))
        assertTrue(json.contains("\"sites\": [{\"kind\": \"field\", \"name\": \"entityManager\", \"member\": \"entityManager\"}]"))
        assertTrue(json.contains("{\"kind\": \"method\", \"name\": \"place\", \"descriptor\": \"(Lcom/acme/domain/Order;)V\", \"line\": 42, \"member\": \"place\"}"))
        assertTrue(json.contains("{\"kind\": \"method\", \"name\": \"getClient\", \"descriptor\": \"()Lorg/springframework/web/client/RestTemplate;\", \"generated\": true}"))
        assertTrue(json.contains("\"rule\": {\"text\": \"deny(\\\"jakarta.persistence\\\")\", \"package\": \"com.acme.domain\", \"block\": \"pkg(\\\"com.acme.domain\\\")\", \"location\": \"byterails.kts:14\"}"))
        assertTrue(json.contains("\"allows\": [\"com.acme.domain\", \"java.lang\", \"java.time\", \"java.util\", \"kotlin\"]"))
        assertTrue(json.contains("\"declaration\": {\"package\": \"com.acme.domain\", \"text\": \"pkg(\\\"com.acme.domain\\\")\", \"location\": \"byterails.kts:12\", \"flat\": false, \"isolated\": false}"))
        assertTrue(json.contains("\"owner\": \"com.acme.domain.Shipping\""))
        assertTrue(json.contains("\"fix\": \"move the code, or lift the deny\""))
        assertTrue(json.trim().endsWith("}"))
    }
}
