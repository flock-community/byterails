package community.flock.byterails.model

/**
 * A dot-separated name prefix that matches on segment boundaries only.
 *
 * `com.acme.domain` covers `com.acme.domain` and `com.acme.domain.model`, and does not cover
 * `com.acme.domainservice`. A prefix may name a package or a class: `java.lang.System` covers
 * the class `java.lang.System` and any of its nested classes, and nothing else.
 */
class Prefix private constructor(val segments: List<String>) : Comparable<Prefix> {

    val name: String get() = segments.joinToString(".")

    val depth: Int get() = segments.size

    /** True when [other] equals this prefix or lies beneath it. */
    fun covers(other: Prefix): Boolean = covers(other.segments)

    fun covers(className: ClassName): Boolean = covers(className.segments)

    fun coversPackage(packageName: String): Boolean = covers(packageSegments(packageName))

    fun covers(segments: List<String>): Boolean {
        if (segments.size < this.segments.size) return false
        for (i in this.segments.indices) {
            if (this.segments[i] != segments[i]) return false
        }
        return true
    }

    override fun compareTo(other: Prefix): Int = name.compareTo(other.name)

    override fun equals(other: Any?): Boolean = other is Prefix && other.segments == segments

    override fun hashCode(): Int = segments.hashCode()

    override fun toString(): String = name

    companion object {
        /**
         * Parses [text] into a prefix.
         *
         * @throws IllegalArgumentException with a human-readable reason when the text is malformed.
         */
        fun parse(text: String): Prefix {
            val trimmed = text.trim()
            require(trimmed.isNotEmpty()) { "prefix is empty" }
            require(!trimmed.startsWith('.') && !trimmed.endsWith('.')) {
                "prefix \"$text\" must not start or end with a dot"
            }
            val segments = trimmed.split('.').flatMap { segment ->
                require(segment.isNotEmpty()) { "prefix \"$text\" contains an empty segment" }
                require(isIdentifier(segment)) { "segment \"$segment\" in prefix \"$text\" is not a valid identifier" }
                segment.split('$')
            }
            return Prefix(segments)
        }

        internal fun packageSegments(packageName: String): List<String> =
            if (packageName.isEmpty()) emptyList() else packageName.split('.')

        private fun isIdentifier(segment: String): Boolean =
            Character.isJavaIdentifierStart(segment[0]) && segment.all { Character.isJavaIdentifierPart(it) }
    }
}
