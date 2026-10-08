package community.flock.byterails.analysis

import java.io.File

/** Walks class directories and analyses every class file in them, skipping module descriptors. */
object ClassDirScanner {

    fun scan(classDirs: Iterable<File>): Sequence<AnalyzedClass> = sequence {
        for (dir in classDirs) {
            if (!dir.isDirectory) continue
            val files = dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") && it.name != "module-info.class" }
                .sortedBy { it.relativeTo(dir).path }
            for (file in files) yield(analyze(file))
        }
    }

    private fun analyze(file: File): AnalyzedClass =
        try {
            ClassFileAnalyzer.analyze(file.readBytes())
        } catch (e: Exception) {
            throw UnreadableClassFile(file, e)
        }
}

/** A class file byterails could not read: written for a JDK newer than this release knows, or not a class file at all. */
class UnreadableClassFile(val file: File, cause: Throwable) : RuntimeException(describe(file, cause), cause) {

    companion object {
        private val UNSUPPORTED_VERSION = Regex("Unsupported class file major version (\\d+)")

        /** The class file version javac writes is the JDK's feature version plus 44: 52 is JDK 8, 65 is JDK 21. */
        private const val VERSION_OFFSET = 44

        private fun describe(file: File, cause: Throwable): String {
            val major = cause.message?.let { UNSUPPORTED_VERSION.find(it) }?.groupValues?.get(1)?.toIntOrNull()
            return when {
                major != null && major in 45..99 ->
                    "class file ${file.path} was compiled for JDK ${major - VERSION_OFFSET} (class file version $major), which this release of byterails cannot read; upgrade byterails"
                major != null -> "class file ${file.path} is not a valid class file (class file version $major)"
                else -> "cannot read class file ${file.path}: ${cause.message ?: cause.javaClass.simpleName}"
            }
        }
    }
}
