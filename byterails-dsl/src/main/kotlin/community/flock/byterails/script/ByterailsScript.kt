package community.flock.byterails.script

import community.flock.byterails.dsl.ByterailsBuilder
import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.RuleSet
import community.flock.byterails.model.SourceLocation
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.filePathPattern
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm

/**
 * The script definition behind `byterails.kts`. A script calls `byterails { }` exactly once.
 *
 * An IDE finds this definition through the marker `META-INF/kotlin/script/templates/` names, in any
 * jar on a module's classpath, and applies it to a file that ends in `byterails.kts`: the bare name
 * next to a build file as well as a qualified one such as `orders.byterails.kts`.
 */
@KotlinScript(
    fileExtension = "byterails.kts",
    compilationConfiguration = ByterailsScriptCompilationConfiguration::class,
)
abstract class ByterailsScript {

    private var built: RuleSet? = null

    fun byterails(block: ByterailsBuilder.() -> Unit) {
        if (built != null) throw ConfigException("byterails { } appears twice; a rules file calls it once", SourceLocation.capture())
        // Built without throwing: what the DSL finds wrong travels in the rule set, so the loader reports it together with what the validator finds.
        built = ByterailsBuilder().apply(block).build()
    }

    /** The rule set the script built, or null when it never called `byterails { }`. Read by the loader in the core. */
    fun ruleSetOrNull(): RuleSet? = built
}

object ByterailsScriptCompilationConfiguration : ScriptCompilationConfiguration({
    defaultImports("community.flock.byterails.dsl.*", "community.flock.byterails.rules.*")
    // The extension alone matches only `<name>.byterails.kts`; the pattern takes the bare file name too.
    filePathPattern(".*byterails\\.kts")
    jvm {
        dependenciesFromClassContext(ByterailsScript::class, wholeClasspath = true)
    }
})
