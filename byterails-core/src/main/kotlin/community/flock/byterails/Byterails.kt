package community.flock.byterails

import community.flock.byterails.analysis.ClassDirScanner
import community.flock.byterails.check.CheckResult
import community.flock.byterails.check.Checker
import community.flock.byterails.model.ConfigException
import community.flock.byterails.model.ConfigPhase
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
import community.flock.byterails.report.VerboseReporter
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
        fun displayName(file: File) = displayName(file, rootDir)
        val defaults = defaultRules.orEmpty().filter { it.isNotBlank() }
        val fromFile = when {
            rulesFile != null && rulesFile.isFile -> ScriptLoader.load(rulesFile, scriptCacheDir, displayName(rulesFile))
            defaults.isNotEmpty() || modules.isNotEmpty() -> RuleSet(emptyList(), emptyList())
            rulesFile == null -> throw ConfigException("no rules file is configured and no default rules are set; add a byterails.kts or set defaultRules", phase = ConfigPhase.SETTINGS)
            else -> throw ConfigException("the rules file ${rulesFile.path} does not exist", phase = ConfigPhase.SETTINGS)
        }
        val sliced = slices.orEmpty().any { it.isNotBlank() }
        val moduleRules = modules.map { configuration ->
            val file = configuration.rulesFile?.takeIf { it.isFile }
            val own = file?.let { ScriptLoader.load(it, scriptCacheDir, displayName(it)) } ?: RuleSet(emptyList(), emptyList())
            val moduleSlices = configuration.slices.filter { it.isNotBlank() }
            ModuleRules(configuration.name, own.withDefaultRules(configuration.defaultRules, moduleSlices.isNotEmpty()), moduleSlices)
        }
        val ruleSet = fromFile.withDefaultRules(defaults, sliced).withSlices(slices).withModules(moduleRules, module).withBasePackage(basePackage)
        // Every problem of every file in one run, each with the line it is on.
        val files = (listOfNotNull(rulesFile?.takeIf { it.isFile }) + modules.mapNotNull { it.rulesFile?.takeIf { file -> file.isFile } })
            .associateBy { displayName(it) }
        val problems = ScriptLoader.attachSource(ruleSet.problems + RuleSetValidator.validate(ruleSet), files)
        val errors = problems.filter { it.severity == Severity.ERROR }
        if (errors.isNotEmpty()) throw ConfigException(errors)
        return Loaded(ruleSet, problems)
    }

    /** Validates a rule set, together with what the DSL found wrong while building it, and returns its warnings. */
    fun validate(ruleSet: RuleSet): List<ConfigProblem> {
        val problems = ruleSet.problems + RuleSetValidator.validate(ruleSet)
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

    /**
     * How a rules file is named in locations and messages: its path relative to the root of the build
     * when it lies under it, so a build with several files tells them apart, else its bare name.
     */
    fun displayName(file: File, rootDir: File?): String {
        val root = rootDir?.absoluteFile?.normalize() ?: return file.name
        val relative = file.absoluteFile.normalize().relativeToOrNull(root)?.invariantSeparatorsPath
        return if (relative == null || relative.startsWith("..")) file.name else relative
    }
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

    /** The option that names how the running tool turns verbose output on, for the lines that say what was left out. */
    const val VERBOSE_SWITCH = "verboseSwitch"

    /** The option that says whether the detail consumer reaches the user; informational. */
    const val VERBOSE = "verbose"

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
    ): Int = run(rulesFile, classDirs, reportFile, scriptCacheDir, basePackage, slices, defaultRules, rootDir, module, modules, emptyMap(), out) { }

    /**
     * The entry point with the verbose stream.
     *
     * @param options [VERBOSE_SWITCH], how the running tool turns verbose output on (`--verbose`,
     *   `-Pbyterails.verbose=true`, `-Dbyterails.verbose=true`), and [VERBOSE], whether [detail] is shown.
     * @param out the lines every run prints: the grouped violations and the summary.
     * @param detail the verbose lines: the run's settings, every reference on one line with the names as
     *   the class file spells them, and the rules in effect for every package with a violation. The build
     *   tool decides whether the developer sees them.
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
        options: Map<String, Any?>,
        out: Consumer<String>,
        detail: Consumer<String>,
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
        val currentModule = module?.takeIf { it.isNotBlank() }
        val loaded = Byterails.load(rulesFile, scriptCacheDir, basePackage, slices, defaultRules, configurations, currentModule, rootDir)
        describeRun(rulesFile, classDirs, basePackage, slices, defaultRules, rootDir, currentModule, configurations).forEach(detail::accept)
        val result = Checker(loaded.ruleSet, loaded.warnings).check(ClassDirScanner.scan(classDirs))
        VerboseReporter.render(result).forEach(detail::accept)
        ConsoleReporter.render(result, options[VERBOSE_SWITCH] as? String ?: "--verbose").forEach(out::accept)
        if (reportFile != null) {
            reportFile.parentFile?.mkdirs()
            reportFile.writeText(JsonReporter.render(result))
        }
        return result.violations.size
    }

    /** The settings of the run, as three lines of the verbose output. */
    private fun describeRun(
        rulesFile: File?,
        classDirs: List<File>,
        basePackage: String?,
        slices: List<String>?,
        defaultRules: List<String>?,
        rootDir: File?,
        module: String?,
        modules: List<ModuleConfiguration>,
    ): List<String> {
        val files = listOfNotNull(rulesFile?.takeIf { it.isFile }) + modules.mapNotNull { it.rulesFile?.takeIf { file -> file.isFile } }
        val defaults = defaultRules.orEmpty().filter { it.isNotBlank() }
        val rules = listOfNotNull(
            files.takeIf { it.isNotEmpty() }?.joinToString(", ") { Byterails.displayName(it, rootDir) } ?: "no rules file",
            defaults.takeIf { it.isNotEmpty() }?.let { "default rules ${it.joinToString(", ")}" },
        )
        val build = listOfNotNull(
            basePackage?.takeIf { it.isNotBlank() }?.let { "base package $it" },
            slices.orEmpty().filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { "slices ${it.joinToString(", ")}" },
            module?.let { "module $it" },
            modules.takeIf { it.isNotEmpty() }?.let { "modules ${it.joinToString(", ") { m -> m.name }}" },
        )
        return listOf(
            "byterails: rules    ${rules.joinToString("; ")}",
            "byterails: build    ${build.takeIf { it.isNotEmpty() }?.joinToString("; ") ?: "no base package, slices or modules"}",
            "byterails: classes  ${classDirs.joinToString(", ") { it.path }}",
        )
    }
}
