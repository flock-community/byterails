import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

description = "byterails default rules: the java, kotlin, hexagonal and hexagonalSpring rule sets, written with the byterails DSL"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// The compiled fixture classes of the core, which the tests here analyse as well.
val fixtureClasses: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    api(project(":byterails-core"))

    fixtureClasses(project(mapOf("path" to ":byterails-core", "configuration" to "fixtureClasses")))

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    val fixtureClassDirs: FileCollection = fixtureClasses.incoming.files
    inputs.files(fixtureClassDirs).withPropertyName("fixtureClassDirs").withPathSensitivity(PathSensitivity.RELATIVE)
    jvmArgumentProviders.add(FixturesArgument(fixtureClassDirs))
}

/** A named class rather than a lambda, so the configuration cache never sees the build script object. */
class FixturesArgument(private val dirs: FileCollection) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Dbyterails.fixtures=${dirs.asPath}")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
