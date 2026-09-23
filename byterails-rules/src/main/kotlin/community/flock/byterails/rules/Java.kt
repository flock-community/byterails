package community.flock.byterails.rules

import community.flock.byterails.dsl.ByterailsBuilder

/**
 * The `java` rule set: the Java standard library, allowed in every package.
 *
 * That is the `java` namespace, the `javax` and `com.sun` packages the JDK itself exports, the `jdk`
 * namespace, and the W3C DOM, SAX and JGSS packages. Not `javax` as a whole, because
 * `javax.persistence`, `javax.inject` and friends are libraries, not the JDK.
 */
object Java : DefaultRuleSet(
    id = "java",
    description = "the Java standard library, allowed in every package",
    allowsLabel = "the Java standard library",
) {
    override fun ByterailsBuilder.rules() {
        STANDARD_LIBRARY.forEach { allow(it) }
    }

    /** The packages JDK 21 exports without qualification, collapsed to the shortest prefixes, minus `jdk.unsupported`. */
    val STANDARD_LIBRARY: List<String> = listOf(
        "java",
        "jdk",
        "javax.accessibility",
        "javax.annotation.processing",
        "javax.crypto",
        "javax.imageio",
        "javax.lang.model",
        "javax.management",
        "javax.naming",
        "javax.net",
        "javax.print",
        "javax.rmi.ssl",
        "javax.script",
        "javax.security.auth",
        "javax.security.cert",
        "javax.security.sasl",
        "javax.smartcardio",
        "javax.sound",
        "javax.sql",
        "javax.swing",
        "javax.tools",
        "javax.transaction.xa",
        "javax.xml",
        "com.sun.java.accessibility.util",
        "com.sun.jdi",
        "com.sun.management",
        "com.sun.net.httpserver",
        "com.sun.nio.sctp",
        "com.sun.security.auth",
        "com.sun.security.jgss",
        "com.sun.source",
        "com.sun.tools.attach",
        "com.sun.tools.javac",
        "com.sun.tools.jconsole",
        "netscape.javascript",
        "org.ietf.jgss",
        "org.w3c.dom",
        "org.xml.sax",
    )
}

/** The `java` default rules: the Java standard library, allowed in every package. */
fun ByterailsBuilder.java() = include(Java.build())
