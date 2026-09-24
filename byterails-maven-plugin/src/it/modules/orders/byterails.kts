byterails {
    exported("api")
    allow("common")
    pkg("api")
    pkg("domain") {
        allow("api")
        exclusive("java.util")
    }
}
