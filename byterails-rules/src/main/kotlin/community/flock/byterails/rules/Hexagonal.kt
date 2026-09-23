package community.flock.byterails.rules

import community.flock.byterails.dsl.ByterailsBuilder
import community.flock.byterails.dsl.SliceBuilder

/**
 * The `hexagonal` rule set: a `domain` package that cannot have any external dependency.
 *
 * With slices configured there is one in every slice, without there is one under the base package.
 * The package is isolated, so it inherits no allow from the root block or an enclosing declaration,
 * and may reference only the language baseline and itself.
 */
object Hexagonal : DefaultRuleSet(
    id = "hexagonal",
    description = "a domain package without external dependencies, in every slice",
    allowsLabel = "the language baseline: ${LANGUAGE_BASELINE.joinToString(", ")}",
) {
    override fun ByterailsBuilder.rules() {
        slice {
            pkg("domain") {
                isolated()
                LANGUAGE_BASELINE.forEach { allow(it) }
            }
        }
    }
}

/**
 * What a class needs from the JDK and the Kotlin runtime to exist at all, plus the value types a
 * domain model is made of. Nothing here talks to the outside world.
 */
val LANGUAGE_BASELINE: List<String> = listOf(
    "kotlin",
    "org.jetbrains.annotations",
    "java.lang",
    "java.util",
    "java.time",
    "java.math",
    "java.text",
)

/**
 * The `hexagonal` default rules: a `domain` package without external dependencies, in every slice
 * when the file has a `slice { }` block and under the base package otherwise.
 */
fun ByterailsBuilder.hexagonal() = include(Hexagonal.build())

/** The `hexagonal` default rules: a `domain` package in every slice without external dependencies. */
fun SliceBuilder.hexagonal() = include(Hexagonal.build())
