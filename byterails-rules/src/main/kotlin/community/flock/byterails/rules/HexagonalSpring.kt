package community.flock.byterails.rules

import community.flock.byterails.dsl.ByterailsBuilder
import community.flock.byterails.dsl.NamingBuilder

/**
 * The `hexagonalSpring` rule set: the vertically sliced hexagonal layout of a Spring Boot service, in
 * whitelist form.
 *
 * Under the base package only the application class and the `config` package may exist. Every slice
 * has an isolated `domain` split into `model`, `ports` and `services`, an `application` layer, and
 * `adapters.inbound` and `adapters.outbound` with a fixed place for controllers and for database
 * code. Dependencies point inwards, `@Configuration` lives in `config`, the Spring web annotations
 * live in `controllers`, and the controllers may use only the Spring packages a thin HTTP layer
 * needs. The domain allows the standard libraries and Spring's stereotypes. Everything else inherits
 * the root block and the slice root, so a rules file widens a layer with a root or slice-root allow
 * and refines one with a sub-package declaration.
 */
object HexagonalSpring : DefaultRuleSet(
    id = "hexagonalSpring",
    description = "the hexagonal Spring Boot layout of a sliced service: an isolated domain, an application layer, inbound and outbound adapters, and a config package",
    allowsLabel = "the layout's grant for this package; see the hexagonalSpring section of the README for what each package may use",
) {
    override fun ByterailsBuilder.rules() {
        // The base package holds the application class and nothing else, so it is flat.
        basePackage {
            flat()
            allowAnything()
            naming { fileSuffix("Application") }
        }

        pkg("config") {
            allowAnything()
            exclusive(SPRING_CONFIGURATION)
            naming { fileSuffix("Config") }
        }

        slice {
            pkg("domain.model") {
                isolated()
                flat()
                DOMAIN_BASELINE.forEach { allow(it) }
            }

            pkg("domain.ports") {
                isolated()
                flat()
                DOMAIN_BASELINE.forEach { allow(it) }
                allow("domain.model")
                naming { fileSuffix("Port") }
            }

            pkg("domain.services") {
                isolated()
                flat()
                DOMAIN_BASELINE.forEach { allow(it) }
                allow("domain.model")
                allow("domain.ports")
                naming { fileSuffix("Service") }
            }

            pkg("application") {
                allow("domain.model")
                allow("domain.ports")
                allow("domain.services")
                allow(SPRING_STEREOTYPE)
                deny("adapters.outbound")
            }

            pkg("adapters.inbound") {
                allow("domain.model")
                allow("domain.services")
                allow("application")
                deny("domain.ports")
                deny("adapters.outbound")
            }

            pkg("adapters.inbound.controllers") {
                CONTROLLER_SPRING.forEach { allow(it) }
                exclusive(SPRING_WEB_BIND)
            }

            pkg("adapters.inbound.controllers.error") {
                CONTROLLER_ERROR_SPRING.forEach { allow(it) }
            }

            pkg("adapters.outbound") {
                allow("domain.model")
                allow("domain.ports")
                allow(SPRING_STEREOTYPE)
                deny("domain.services")
                deny("adapters.inbound")
            }

            pkg("adapters.outbound.database") {
                flat()
                DATABASE_LIBRARIES.forEach { allow(it) }
                allow("adapters.outbound.database.model")
                allow("adapters.outbound.database.mappers")
            }

            pkg("adapters.outbound.database.mappers") {
                flat()
                allow("adapters.outbound.database.model")
            }

            pkg("adapters.outbound.database.model") {
                flat()
                DATABASE_LIBRARIES.forEach { allow(it) }
            }
        }
    }

    /**
     * A file-name convention as a class-name one: Kotlin compiles the top-level functions of
     * `OrderPort.kt` into `OrderPortKt`, so both spellings pass.
     */
    private fun NamingBuilder.fileSuffix(suffix: String) {
        endsWith(suffix)
        endsWith(suffix + "Kt")
    }

    /** What the domain may use: the standard libraries and Spring's stereotype annotations. */
    val DOMAIN_BASELINE: List<String> = listOf("kotlin", "org.jetbrains.annotations", "java", SPRING_STEREOTYPE)

    /** The Spring packages a thin controller needs: HTTP types, method security, validation and multipart. */
    val CONTROLLER_SPRING: List<String> = listOf(
        "org.springframework.http",
        "org.springframework.security.access.prepost",
        "org.springframework.validation.annotation",
        "org.springframework.web.multipart",
    )

    /** What an error handler under controllers may use on top: data-access exceptions, security, stereotypes, validation and the whole of Spring web. */
    val CONTROLLER_ERROR_SPRING: List<String> = listOf(
        "org.springframework.dao",
        "org.springframework.security.access",
        SPRING_STEREOTYPE,
        "org.springframework.validation",
        "org.springframework.web",
    )

    /** The persistence libraries confined to `adapters.outbound.database`. */
    val DATABASE_LIBRARIES: List<String> = listOf(
        "org.springframework.data",
        "jakarta.persistence",
        "javax.persistence",
        "org.jooq",
        "com.mongodb",
        "io.r2dbc",
        "org.jetbrains.exposed",
    )

    const val SPRING_STEREOTYPE = "org.springframework.stereotype"
    const val SPRING_CONFIGURATION = "org.springframework.context.annotation.Configuration"
    const val SPRING_WEB_BIND = "org.springframework.web.bind.annotation"
}

/**
 * The `hexagonalSpring` default rules: the application class and `config` under the base package, and
 * the hexagonal Spring Boot layout in every slice when the file has a `slice { }` block, otherwise
 * under the base package.
 */
fun ByterailsBuilder.hexagonalSpring() = include(HexagonalSpring.build())
