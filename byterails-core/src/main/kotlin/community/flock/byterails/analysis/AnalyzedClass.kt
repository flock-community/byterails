package community.flock.byterails.analysis

import community.flock.byterails.model.ClassName
import org.objectweb.asm.Opcodes

/** Where in a class a reference was found. */
sealed interface Site {
    data object ClassHeader : Site

    data class Field(val name: String) : Site

    data class Method(val name: String, val descriptor: String) : Site
}

/** One class name written into a class file, and where. */
data class Reference(val target: ClassName, val site: Site, val line: Int?)

/** Everything the checker needs to know about one class file. */
data class AnalyzedClass(
    val name: ClassName,
    val sourceFile: String?,
    val access: Int,
    val annotations: List<ClassName>,
    val references: List<Reference>,
) {
    val isSynthetic: Boolean get() = access and Opcodes.ACC_SYNTHETIC != 0

    /** True when any annotation on the class has the simple name `Generated`, whatever its package. */
    val isGenerated: Boolean get() = annotations.any { it.simpleName.substringAfterLast('$') == "Generated" }
}
