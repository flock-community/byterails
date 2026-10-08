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
    /** The class this one was written inside, from the EnclosingMethod attribute: set for a lambda, an anonymous or a local class. */
    val enclosingClass: ClassName? = null,
    /** The method this class was written inside, when the EnclosingMethod attribute names one. */
    val enclosingMethod: String? = null,
    /** The `k` of the `kotlin.Metadata` annotation, or null for a class the Kotlin compiler did not write. */
    val kotlinKind: Int? = null,
    /** The components of a Java record, name to descriptor, in declaration order. */
    val recordComponents: Map<String, String> = emptyMap(),
    val fieldNames: Set<String> = emptySet(),
    /** The members the compiler flagged synthetic or bridge. */
    val syntheticMembers: Set<Site> = emptySet(),
) {
    val isSynthetic: Boolean get() = access and Opcodes.ACC_SYNTHETIC != 0

    val isRecord: Boolean get() = access and Opcodes.ACC_RECORD != 0

    val isKotlin: Boolean get() = kotlinKind != null

    /** True when any annotation on the class has the simple name `Generated`, whatever its package. */
    val isGenerated: Boolean get() = annotations.any { it.simpleName.substringAfterLast('$') == "Generated" }

    /**
     * True for a class the compiler named rather than the developer: a lambda or suspend lambda, an anonymous or
     * local class, or a helper class Kotlin emits. Such a class is reported under the class it was written in.
     */
    val isCompilerNamed: Boolean get() =
        isSynthetic || kotlinKind == KOTLIN_SYNTHETIC_CLASS || enclosingClass != null ||
            name.simpleName.split('$').drop(1).any { segment -> segment.isNotEmpty() && segment.all(Char::isDigit) }

    companion object {
        /** `kotlin.Metadata.k` of a class the Kotlin compiler generated: a lambda, a `$WhenMappings`, a SAM wrapper. */
        const val KOTLIN_SYNTHETIC_CLASS = 3
    }
}
