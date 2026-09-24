package community.flock.byterails

import community.flock.byterails.analysis.ClassDirScanner
import community.flock.byterails.check.CheckResult
import community.flock.byterails.check.Checker
import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.ConfigProblem
import community.flock.byterails.model.ModuleRules
import community.flock.byterails.model.RuleSet
import community.flock.byterails.model.Severity
import community.flock.byterails.model.withBasePackage
import community.flock.byterails.model.withModules
import community.flock.byterails.rules.withDefaultRules
import community.flock.byterails.model.withSlices
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
     * @param slices the slices the file's `slice { }` block applies to, relative to the base package; see [withSlices].
     * @param defaultRules ids of the rule sets byterails ships, see `DefaultRuleSet`; with any of them the rules file may be absent.
     */
    fun load(
        rulesFile: File?,
        scriptCacheDir: File? = null,
        basePackage: String? = null,
        slices: List<String>? = null,
        defaultRules: List<String>? = null,
    ): Loaded = load(rulesFile, scriptCacheDir, basePackage, slices, defaultRules, emptyList(), null, null)

    /**
     * Loads and validates the rules of a build with modules: the root `byterails.kts` plus the rules
     * file of every module, for a check of the classes of [module].
     *
     * @param modules every module of the build, this one included; see [ModuleConfiguration].
     * @param module the module whose classes are checked, or null for a project outside the modules.
     * @param rootDir the root directory of the build; rules files under it are named by their relative
     *   path in locations and messages, so a violation says which `byterails.kts` it means.
     */
    fun load(
        rulesFile: File?,
        scriptCacheDir: File?,
        basePackage: String?,
        slices: List<String>?,
        defaultRules: List<String>?,
        modules: List<ModuleConfiguration>,
        module: String?,
        rootDir: File?,
    ): Loaded {
        fun displayName(file: File): String {
            val root = rootDir?.absoluteFile?.normalize() ?: return file.name
            val relative = file.absoluteFile.normalize().relativeToOrNull(root)?.invariantSeparatorsPath
            return if (relative == null || relative.startsWith("..")) file.name else relative
        }
        val defaults = defaultRules.orEmpty().filter { it.isNotBlank() }
        val fromFile = when {
            rulesFile != null && rulesFile.isFile -> ScriptLoader.load(rulesFile, scriptCacheDir, displayName(rulesFile))
            defaults.isNotEmpty() || modules.isNotEmpty() -> RuleSet(emptyList(), emptyList())
            rulesFile == null -> throw ConfigException("no rules file and no default rules configured")
            else -> throw ConfigException("rules file ${rulesFile.path} does not exist")
        }
        val sliced = slices.orEmpty().any { it.isNotBlank() }
        val moduleRules = modules.map { configuration ->
            val file = configuration.rulesFile?.takeIf { it.isFile }
            val own = file?.let { ScriptLoader.load(it, scriptCacheDir, displayName(it)) } ?: RuleSet(emptyList(), emptyList())
            val moduleSlices = configuration.slices.filter { it.isNotBlank() }
            ModuleRules(configuration.name, own.withDefaultRules(configuration.defaultRules, moduleSlices.isNotEmpty()), moduleSlices)
        }
        val ruleSet = fromFile.withDefaultRules(defaults, sliced).withSlices(slices).withModules(moduleRules, module).withBasePackage(basePackage)
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
 * One module of the build, as the plugins configure it: its name, a package relative to the base
 * package; its own rules file, which may be absent; and the slices and default rules configured on
 * the module's project, which apply under the module.
 */
data class ModuleConfiguration(
    val name: String,
    val rulesFile: File?,
    val slices: List<String> = emptyList(),
    val defaultRules: List<String> = emptyList(),
)

/**
 * A JDK-types-only entry point for build tools that load the core in an isolated class loader.
 *
 * Returns the number of violations. Throws [ConfigException] for configuration errors.
 */
object ByterailsRunner {

    @JvmStatic
    fun run(
        rulesFile: File?,
        classDirs: List<File>,
        reportFile: File?,
        scriptCacheDir: File?,
        basePackage: String?,
        slices: List<String>?,
        defaultRules: List<String>?,
        out: Consumer<String>,
    ): Int = run(rulesFile, classDirs, reportFile, scriptCacheDir, basePackage, slices, defaultRules, null, null, null, out)

    /**
     * The entry point for a build with modules.
     *
     * @param rootDir the root directory of the build, for naming rules files by their relative path.
     * @param module the module whose classes [classDirs] hold, or null for a project outside the modules.
     * @param modules every module of the build, each a map with the keys `name` (a String), `rulesFile`
     *   (a File, or absent), `slices` and `defaultRules` (lists of String, or absent); JDK types only, so
     *   a build tool can hand them across a class loader boundary.
     */
    @JvmStatic
    fun run(
        rulesFile: File?,
        classDirs: List<File>,
        reportFile: File?,
        scriptCacheDir: File?,
        basePackage: String?,
        slices: List<String>?,
        defaultRules: List<String>?,
        rootDir: File?,
        module: String?,
        modules: List<Map<String, Any?>>?,
        out: Consumer<String>,
    ): Int {
        val configurations = modules.orEmpty().map { spec ->
            @Suppress("UNCHECKED_CAST")
            ModuleConfiguration(
                name = spec["name"] as? String ?: throw ConfigException("a module has no name"),
                rulesFile = spec["rulesFile"] as? File,
                slices = (spec["slices"] as? List<String>).orEmpty(),
                defaultRules = (spec["defaultRules"] as? List<String>).orEmpty(),
            )
        }
        val loaded = Byterails.load(rulesFile, scriptCacheDir, basePackage, slices, defaultRules, configurations, module?.takeIf { it.isNotBlank() }, rootDir)
        val result = Checker(loaded.ruleSet, loaded.warnings).check(ClassDirScanner.scan(classDirs))
        ConsoleReporter.render(result).forEach(out::accept)
        if (reportFile != null) {
            reportFile.parentFile?.mkdirs()
            reportFile.writeText(JsonReporter.render(result))
        }
        return result.violations.size
    }
}
