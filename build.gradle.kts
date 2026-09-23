import org.gradle.plugins.signing.Sign

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

// Releases go through the Central Portal's OSSRH staging API; snapshots straight to the snapshot repository.
// Credentials are a Central Portal user token, the same one kmapper uses.
nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username.set(System.getenv("SONATYPE_USERNAME"))
            password.set(System.getenv("SONATYPE_PASSWORD"))
        }
    }
}

// Every published module gets the POM metadata Maven Central requires and is signed when the key is present.
subprojects {
    plugins.withId("maven-publish") {
        apply(plugin = "signing")

        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(project.name)
                    description.set(project.provider { project.description ?: "byterails: whitelist-based architecture guardrails checked on bytecode" })
                    url.set("https://github.com/flock-community/byterails")
                    licenses {
                        license {
                            name.set("Apache License 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }
                    developers {
                        developer {
                            id.set("flock-community")
                            name.set("Flock Community")
                            email.set("info@flock.community")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/flock-community/byterails.git")
                        developerConnection.set("scm:git:ssh://github.com:flock-community/byterails.git")
                        url.set("https://github.com/flock-community/byterails")
                    }
                }
            }
        }

        extensions.configure<SigningExtension> {
            val signingKey = System.getenv("GPG_PRIVATE_KEY")
            val signingPassword = System.getenv("GPG_PASSPHRASE")
            if (signingKey != null && signingPassword != null) {
                useInMemoryPgpKeys(signingKey, signingPassword)
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }

        // Gradle does not order signing before publishing on its own when several publications sign each other's artifacts.
        tasks.withType<PublishToMavenRepository>().configureEach {
            dependsOn(tasks.withType<Sign>())
        }
        tasks.withType<PublishToMavenLocal>().configureEach {
            dependsOn(tasks.withType<Sign>())
        }
    }
}

// The modules that make up the tool are checked against the repository's own byterails.kts on every
// build. The core alone loads the file without the default rules on the classpath, which is how a
// library user without byterails-rules sees it; the rules module is checked with both.
val selfChecked = setOf("byterails-core", "byterails-rules")

subprojects {
    if (name !in selfChecked) return@subprojects
    plugins.withId("org.jetbrains.kotlin.jvm") {
        val main = extensions.getByType<SourceSetContainer>().named("main")
        val selfCheck = tasks.register<JavaExec>("byterailsSelfCheck") {
            description = "Checks ${project.name} against the repository's own byterails.kts"
            group = "verification"
            classpath = main.get().runtimeClasspath
            mainClass.set("community.flock.byterails.cli.Main")
            val rules = rootProject.layout.projectDirectory.file("byterails.kts").asFile
            val classes: FileCollection = main.get().output.classesDirs
            val report = layout.buildDirectory.file("reports/byterails/self-check.json").get().asFile
            val cache = layout.buildDirectory.dir("byterails/script-cache").get().asFile
            inputs.file(rules).withPropertyName("rules").withPathSensitivity(PathSensitivity.NONE)
            inputs.files(classes).withPropertyName("classes").withPathSensitivity(PathSensitivity.RELATIVE)
            outputs.file(report)
            argumentProviders.add(SelfCheckArguments(rules, classes, report, cache))
        }
        tasks.named("check") { dependsOn(selfCheck) }
    }
}

/** A named class rather than a lambda, so the configuration cache never sees the build script object. */
class SelfCheckArguments(
    private val rules: File,
    private val classes: FileCollection,
    private val report: File,
    private val cache: File,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("--rules", rules.path, "--classes", classes.asPath, "--report", report.path, "--cache", cache.path)
}
