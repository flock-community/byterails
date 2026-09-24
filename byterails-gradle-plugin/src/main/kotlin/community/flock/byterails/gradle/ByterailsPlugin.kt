package community.flock.byterails.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.tasks.SourceSetContainer
import java.util.Properties

class ByterailsPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("byterails", ByterailsExtension::class.java)
        extension.rulesFile.convention(project.rootProject.layout.projectDirectory.file("byterails.kts"))
        extension.moduleRulesFile.convention(project.layout.projectDirectory.file("byterails.kts"))
        extension.reportOnly.convention(
            project.providers.gradleProperty("byterails.reportOnly").map { it.equals("true", ignoreCase = true) }.orElse(false),
        )
        // The root project configures what is shared: a module project inherits its base package, and
        // the root project's slices and default rules stay with the root rules file.
        val rootExtension = if (project === project.rootProject) null else project.rootProject.extensions.findByType(ByterailsExtension::class.java)
        if (rootExtension != null) extension.basePackage.convention(rootExtension.basePackage)

        val tool = project.configurations.create(TOOL_CONFIGURATION) { configuration ->
            configuration.isCanBeConsumed = false
            configuration.isVisible = false
            configuration.description = "The byterails core and default rules used by the byterailsCheck task"
            configuration.defaultDependencies { dependencies ->
                dependencies.add(project.dependencies.create("community.flock.byterails:byterails-rules:${pluginVersion()}"))
            }
        }
        extension.toolClasspath.from(tool)

        val check = project.tasks.register(CHECK_TASK, ByterailsCheckTask::class.java) { task ->
            task.group = "verification"
            task.description = "Checks the compiled main classes against byterails.kts"
            // Absent file: only an error when no default rules stand in for it, which the runner reports.
            task.rulesFile.set(extension.rulesFile.filter { it.asFile.isFile })
            task.rulesFileConfigured.set(extension.rulesFile.map { it.asFile.path })
            task.reportOnly.set(extension.reportOnly)
            task.basePackage.set(extension.basePackage)
            task.slices.set(project.provider { if (extension.module.isPresent) rootExtension?.slices?.get().orEmpty() else extension.slices.get() })
            task.defaultRules.set(project.provider { if (extension.module.isPresent) rootExtension?.defaultRules?.get().orEmpty() else extension.defaultRules.get() })
            task.module.set(extension.module)
            task.moduleRulesFileConfigured.set(extension.moduleRulesFile.map { it.asFile.path })
            task.modules.set(project.provider { modules(project) })
            task.rootDir.set(project.rootProject.layout.projectDirectory)
            task.toolClasspath.from(extension.toolClasspath)
            task.reportFile.set(project.layout.buildDirectory.file("reports/byterails/violations.json"))
            task.scriptCacheDir.set(project.layout.buildDirectory.dir("byterails/script-cache"))
        }

        project.plugins.withType(JavaBasePlugin::class.java) {
            val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
            check.configure { task ->
                task.classDirs.from(sourceSets.named("main").map { it.output.classesDirs })
            }
            project.tasks.named("check") { it.dependsOn(check) }
        }
    }

    companion object {
        const val CHECK_TASK = "byterailsCheck"
        const val TOOL_CONFIGURATION = "byterails"

        /**
         * Every module of the build: each project that applies the plugin and names a module. Read when
         * the task's inputs are resolved, once every project is configured.
         */
        fun modules(project: Project): List<ModuleSpec> =
            project.rootProject.allprojects.mapNotNull { candidate ->
                val extension = candidate.extensions.findByType(ByterailsExtension::class.java) ?: return@mapNotNull null
                val name = extension.module.orNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ModuleSpec(name, extension.moduleRulesFile.orNull?.asFile?.takeIf { it.isFile }, extension.slices.get(), extension.defaultRules.get())
            }

        fun pluginVersion(): String =
            ByterailsPlugin::class.java.getResourceAsStream("/META-INF/byterails.properties").use { stream ->
                Properties().apply { if (stream != null) load(stream) }.getProperty("version")
            } ?: error("byterails plugin version is missing from META-INF/byterails.properties")
    }
}
