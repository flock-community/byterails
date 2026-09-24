package community.flock.byterails.script

import community.flock.byterails.model.ConfigException
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
     * @throws ConfigException when the script does not compile, throws, or never calls `byterails { }`.
     */
    fun load(file: File, cacheDir: File? = null, displayName: String = file.name): RuleSet {
        if (!file.isFile) throw ConfigException("rules file ${file.path} does not exist")
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

    private fun ruleSetFrom(file: File, displayName: String, result: ResultWithDiagnostics<EvaluationResult>): RuleSet {
        val problems = result.reports
            .filter { it.severity == ScriptDiagnostic.Severity.ERROR || it.severity == ScriptDiagnostic.Severity.FATAL }
            .map { ConfigProblem(Severity.ERROR, it.message, it.location?.start?.line?.let { line -> SourceLocation(displayName, line) }) }
        if (problems.isNotEmpty()) throw ConfigException(problems)
        val evaluated = result.valueOrNull() ?: throw ConfigException("rules file $displayName could not be evaluated")
        val returnValue = evaluated.returnValue
        if (returnValue is ResultValue.Error) {
            val cause = returnValue.error
            if (cause is ConfigException) throw cause.renamed(file.name, displayName)
            throw ConfigException("rules file $displayName failed: ${cause::class.simpleName}: ${cause.message}")
        }
        val script = returnValue.scriptInstance as? ByterailsScript
            ?: throw ConfigException("rules file $displayName could not be evaluated")
        val ruleSet = script.ruleSetOrNull() ?: throw ConfigException("rules file $displayName never calls byterails { }")
        return ruleSet.renamed(file.name, displayName)
    }

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
        )
    }

    private fun ConfigException.renamed(from: String, to: String): ConfigException =
        if (from == to) this else ConfigException(problems.map { problem -> problem.copy(location = problem.location?.let { if (it.file == from) it.copy(file = to) else it }) })

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
