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
            for (file in files) yield(ClassFileAnalyzer.analyze(file.readBytes()))
        }
    }
}
