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

    /**
     * @param out the lines every run prints, and any note about the tool itself.
     * @param detail the verbose lines, when the core on the classpath produces them.
     * @param verbose whether [detail] reaches the developer, so an older core can say that it does not produce them.
     */
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
        verbose: Boolean,
        out: Consumer<String>,
        detail: Consumer<String>,
    ): Int {
        if (classpath.isEmpty()) throw GradleException("byterails: the tool classpath is empty; set byterails.toolClasspath or check repositories")
        val loader = loaders.computeIfAbsent(classpath.joinToString(File.pathSeparator) { it.absolutePath }) { key ->
            URLClassLoader("byterails", classpath.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader())
        }
        val runner = loader.loadClass(RUNNER_CLASS)
        // JDK collections only: the core's Kotlin is not the one this plugin was compiled against.
        val moduleMaps = java.util.ArrayList(modules.map { it.toMap() })
        val shared = arrayOf<Any?>(
            rulesFile, java.util.ArrayList(classDirs), reportFile, cacheDir, basePackage, java.util.ArrayList(slices), java.util.ArrayList(defaultRules),
            rootDir, module, moduleMaps,
        )
        val sharedTypes = arrayOf(
            File::class.java, List::class.java, File::class.java, File::class.java, String::class.java, List::class.java, List::class.java,
            File::class.java, String::class.java, List::class.java,
        )
        // A core older than the plugin has no verbose stream; it is still run, and told apart by the missing overload.
        val withDetail = try {
            runner.getMethod("run", *sharedTypes, Map::class.java, Consumer::class.java, Consumer::class.java)
        } catch (e: NoSuchMethodException) {
            null
        }
        val options = java.util.HashMap<String, Any?>()
        options["verboseSwitch"] = VERBOSE_SWITCH
        options["verbose"] = verbose
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = loader
        try {
            return if (withDetail != null) {
                withDetail.invoke(null, *shared, options, out, detail) as Int
            } else {
                if (verbose) out.accept("byterails: the byterails core on the tool classpath is older than the plugin, so verbose output is not available")
                runner.getMethod("run", *sharedTypes, Consumer::class.java).invoke(null, *shared, out) as Int
            }
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            throw GradleException(cause.message ?: cause.toString(), cause)
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    /** How a Gradle build turns verbose output on, named in the lines that say what was left out. */
    const val VERBOSE_SWITCH = "-Pbyterails.verbose=true"
}
