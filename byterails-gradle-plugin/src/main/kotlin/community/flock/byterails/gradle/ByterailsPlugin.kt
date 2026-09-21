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
        extension.reportOnly.convention(
            project.providers.gradleProperty("byterails.reportOnly").map { it.equals("true", ignoreCase = true) }.orElse(false),
        )

        val tool = project.configurations.create(TOOL_CONFIGURATION) { configuration ->
            configuration.isCanBeConsumed = false
            configuration.isVisible = false
            configuration.description = "The byterails core used by the byterailsCheck task"
            configuration.defaultDependencies { dependencies ->
                dependencies.add(project.dependencies.create("community.flock.byterails:byterails-core:${pluginVersion()}"))
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
            task.slices.set(extension.slices)
            task.defaultRules.set(extension.defaultRules)
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

        fun pluginVersion(): String =
            ByterailsPlugin::class.java.getResourceAsStream("/META-INF/byterails.properties").use { stream ->
                Properties().apply { if (stream != null) load(stream) }.getProperty("version")
            } ?: error("byterails plugin version is missing from META-INF/byterails.properties")
    }
}
