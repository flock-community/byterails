package community.flock.byterails.report

/** Renders JVM descriptors as simple names: `(Lcom/acme/Order;I[Ljava/lang/String;)V` becomes `Order, int, String[]` and `void`. */
internal object Descriptors {

    /** The argument types and the return type of a method descriptor. */
    fun parseMethod(descriptor: String): Pair<List<String>, String> {
        require(descriptor.startsWith("(")) { "not a method descriptor: $descriptor" }
        val arguments = mutableListOf<String>()
        var index = 1
        while (descriptor[index] != ')') {
            val (type, next) = parseType(descriptor, index)
            arguments += type
            index = next
        }
        return arguments to parseType(descriptor, index + 1).first
    }

    /** The simple name of the type starting at [start], and the index just after it. */
    fun parseType(descriptor: String, start: Int): Pair<String, Int> {
        var index = start
        var dimensions = 0
        while (descriptor[index] == '[') {
            dimensions++
            index++
        }
        val (base, next) = when (val c = descriptor[index]) {
            'L' -> {
                val end = descriptor.indexOf(';', index)
                descriptor.substring(index + 1, end).substringAfterLast('/') to end + 1
            }
            else -> PRIMITIVES.getValue(c) to index + 1
        }
        return base + "[]".repeat(dimensions) to next
    }

    private val PRIMITIVES = mapOf(
        'V' to "void", 'Z' to "boolean", 'B' to "byte", 'C' to "char", 'S' to "short",
        'I' to "int", 'J' to "long", 'F' to "float", 'D' to "double",
    )
}
