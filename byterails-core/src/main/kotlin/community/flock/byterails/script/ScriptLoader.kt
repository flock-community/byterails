package community.flock.byterails.script

import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.ConfigProblem
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
     * @throws ConfigException when the script does not compile, throws, or never calls `byterails { }`.
     */
    fun load(file: File, cacheDir: File? = null): RuleSet {
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
        return ruleSetFrom(file, result)
    }

    private fun ruleSetFrom(file: File, result: ResultWithDiagnostics<EvaluationResult>): RuleSet {
        val problems = result.reports
            .filter { it.severity == ScriptDiagnostic.Severity.ERROR || it.severity == ScriptDiagnostic.Severity.FATAL }
            .map { ConfigProblem(Severity.ERROR, it.message, it.location?.start?.line?.let { line -> SourceLocation(file.name, line) }) }
        if (problems.isNotEmpty()) throw ConfigException(problems)
        val evaluated = result.valueOrNull() ?: throw ConfigException("rules file ${file.name} could not be evaluated")
        val returnValue = evaluated.returnValue
        if (returnValue is ResultValue.Error) {
            val cause = returnValue.error
            if (cause is ConfigException) throw cause
            throw ConfigException("rules file ${file.name} failed: ${cause::class.simpleName}: ${cause.message}")
        }
        val script = returnValue.scriptInstance as? ByterailsScript
            ?: throw ConfigException("rules file ${file.name} could not be evaluated")
        return script.ruleSetOrNull() ?: throw ConfigException("rules file ${file.name} never calls byterails { }")
    }

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
