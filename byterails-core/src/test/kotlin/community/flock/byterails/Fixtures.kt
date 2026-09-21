package community.flock.byterails

import community.flock.byterails.analysis.AnalyzedClass
import community.flock.byterails.analysis.ClassFileAnalyzer
import community.flock.byterails.dsl.byterails
import community.flock.byterails.model.RuleSet
import community.flock.byterails.model.withSlices
import java.io.File

/** The compiled fixture classes, handed to the tests by the build through a system property. */
object Fixtures {

    val classDirs: List<File> = System.getProperty("byterails.fixtures")
        ?.split(File.pathSeparator)
        ?.map(::File)
        ?.filter { it.isDirectory }
        ?: error("system property byterails.fixtures is not set; run the tests through Gradle")

    fun analyze(dottedName: String): AnalyzedClass {
        val relative = dottedName.replace('.', '/') + ".class"
        val file = classDirs.map { File(it, relative) }.firstOrNull { it.isFile }
            ?: error("fixture class $dottedName not found under $classDirs")
        return ClassFileAnalyzer.analyze(file.readBytes())
    }

    /** The rules the fixture application is checked against. Mirrors the example in the README. */
    fun rules(): RuleSet = byterails {
        allow("kotlin")
        allow("java.lang")
        allow("java.util")
        allow("org.jetbrains.annotations")

        pkg("fixtures.lib")

        pkg("fixtures.app")

        pkg("fixtures.app.domain") {
            deny("fixtures.lib.persistence")
        }

        pkg("fixtures.app.application") {
            allow("fixtures.app.domain")
            naming { endsWith("UseCase") }
        }

        pkg("fixtures.app.infra.persistence") {
            allow("fixtures.app.domain")
            allow("fixtures.lib.persistence")
            exclusive("fixtures.lib.jooq")
        }

        pkg("fixtures.app.infra.web") {
            allow("fixtures.app.application")
            exclusive("fixtures.lib.web")
            naming {
                endsWith("Controller")
                endsWith("Advice")
            }
        }

        pkg("fixtures.app.javainterop")

        slice {
            exported("api")
            pkg("api")
            pkg("domain")
            pkg("application") {
                allow("domain")
                allow("api")
            }
            pkg("infra") {
                allow("domain")
                exclusive("fixtures.lib.messaging")
            }
        }
    }.withSlices(listOf("fixtures.slices.orders", "fixtures.slices.customers"))
}
