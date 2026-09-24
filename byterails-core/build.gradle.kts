import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

description = "byterails core: byterails.kts loader, bytecode analysis, checking and reporting"

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

// Compiled Kotlin and Java snippets that the tests analyse. They are never shipped.
val fixtures: SourceSet by sourceSets.creating

// The fixture classes for the tests of other modules, a directory per language.
val fixtureClasses: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
fixtures.output.classesDirs.forEach { dir ->
    artifacts.add(fixtureClasses.name, dir) { builtBy(fixtures.output) }
}

dependencies {
    api(project(":byterails-dsl"))
    implementation(libs.asm)
    implementation(libs.kotlin.scripting.jvm.host)

    "fixturesImplementation"(kotlin("stdlib"))

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    val fixtureClassDirs: FileCollection = fixtures.output.classesDirs
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
