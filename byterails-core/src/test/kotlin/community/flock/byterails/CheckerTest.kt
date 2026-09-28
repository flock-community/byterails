package community.flock.byterails

import community.flock.byterails.analysis.ClassDirScanner
import community.flock.byterails.analysis.Site
import community.flock.byterails.check.CheckResult
import community.flock.byterails.check.Checker
import community.flock.byterails.check.Violation
import community.flock.byterails.check.ViolationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheckerTest {

    private val result: CheckResult = Checker(Fixtures.rules()).check(ClassDirScanner.scan(Fixtures.classDirs))

    private fun of(kind: ViolationKind) = result.violations.filter { it.kind == kind }

    private fun on(className: String) = result.violations.filter { it.className.name == className }

    private fun Violation.targets(name: String) = target?.name == name

    @Test
    fun `an undeclared package is reported once per class`() {
        val undeclared = of(ViolationKind.UNDECLARED_PACKAGE)
        assertEquals(listOf("fixtures.undeclared.Stray"), undeclared.map { it.className.name })
        assertEquals("fixtures.undeclared.Stray lies in package fixtures.undeclared, which is not declared", undeclared[0].message)
    }

    @Test
    fun `a deny wins wherever the reference hides`() {
        val denied = on("fixtures.app.domain.OrderService").filter { it.kind == ViolationKind.DENIED }
        assertTrue(denied.any { it.targets("fixtures.lib.persistence.EntityManager") && it.occurrences.any { o -> (o.site as? Site.Method)?.name == "managers" } })
        assertTrue(denied.any { it.targets("fixtures.lib.persistence.PersistenceException") })
        assertEquals("deny(\"fixtures.lib.persistence\")", denied[0].rule?.text)
        assertEquals("fixtures.app.domain", denied[0].rule?.declaringPackage)
    }

    @Test
    fun `an exclusive owned elsewhere is reported with its owner`() {
        val exclusive = on("fixtures.app.domain.OrderService").filter { it.kind == ViolationKind.EXCLUSIVE }
        val lambda = exclusive.first { it.targets("fixtures.lib.web.RestTemplate") }
        assertTrue(lambda.lines.isNotEmpty(), "the lambda body has line numbers")
        assertEquals("exclusive(\"fixtures.lib.web\")", lambda.rule?.text)
        assertEquals("fixtures.app.infra.web", lambda.rule?.declaringPackage)
        assertTrue(exclusive.any { it.targets("fixtures.lib.jooq.DSLContext") }, "the local variable type is exclusive to persistence")
    }

    @Test
    fun `suspend lambda classes are checked as their own classes`() {
        val nested = result.violations.filter { it.className.name.startsWith("fixtures.app.domain.OrderService\$later") }
        assertTrue(nested.any { it.kind == ViolationKind.EXCLUSIVE && it.targets("fixtures.lib.web.RestTemplate") })
    }

    @Test
    fun `not allowed lists what the package may use`() {
        val notAllowed = on("fixtures.app.domain.OrderService").filter { it.kind == ViolationKind.NOT_ALLOWED }
        val timer = notAllowed.single { it.targets("fixtures.lib.util.Timer") }
        assertEquals(listOf("java.lang", "java.util", "kotlin", "org.jetbrains.annotations"), timer.allows)
        assertEquals("fixtures.app.domain.OrderService uses fixtures.lib.util.Timer, which no rule allows", timer.message)
    }

    @Test
    fun `compiler-emitted references carry a hint`() {
        val result = Checker(community.flock.byterails.dsl.byterails { pkg("fixtures.app.domain") })
            .check(sequenceOf(Fixtures.analyze("fixtures.app.domain.Order")))
        val hinted = result.violations.filter { it.hint != null }.map { it.target?.name to it.hint }
        assertTrue(hinted.any { (target, hint) -> target == "kotlin.Metadata" && hint!!.contains("allow(\"kotlin\")") }, hinted.toString())
        assertTrue(hinted.any { (target, hint) -> target == "java.lang.Object" && hint!!.contains("allow(\"java.lang\")") }, hinted.toString())
        assertTrue(hinted.any { (target, _) -> target == "org.jetbrains.annotations.NotNull" }, hinted.toString())
    }

    @Test
    fun `a constant leaves no trace and therefore no violation`() {
        assertTrue(result.violations.none { it.targets("fixtures.lib.config.Constants") })
        assertTrue(result.violations.none { it.targets("fixtures.lib.config.JavaConstants") })
    }

    @Test
    fun `java references are found in signatures, bootstrap arguments and record components`() {
        val javaUser = on("fixtures.app.javainterop.JavaUser")
        assertTrue(javaUser.any { it.kind == ViolationKind.NOT_ALLOWED && it.targets("fixtures.lib.persistence.EntityManager") && it.occurrences.any { o -> o.site == Site.Field("managers") } })
        assertTrue(javaUser.any { it.kind == ViolationKind.EXCLUSIVE && it.targets("fixtures.lib.web.RestTemplate") })
        val point = on("fixtures.app.javainterop.JavaUser\$Point")
        assertTrue(point.any { it.kind == ViolationKind.EXCLUSIVE && it.targets("fixtures.lib.jooq.DSLContext") })
        assertTrue(result.violations.none { it.targets("java.lang.invoke.StringConcatFactory") }, "java.lang covers java.lang.invoke")
    }

    @Test
    fun `naming applies to top-level classes the user wrote`() {
        val naming = of(ViolationKind.NAMING).map { it.className.name }
        assertEquals(
            listOf(
                "fixtures.app.application.HelpersKt",
                "fixtures.app.application.Wrong",
                "fixtures.app.infra.web.Whatever",
            ),
            naming,
        )
        val wrong = of(ViolationKind.NAMING).first { it.className.simpleName == "Wrong" }
        assertEquals("naming { endsWith(\"UseCase\") }", wrong.rule?.text)
        assertEquals("fixtures.app.application.Wrong must end with \"UseCase\"", wrong.message)
    }

    @Test
    fun `the owner of an exclusive and packages that allow a library are clean`() {
        assertEquals(emptyList(), on("fixtures.app.infra.persistence.OrderRepository"))
        assertEquals(emptyList(), on("fixtures.app.infra.web.OrderController"))
        assertEquals(emptyList(), on("fixtures.app.application.PlaceOrderUseCase"))
        assertEquals(emptyList(), on("fixtures.app.application.PlaceOrderUseCase\$Companion"))
        assertEquals(emptyList(), on("fixtures.app.Application"))
        assertEquals(emptyList(), on("fixtures.lib.util.InlineKt"))
    }

    @Test
    fun `the result counts classes and packages and orders violations by package and class`() {
        assertTrue(result.classCount > 20, "classCount=${result.classCount}")
        assertTrue(result.packageCount >= 11, "packageCount=${result.packageCount}")
        val names = result.violations.map { it.className.packageName + " " + it.className.name }
        assertEquals(names.sorted(), names)
    }

    @Test
    fun `the same violation is reported once per class and target, with every site as an occurrence`() {
        val keys = result.violations.map { it.className to it.target }
        assertEquals(keys.distinct().size, keys.size)
        val caught = on("fixtures.app.domain.OrderService").single { it.targets("java.io.PrintStream") }
        assertEquals(listOf("local", "caught"), caught.occurrences.map { it.member }.distinct())
        assertEquals(listOf(22, 28, 30), caught.lines)
    }

    @Test
    fun `members the compiler generated fold into the member the developer wrote`() {
        val point = on("fixtures.app.javainterop.JavaUser\$Point").single { it.targets("fixtures.lib.jooq.DSLContext") }
        assertEquals(listOf("ctx"), point.occurrences.mapNotNull { it.member }.distinct(), "the record component; accessor, constructor, equals, hashCode and toString are generated")
        assertEquals(5, point.occurrences.filter { it.member == null }.map { it.site }.distinct().size, point.occurrences.toString())
        val lambda = on("fixtures.app.domain.OrderService\$later\$fetch\$1").single { it.targets("fixtures.lib.web.RestTemplate") }
        assertEquals("fixtures.app.domain.OrderService", lambda.reportedClass.name, "a suspend lambda is reported under the class it was written in")
        assertEquals(setOf("later"), lambda.occurrences.map { it.member }.toSet(), "and under the method it was written in")
        val outer = on("fixtures.app.domain.OrderService").single { it.targets("fixtures.lib.web.RestTemplate") }
        assertEquals(setOf("lambdaOnly", "later"), outer.occurrences.mapNotNull { it.member }.toSet(), "the lambda body method is named after its method")
        val customer = result.violations.single { it.className.name == "fixtures.slices.customers.domain.Customer" }
        assertEquals(listOf("bus", "the constructor", "rename"), customer.occurrences.mapNotNull { it.member }.distinct())
    }
}
