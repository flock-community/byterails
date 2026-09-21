package community.flock.byterails.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class ByterailsCheckTask : DefaultTask() {

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rulesFile: RegularFileProperty

    /** The path the rules file was expected at, for the message when it is absent. */
    @get:Input
    abstract val rulesFileConfigured: Property<String>

    @get:InputFiles
    @get:Classpath
    abstract val classDirs: ConfigurableFileCollection

    @get:InputFiles
    @get:Classpath
    abstract val toolClasspath: ConfigurableFileCollection

    @get:Input
    abstract val reportOnly: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val basePackage: Property<String>

    @get:Input
    abstract val slices: ListProperty<String>

    @get:Input
    abstract val defaultRules: ListProperty<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @get:LocalState
    abstract val scriptCacheDir: DirectoryProperty

    @TaskAction
    fun check() {
        val rules = rulesFile.orNull?.asFile
        if (rules == null && defaultRules.get().isEmpty()) {
            throw GradleException("byterails: rules file ${rulesFileConfigured.get()} does not exist and no defaultRules are set")
        }
        val dirs = classDirs.files.filter { it.isDirectory }
        val report = reportFile.get().asFile
        val cache = scriptCacheDir.get().asFile
        val base = basePackage.orNull
        val violations = ToolRunner.run(toolClasspath.files, rules, dirs, report, cache, base, slices.get(), defaultRules.get()) { line -> logger.lifecycle(line) }
        if (violations > 0 && !reportOnly.get()) {
            val noun = if (violations == 1) "violation" else "violations"
            throw GradleException("byterails found $violations $noun; see the lines above or $report")
        }
    }
}
