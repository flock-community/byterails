package community.flock.byterails.rules

import java.io.File

/** The compiled fixture classes of the core, handed to the tests by the build through a system property. */
object Fixtures {

    val classDirs: List<File> = System.getProperty("byterails.fixtures")
        ?.split(File.pathSeparator)
        ?.map(::File)
        ?.filter { it.isDirectory }
        ?: error("system property byterails.fixtures is not set; run the tests through Gradle")
}
