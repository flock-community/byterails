package community.flock.byterails.rules

import community.flock.byterails.dsl.ByterailsBuilder

/**
 * The `kotlin` rule set: the Kotlin standard library, allowed in every package.
 *
 * That is `kotlin`, the nullability annotations the compiler writes into every class, and the Java
 * standard library, because Kotlin's collections, strings and boxed numbers compile to `java.util`
 * and `java.lang`.
 */
object Kotlin : DefaultRuleSet(
    id = "kotlin",
    description = "the Kotlin standard library and the Java one it compiles to, allowed in every package",
    allowsLabel = "the Kotlin and Java standard libraries",
) {
    override fun ByterailsBuilder.rules() {
        allow("kotlin")
        allow("org.jetbrains.annotations")
        Java.STANDARD_LIBRARY.forEach { allow(it) }
    }
}

/** The `kotlin` default rules: the Kotlin standard library and the Java one it compiles to, allowed in every package. */
fun ByterailsBuilder.kotlin() = include(Kotlin.build())
