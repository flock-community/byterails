package community.flock.byterails.cli

import community.flock.byterails.ByterailsRunner
import community.flock.byterails.model.ConfigException
import java.io.File
import kotlin.system.exitProcess

/**
 * `java -cp ... community.flock.byterails.cli.Main --rules byterails.kts --classes build/classes/kotlin/main [--classes ...]
 * [--report build/byterails.json] [--cache build/byterails-cache] [--report-only]`
 *
 * Exit status: 0 clean, 1 violations, 2 configuration or usage error.
 */
object Main {

    @JvmStatic
    fun main(args: Array<String>) {
        var rules: File? = null
        val classes = mutableListOf<File>()
        var report: File? = null
        var cache: File? = null
        var reportOnly = false
        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--rules" -> rules = File(args.getOrNull(++i) ?: usage("--rules needs a file"))
                "--classes" -> classes += (args.getOrNull(++i) ?: usage("--classes needs a directory")).split(File.pathSeparator).map(::File)
                "--report" -> report = File(args.getOrNull(++i) ?: usage("--report needs a file"))
                "--cache" -> cache = File(args.getOrNull(++i) ?: usage("--cache needs a directory"))
                "--report-only" -> reportOnly = true
                "--help", "-h" -> usage(null)
                else -> usage("unknown argument $arg")
            }
            i++
        }
        if (rules == null) usage("--rules is required")
        if (classes.isEmpty()) usage("--classes is required")
        val count = try {
            ByterailsRunner.run(rules, classes, report, cache) { println(it) }
        } catch (e: ConfigException) {
            System.err.println(e.message)
            exitProcess(2)
        }
        exitProcess(if (count > 0 && !reportOnly) 1 else 0)
    }

    private fun usage(problem: String?): Nothing {
        if (problem != null) System.err.println("byterails: $problem")
        System.err.println(
            "usage: byterails --rules byterails.kts --classes <dir>[:<dir>...] [--report <file>] [--cache <dir>] [--report-only]",
        )
        exitProcess(2)
    }
}
