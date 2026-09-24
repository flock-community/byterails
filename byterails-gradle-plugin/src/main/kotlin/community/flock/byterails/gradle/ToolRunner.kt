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
        rootDir: File,
        module: String?,
        modules: List<ModuleSpec>,
        out: Consumer<String>,
    ): Int {
        if (classpath.isEmpty()) throw GradleException("byterails: the tool classpath is empty; set byterails.toolClasspath or check repositories")
        val loader = loaders.computeIfAbsent(classpath.joinToString(File.pathSeparator) { it.absolutePath }) { key ->
            URLClassLoader("byterails", classpath.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader())
        }
        val runner = loader.loadClass(RUNNER_CLASS)
        val method = runner.getMethod(
            "run", File::class.java, List::class.java, File::class.java, File::class.java, String::class.java, List::class.java, List::class.java,
            File::class.java, String::class.java, List::class.java, Consumer::class.java,
        )
        // JDK collections only: the core's Kotlin is not the one this plugin was compiled against.
        val moduleMaps = java.util.ArrayList(modules.map { it.toMap() })
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = loader
        try {
            return method.invoke(
                null, rulesFile, java.util.ArrayList(classDirs), reportFile, cacheDir, basePackage, java.util.ArrayList(slices), java.util.ArrayList(defaultRules),
                rootDir, module, moduleMaps, out,
            ) as Int
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            throw GradleException(cause.message ?: cause.toString(), cause)
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }
}
