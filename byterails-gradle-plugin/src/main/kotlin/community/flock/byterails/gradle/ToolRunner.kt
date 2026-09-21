package community.flock.byterails.gradle

import org.gradle.api.GradleException
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * Loads the byterails core in a class loader whose parent is the platform loader, so the Kotlin
 * compiler inside it never sees Gradle's Kotlin standard library. One loader is kept per distinct
 * classpath for the life of the daemon.
 */
internal object ToolRunner {

    private const val RUNNER_CLASS = "community.flock.byterails.ByterailsRunner"

    private val loaders = ConcurrentHashMap<String, URLClassLoader>()

    fun run(
        classpath: Set<File>,
        rulesFile: File?,
        classDirs: List<File>,
        reportFile: File,
        cacheDir: File,
        basePackage: String?,
        slices: List<String>,
        defaultRules: List<String>,
        out: Consumer<String>,
    ): Int {
        if (classpath.isEmpty()) throw GradleException("byterails: the tool classpath is empty; set byterails.toolClasspath or check repositories")
        val loader = loaders.computeIfAbsent(classpath.joinToString(File.pathSeparator) { it.absolutePath }) { key ->
            URLClassLoader("byterails", classpath.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader())
        }
        val runner = loader.loadClass(RUNNER_CLASS)
        val method = runner.getMethod(
            "run", File::class.java, List::class.java, File::class.java, File::class.java, String::class.java, List::class.java, List::class.java, Consumer::class.java,
        )
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = loader
        try {
            return method.invoke(null, rulesFile, classDirs, reportFile, cacheDir, basePackage, slices, defaultRules, out) as Int
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            throw GradleException(cause.message ?: cause.toString(), cause)
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }
}
