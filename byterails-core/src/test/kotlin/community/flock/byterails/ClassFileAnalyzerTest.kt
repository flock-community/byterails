package community.flock.byterails

import community.flock.byterails.analysis.AnalyzedClass
import community.flock.byterails.analysis.Reference
import community.flock.byterails.analysis.Site
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One assertion per place a compiler can hide a class name. */
class ClassFileAnalyzerTest {

    private val orderService = Fixtures.analyze("fixtures.app.domain.OrderService")
    private val javaUser = Fixtures.analyze("fixtures.app.javainterop.JavaUser")

    private fun AnalyzedClass.refs(target: String): List<Reference> = references.filter { it.target.name == target }

    private fun AnalyzedClass.ref(target: String, method: String): Reference? =
        refs(target).firstOrNull { (it.site as? Site.Method)?.name == method }

    @Test
    fun `the class header and source file are read`() {
        assertEquals("fixtures.app.domain.OrderService", orderService.name.name)
        assertEquals("OrderService.kt", orderService.sourceFile)
        assertTrue(orderService.annotations.map { it.name }.contains("kotlin.Metadata"), orderService.annotations.toString())
        assertTrue(orderService.refs("java.lang.Object").any { it.site == Site.ClassHeader })
    }

    @Test
    fun `generic signatures of methods are read`() {
        val ref = orderService.ref("fixtures.lib.persistence.EntityManager", "managers")
        assertNotNull(ref, "EntityManager only appears in the Signature attribute of managers()")
        assertNull(ref.line, "a signature has no line number")
    }

    @Test
    fun `generic signatures of fields are read`() {
        val ref = javaUser.refs("fixtures.lib.persistence.EntityManager").single()
        assertEquals(Site.Field("managers"), ref.site)
    }

    @Test
    fun `lambda bodies are read with a line number`() {
        val ref = orderService.refs("fixtures.lib.web.RestTemplate")
            .firstOrNull { (it.site as? Site.Method)?.name?.contains("lambdaOnly") == true }
        assertNotNull(ref, "RestTemplate is only constructed inside the lambda body of lambdaOnly()")
        assertNotNull(ref.line, "the lambda body has line numbers")
        assertTrue((ref.site as Site.Method).name != "lambdaOnly", "the body lives in a synthetic method named after lambdaOnly")
    }

    @Test
    fun `invokedynamic bootstrap arguments are read`() {
        val ref = javaUser.ref("fixtures.lib.web.RestTemplate", "supplier")
        assertNotNull(ref, "RestTemplate::new only exists as a bootstrap method handle")
        assertNotNull(ref.line)
        assertTrue(javaUser.refs("java.lang.invoke.StringConcatFactory").isNotEmpty(), "string concatenation is an invokedynamic")
        assertTrue(javaUser.refs("java.lang.invoke.LambdaMetafactory").isNotEmpty())
    }

    @Test
    fun `local variable types are read`() {
        val ref = orderService.ref("fixtures.lib.jooq.DSLContext", "local")
        assertNotNull(ref, "DSLContext only exists in the local variable table of local()")
    }

    @Test
    fun `catch types are read`() {
        assertNotNull(orderService.ref("fixtures.lib.persistence.PersistenceException", "caught"))
    }

    @Test
    fun `inlined constants leave no reference`() {
        assertTrue(orderService.refs("fixtures.lib.config.Constants").isEmpty(), "a const val is inlined by the compiler")
        assertTrue(javaUser.refs("fixtures.lib.config.JavaConstants").isEmpty(), "a static final int is inlined by the compiler")
    }

    @Test
    fun `inlined function bodies bring their references into the caller`() {
        assertNotNull(orderService.ref("fixtures.lib.util.Timer", "timedWork"))
        assertTrue(orderService.refs("fixtures.lib.util.InlineKt").isEmpty(), "the call to the inline function itself disappears")
    }

    @Test
    fun `suspend lambdas compile to nested classes that are analysed on their own`() {
        val nested = Fixtures.classDirs.asSequence()
            .flatMap { dir -> dir.walkTopDown().filter { it.name.startsWith("OrderService\$later") && it.name.endsWith(".class") } }
            .firstOrNull()
        assertNotNull(nested, "expected a class for the suspend lambda in later()")
        val analyzed = community.flock.byterails.analysis.ClassFileAnalyzer.analyze(nested.readBytes())
        assertTrue(analyzed.name.isNested)
        assertTrue(analyzed.refs("fixtures.lib.web.RestTemplate").isNotEmpty())
    }

    @Test
    fun `record components and permitted subclasses are read`() {
        val point = Fixtures.analyze("fixtures.app.javainterop.JavaUser\$Point")
        assertTrue(point.refs("fixtures.lib.jooq.DSLContext").any { it.site == Site.Field("ctx") })
        val shape = Fixtures.analyze("fixtures.app.javainterop.JavaUser\$Shape")
        assertTrue(shape.refs("fixtures.app.javainterop.JavaUser\$Circle").any { it.site == Site.ClassHeader })
    }

    @Test
    fun `annotations on classes are references and are listed`() {
        val controller = Fixtures.analyze("fixtures.app.infra.web.OrderController")
        assertTrue(controller.annotations.map { it.name }.contains("fixtures.lib.web.RestController"))
        assertTrue(controller.refs("fixtures.lib.web.RestController").any { it.site == Site.ClassHeader })
        val generated = Fixtures.analyze("fixtures.app.application.GeneratedThing")
        assertTrue(generated.isGenerated)
        assertFalse(controller.isGenerated)
    }
}
