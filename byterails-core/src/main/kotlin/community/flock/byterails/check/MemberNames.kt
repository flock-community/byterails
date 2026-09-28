package community.flock.byterails.check

import community.flock.byterails.analysis.AnalyzedClass
import community.flock.byterails.analysis.Site
import community.flock.byterails.model.ClassName

/**
 * Names the member a developer wrote for a reference site, and folds away what the compiler generated:
 * lambda bodies go to the method they were written in, accessors to their property, and the members
 * of a record or a data class that only repeat a component are dropped.
 */
internal object MemberNames {

    /** The class a violation in [cls] is reported under: itself, or the class a lambda or anonymous class was written in. */
    fun owner(cls: AnalyzedClass): ClassName {
        if (!cls.isCompilerNamed) return cls.name
        cls.enclosingClass?.let { return it }
        val outer = cls.name.outermostSimpleName
        return ClassName.fromDotted(if (cls.name.packageName.isEmpty()) outer else "${cls.name.packageName}.$outer")
    }

    /** The member of [cls] a developer recognises [site] by, or null when the compiler generated the member. */
    fun member(cls: AnalyzedClass, site: Site): String? {
        if (cls.isCompilerNamed) {
            // Everything in a lambda or anonymous class belongs to the method it was written in.
            return cls.enclosingMethod?.let(::methodName) ?: if (cls.enclosingClass != null) "an initializer" else null
        }
        return when (site) {
            Site.ClassHeader -> "the class declaration"
            is Site.Field -> if (site in cls.syntheticMembers || site.name.startsWith("$")) null else site.name
            is Site.Method -> method(cls, site)
        }
    }

    private fun method(cls: AnalyzedClass, site: Site.Method): String? {
        val name = site.name
        KOTLIN_LAMBDA.matchEntire(name)?.let { return it.groupValues[1] }
        JAVA_LAMBDA.matchEntire(name)?.let { return it.groupValues[1] }
        if (site in cls.syntheticMembers || name.startsWith("access$") || name.contains("\$default")) return null
        if (name == "<clinit>") return "the static initializer"
        if (name == "<init>") return if (cls.isRecord && site.descriptor == canonicalConstructor(cls)) null else "the constructor"
        if (cls.isRecord && (name in DATA_METHODS || (name in cls.recordComponents && site.descriptor.startsWith("()")))) return null
        if (cls.isKotlin) {
            if (name in DATA_METHODS || name == "copy" || COMPONENT.matches(name)) return null
            val property = accessedProperty(name)
            if (property != null && property in cls.fieldNames) return null
        }
        return name
    }

    private fun methodName(name: String): String = when (name) {
        "<init>" -> "the constructor"
        "<clinit>" -> "the static initializer"
        else -> name
    }

    private fun canonicalConstructor(cls: AnalyzedClass): String = "(${cls.recordComponents.values.joinToString("")})V"

    /** The property behind a Kotlin accessor name: `getBus` and `setBus` are `bus`, `isActive` is itself. */
    private fun accessedProperty(name: String): String? = when {
        name.length > 3 && (name.startsWith("get") || name.startsWith("set")) && name[3].isUpperCase() ->
            name.substring(3).replaceFirstChar { it.lowercase() }
        name.length > 2 && name.startsWith("is") && name[2].isUpperCase() -> name
        else -> null
    }

    /** Kotlin names the body of a lambda in `place` `place$lambda$0`; javac names it `lambda$place$0`. */
    private val KOTLIN_LAMBDA = Regex("(.+?)\\\$lambda(\\\$\\d+)+")
    private val JAVA_LAMBDA = Regex("lambda\\\$(.+?)\\\$\\d+")
    private val COMPONENT = Regex("component\\d+")
    private val DATA_METHODS = setOf("equals", "hashCode", "toString")
}
