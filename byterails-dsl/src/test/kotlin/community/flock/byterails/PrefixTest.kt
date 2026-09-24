package community.flock.byterails

import community.flock.byterails.model.ClassName
import community.flock.byterails.model.Prefix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrefixTest {

    @Test
    fun `matches on segment boundaries only`() {
        val domain = Prefix.parse("com.acme.domain")
        assertTrue(domain.covers(Prefix.parse("com.acme.domain")))
        assertTrue(domain.covers(Prefix.parse("com.acme.domain.model")))
        assertFalse(domain.covers(Prefix.parse("com.acme.domainservice")))
        assertFalse(domain.covers(Prefix.parse("com.acme")))
    }

    @Test
    fun `a prefix may name a class and covers its nested classes`() {
        val system = Prefix.parse("java.lang.System")
        assertTrue(system.covers(ClassName("java/lang/System")))
        assertTrue(system.covers(ClassName("java/lang/System\$Logger")))
        assertFalse(system.covers(ClassName("java/lang/SystemClassLoaderAction")))
        assertFalse(system.covers(ClassName("java/lang/String")))
    }

    @Test
    fun `package matching ignores the class name`() {
        assertTrue(Prefix.parse("com.acme").coversPackage("com.acme"))
        assertTrue(Prefix.parse("com.acme").coversPackage("com.acme.domain"))
        assertFalse(Prefix.parse("com.acme").coversPackage("com.acmex"))
        assertFalse(Prefix.parse("com.acme").coversPackage(""))
    }

    @Test
    fun `class names split into package and nested segments`() {
        val name = ClassName("com/acme/Foo\$Bar")
        assertEquals("com.acme.Foo\$Bar", name.name)
        assertEquals("com.acme", name.packageName)
        assertEquals("Foo\$Bar", name.simpleName)
        assertEquals("Foo", name.outermostSimpleName)
        assertEquals(listOf("com", "acme", "Foo", "Bar"), name.segments)
        assertTrue(name.isNested)
        assertEquals("", ClassName("Default").packageName)
    }

    @Test
    fun `malformed prefixes are rejected with a reason`() {
        assertFailsWith<IllegalArgumentException> { Prefix.parse("") }
        assertFailsWith<IllegalArgumentException> { Prefix.parse("com.acme.") }
        assertFailsWith<IllegalArgumentException> { Prefix.parse(".com.acme") }
        assertFailsWith<IllegalArgumentException> { Prefix.parse("com..acme") }
        assertFailsWith<IllegalArgumentException> { Prefix.parse("com.acme-web") }
        assertEquals("prefix \"com.1x\" contains an invalid segment".length > 0, true)
        val error = assertFailsWith<IllegalArgumentException> { Prefix.parse("com.1x") }
        assertTrue(error.message!!.contains("\"1x\""), error.message)
    }
}
