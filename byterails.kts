// The rules byterails-dsl, byterails-core and byterails-rules are checked against on every build (task byterailsSelfCheck).
byterails {
    // Every Kotlin class references these; nothing else is allowed unless a package says so.
    allow("kotlin")
    allow("java.lang")
    allow("java.util")
    allow("java.io")
    allow("java.nio.charset") // Kotlin's writeText and toByteArray inline their UTF-8 default
    allow("org.jetbrains.annotations")

    // The facade in the root package may reach every layer; its sub-packages may not reach each other freely.
    pkg("community.flock.byterails")

    pkg("community.flock.byterails.model")

    pkg("community.flock.byterails.dsl") {
        allow("community.flock.byterails.model")
        naming {
            endsWith("Builder")
            endsWith("Dsl")
            endsWith("Kt")
        }
    }

    // The default rule sets: an API in byterails-dsl, the shipped sets in byterails-rules, both written with the DSL.
    pkg("community.flock.byterails.rules") {
        allow("community.flock.byterails.model")
        allow("community.flock.byterails.dsl")
    }

    pkg("community.flock.byterails.validation") {
        allow("community.flock.byterails.model")
        allow("community.flock.byterails.rules")
        naming {
            endsWith("Validator")
            matches("Tarjan")
        }
    }

    pkg("community.flock.byterails.analysis") {
        allow("community.flock.byterails.model")
        exclusive("org.objectweb.asm")
    }

    pkg("community.flock.byterails.check") {
        allow("community.flock.byterails.model")
        allow("community.flock.byterails.rules")
        allow("community.flock.byterails.analysis")
    }

    pkg("community.flock.byterails.report") {
        allow("community.flock.byterails.model")
        allow("community.flock.byterails.analysis")
        allow("community.flock.byterails.check")
        naming {
            endsWith("Reporter")
            endsWith("Descriptors")
        }
    }

    // The definition in byterails-dsl and the loader in byterails-core.
    pkg("community.flock.byterails.script") {
        allow("community.flock.byterails.model")
        allow("community.flock.byterails.dsl")
        allow("java.security")
        exclusive("kotlin.script")
    }

    pkg("community.flock.byterails.cli") {
        // Class-level prefixes: the CLI sees the runner and the exception type, nothing else of the core.
        allow("community.flock.byterails.ByterailsRunner")
        allow("community.flock.byterails.model.ConfigException")
        naming { endsWith("Main") }
    }
}
