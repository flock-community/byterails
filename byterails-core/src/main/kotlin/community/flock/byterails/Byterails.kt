package community.flock.byterails

import community.flock.byterails.analysis.ClassDirScanner
import community.flock.byterails.check.CheckResult
import community.flock.byterails.check.Checker
import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.ConfigProblem
import community.flock.byterails.model.RuleSet
import community.flock.byterails.model.Severity
import community.flock.byterails.model.withBasePackage
import community.flock.byterails.report.ConsoleReporter
import community.flock.byterails.report.JsonReporter
import community.flock.byterails.script.ScriptLoader
import community.flock.byterails.validation.RuleSetValidator
import java.io.File
import java.util.function.Consumer

/** The library entry points: load a rules file, validate a rule set, check class directories. */
object Byterails {

    /**
     * Loads and validates a `byterails.kts` file.
     *
     * @param basePackage an optional package every declaration in the file is relative to; see [withBasePackage].
     */
    fun load(rulesFile: File, scriptCacheDir: File? = null, basePackage: String? = null): Loaded {
        val ruleSet = ScriptLoader.load(rulesFile, scriptCacheDir).withBasePackage(basePackage)
        return Loaded(ruleSet, validate(ruleSet))
    }

    /** Validates a rule set and returns its warnings. */
    fun validate(ruleSet: RuleSet): List<ConfigProblem> {
        val problems = RuleSetValidator.validate(ruleSet)
        val errors = problems.filter { it.severity == Severity.ERROR }
        if (errors.isNotEmpty()) throw ConfigException(errors)
        return problems
    }

    /** Validates the rule set and checks every class file under [classDirs]. */
    fun check(ruleSet: RuleSet, classDirs: Iterable<File>): CheckResult {
        val warnings = validate(ruleSet)
        return Checker(ruleSet, warnings).check(ClassDirScanner.scan(classDirs))
    }

    data class Loaded(val ruleSet: RuleSet, val warnings: List<ConfigProblem>)
}

/**
 * A JDK-types-only entry point for build tools that load the core in an isolated class loader.
 *
 * Returns the number of violations. Throws [ConfigException] for configuration errors.
 */
object ByterailsRunner {

    @JvmStatic
    fun run(
        rulesFile: File,
        classDirs: List<File>,
        reportFile: File?,
        scriptCacheDir: File?,
        basePackage: String?,
        out: Consumer<String>,
    ): Int {
        val loaded = Byterails.load(rulesFile, scriptCacheDir, basePackage)
        val result = Checker(loaded.ruleSet, loaded.warnings).check(ClassDirScanner.scan(classDirs))
        ConsoleReporter.render(result).forEach(out::accept)
        if (reportFile != null) {
            reportFile.parentFile?.mkdirs()
            reportFile.writeText(JsonReporter.render(result))
        }
        return result.violations.size
    }
}
