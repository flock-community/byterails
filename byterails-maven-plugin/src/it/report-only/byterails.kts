byterails {
    allow("java.lang")
    pkg("domain")
    pkg("infra") {
        allow("domain")
        exclusive("java.util")
    }
}
