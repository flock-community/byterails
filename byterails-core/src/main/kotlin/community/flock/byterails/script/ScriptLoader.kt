package community.flock.byterails.script

import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.ConfigPhase
import community.flock.byterails.model.ConfigProblem
import community.flock.byterails.model.NamingRules
import community.flock.byterails.model.PackageDeclaration
import community.flock.byterails.model.Rule
import community.flock.byterails.model.RuleSet
import community.flock.byterails.model.Severity
import community.flock.byterails.model.SourceLocation
import java.io.File
import java.security.MessageDigest
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.valueOrNull
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.CompiledScriptJarsCache
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.script.experimental.jvmhost.createJvmEvaluationConfigurationFromTemplate
import kotlin.script.experimental.jvm.compilationCache

/** Evaluates a `byterails.kts` file into a [RuleSet]. */
object ScriptLoader {

    /**
     * @param cacheDir where compiled scripts are kept, keyed by content hash, so an unchanged file
     *   is not compiled again. Null disables the cache.
     * @param displayName how the file is named in locations and messages; the file name by default. A
     *   build with several `byterails.kts` files passes the path relative to its root, so a violation
     *   says which one it means.
     * @return the rule set, with what the DSL found wrong under [RuleSet.problems], so the caller can
     *   report it together with what the validator finds.
     * @throws ConfigException when the script does not compile, throws, or never calls `byterails { }`.
     */
    fun load(file: File, cacheDir: File? = null, displayName: String = file.name): RuleSet {
        if (!file.isFile) throw ConfigException("the rules file ${file.path} does not exist", phase = ConfigPhase.SETTINGS)
        val hostConfiguration = ScriptingHostConfiguration(defaultJvmScriptingHostConfiguration) {
            if (cacheDir != null) {
                cacheDir.mkdirs()
                jvm {
                    compilationCache(CompiledScriptJarsCache { source, configuration -> File(cacheDir, cacheKey(source, configuration) + ".jar") })
                }
            }
        }
        val compilation = createJvmCompilationConfigurationFromTemplate<ByterailsScript>(hostConfiguration)
        val evaluation = createJvmEvaluationConfigurationFromTemplate<ByterailsScript>(hostConfiguration)
        val result = BasicJvmScriptingHost(hostConfiguration).eval(file.toScriptSource(), compilation, evaluation)
        return ruleSetFrom(file, displayName, result)
    }

    /**
     * Attaches the text of the line each problem is on, read from the file it names, so the message
     * can show it. [files] maps a display name to its file; problems in other files are left alone.
     */
    fun attachSource(problems: List<ConfigProblem>, files: Map<String, File>): List<ConfigProblem> {
        val lines = HashMap<String, List<String>>()
        return problems.map { problem ->
            val location = problem.location ?: return@map problem
            if (problem.sourceLine != null) return@map problem
            val file = files[location.file] ?: return@map problem
            val text = lines.getOrPut(location.file) { runCatching { file.readLines() }.getOrDefault(emptyList()) }
            problem.copy(sourceLine = text.getOrNull(location.line - 1))
        }
    }

    private fun ruleSetFrom(file: File, displayName: String, result: ResultWithDiagnostics<EvaluationResult>): RuleSet {
        val source = lazy { runCatching { file.readLines() }.getOrDefault(emptyList()) }
        fun located(line: Int?, column: Int?, message: String) = ConfigProblem(
            Severity.ERROR, message, line?.let { SourceLocation(displayName, it) }, column, line?.let { source.value.getOrNull(it - 1) },
        )
        val compileErrors = result.reports
            .filter { it.severity == ScriptDiagnostic.Severity.ERROR || it.severity == ScriptDiagnostic.Severity.FATAL }
            .map { located(it.location?.start?.line, it.location?.start?.col, it.message.trimEnd('.')) }
        if (compileErrors.isNotEmpty()) throw ConfigException(compileErrors, ConfigPhase.COMPILE)
        val evaluated = result.valueOrNull() ?: throw ConfigException("the rules file $displayName could not be evaluated", phase = ConfigPhase.RUN)
        val returnValue = evaluated.returnValue
        if (returnValue is ResultValue.Error) {
            val cause = returnValue.error
            if (cause is ConfigException) throw cause.renamed(file.name, displayName).withSource(source.value, displayName)
            // The nearest script frame says which line threw; the exception stays attached for the stack trace.
            val line = cause.stackTrace.firstOrNull { it.fileName?.endsWith(".kts") == true }?.lineNumber
            throw ConfigException(listOf(located(line, null, "${cause::class.simpleName}: ${cause.message}")), ConfigPhase.RUN, cause)
        }
        val script = returnValue.scriptInstance as? ByterailsScript
            ?: throw ConfigException("the rules file $displayName could not be evaluated", phase = ConfigPhase.RUN)
        val ruleSet = script.ruleSetOrNull()
            ?: throw ConfigException("the rules file $displayName never calls byterails { }; wrap the rules in byterails { ... }")
        val renamed = ruleSet.renamed(file.name, displayName)
        return renamed.copy(problems = renamed.problems.map { it.withSource(source.value, displayName) })
    }

    private fun ConfigProblem.withSource(lines: List<String>, file: String): ConfigProblem {
        val location = location
        return if (sourceLine != null || location == null || location.file != file) this else copy(sourceLine = lines.getOrNull(location.line - 1))
    }

    private fun ConfigException.withSource(lines: List<String>, file: String): ConfigException =
        ConfigException(problems.map { it.withSource(lines, file) }, phase, cause)

    /** The locations the script captured carry the file name; a build with several files wants them as a path. */
    private fun RuleSet.renamed(from: String, to: String): RuleSet {
        if (from == to) return this
        fun SourceLocation?.renamed() = if (this?.file == from) copy(file = to) else this
        fun Rule.renamed() = copy(location = location.renamed())
        fun NamingRules?.renamed() = this?.copy(location = location.renamed())
        fun PackageDeclaration.renamed() = copy(rules = rules.map { it.renamed() }, naming = naming.renamed(), location = location.renamed())
        return copy(
            rootRules = rootRules.map { it.renamed() },
            packages = packages.map { it.renamed() },
            sliceTemplate = sliceTemplate?.let { template ->
                template.copy(rules = template.rules.map { it.renamed() }, naming = template.naming.renamed(), packages = template.packages.map { it.renamed() }, location = template.location.renamed())
            },
            exported = exported.map { it.copy(location = it.location.renamed()) },
            problems = problems.map { it.copy(location = it.location.renamed()) },
        )
    }

    private fun ConfigException.renamed(from: String, to: String): ConfigException =
        if (from == to) this else ConfigException(problems.map { problem -> problem.copy(location = problem.location?.let { if (it.file == from) it.copy(file = to) else it }) }, phase, cause)

    private fun cacheKey(source: SourceCode, configuration: ScriptCompilationConfiguration): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(source.text.toByteArray())
        digest.update(KotlinVersion.CURRENT.toString().toByteArray())
        digest.update(ByterailsScript::class.java.`package`?.implementationVersion.orEmpty().toByteArray())
        configuration.notTransientData.entries.sortedBy { it.key.name }.forEach { (key, value) ->
            digest.update(key.name.toByteArray())
            digest.update(value.toString().toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
