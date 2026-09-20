package community.flock.byterails.script

import community.flock.byterails.dsl.ByterailsBuilder
import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.RuleSet
import community.flock.byterails.model.SourceLocation
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm

/**
 * The script definition behind `byterails.kts`. A script calls `byterails { }` exactly once.
 */
@KotlinScript(
    fileExtension = "byterails.kts",
    compilationConfiguration = ByterailsScriptCompilationConfiguration::class,
)
abstract class ByterailsScript {

    private var built: RuleSet? = null

    fun byterails(block: ByterailsBuilder.() -> Unit) {
        if (built != null) throw ConfigException("byterails { } may appear only once", SourceLocation.capture())
        built = community.flock.byterails.dsl.byterails(block)
    }

    internal fun ruleSetOrNull(): RuleSet? = built
}

object ByterailsScriptCompilationConfiguration : ScriptCompilationConfiguration({
    defaultImports("community.flock.byterails.dsl.*")
    jvm {
        dependenciesFromClassContext(ByterailsScript::class, wholeClasspath = true)
    }
})
