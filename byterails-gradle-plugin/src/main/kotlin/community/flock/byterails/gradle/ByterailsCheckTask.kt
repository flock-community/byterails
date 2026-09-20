package community.flock.byterails.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class ByterailsCheckTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rulesFile: RegularFileProperty

    @get:InputFiles
    @get:Classpath
    abstract val classDirs: ConfigurableFileCollection

    @get:InputFiles
    @get:Classpath
    abstract val toolClasspath: ConfigurableFileCollection

    @get:Input
    abstract val reportOnly: Property<Boolean>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @get:LocalState
    abstract val scriptCacheDir: DirectoryProperty

    @TaskAction
    fun check() {
        val rules = rulesFile.get().asFile
        val dirs = classDirs.files.filter { it.isDirectory }
        val report = reportFile.get().asFile
        val cache = scriptCacheDir.get().asFile
        val violations = ToolRunner.run(toolClasspath.files, rules, dirs, report, cache) { line -> logger.lifecycle(line) }
        if (violations > 0 && !reportOnly.get()) {
            val noun = if (violations == 1) "violation" else "violations"
            throw GradleException("byterails found $violations $noun; see the lines above or $report")
        }
    }
}
