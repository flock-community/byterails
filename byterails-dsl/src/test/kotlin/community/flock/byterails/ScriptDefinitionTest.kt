package community.flock.byterails

import community.flock.byterails.script.ByterailsScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.fileExtension
import kotlin.script.experimental.api.filePathPattern
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What an IDE needs to treat a `byterails.kts` as a byterails script: the marker that names the template and a file match. */
class ScriptDefinitionTest {

    private val configuration = createJvmCompilationConfigurationFromTemplate<ByterailsScript>()

    @Test
    fun `the template is discoverable through the script templates marker`() {
        val marker = "META-INF/kotlin/script/templates/" + ByterailsScript::class.java.name
        assertNotNull(ByterailsScript::class.java.classLoader.getResource(marker), "missing $marker")
    }

    @Test
    fun `the definition claims the bare file name and a qualified one`() {
        assertEquals("byterails.kts", configuration[ScriptCompilationConfiguration.fileExtension])
        val pattern = Regex(assertNotNull(configuration[ScriptCompilationConfiguration.filePathPattern]))
        assertTrue(pattern.matches("byterails.kts"))
        assertTrue(pattern.matches("/work/acme/orders/byterails.kts"))
        assertTrue(pattern.matches("/work/acme/orders.byterails.kts"))
        assertFalse(pattern.matches("/work/acme/build.gradle.kts"))
        assertFalse(pattern.matches("/work/acme/byterails.kts.bak"))
    }
}
