package community.flock.byterails.model

/**
 * A JVM class name in both spellings, with the segments that prefix matching works on.
 *
 * For `com/acme/Foo$Bar` the segments are `com, acme, Foo, Bar`, so the prefix `com.acme.Foo`
 * covers the nested class and `com.acme` covers both.
 */
class ClassName(val internalName: String) {

    /** Dotted name, `com.acme.Foo$Bar`. */
    val name: String = internalName.replace('/', '.')

    /** `com.acme`, or an empty string for the default package. */
    val packageName: String = name.substringBeforeLast('.', "")

    /** `Foo$Bar`: the name after the last dot, nested classes included. */
    val simpleName: String = name.substringAfterLast('.')

    /** `Foo`: the top-level class this class lives in. */
    val outermostSimpleName: String = simpleName.substringBefore('$')

    val isNested: Boolean = simpleName.contains('$')

    val segments: List<String> = Prefix.packageSegments(packageName) + simpleName.split('$')

    override fun equals(other: Any?): Boolean = other is ClassName && other.name == name

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = name

    companion object {
        fun fromDotted(dotted: String): ClassName = ClassName(dotted.replace('.', '/'))
    }
}
