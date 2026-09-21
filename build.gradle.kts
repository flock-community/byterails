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
