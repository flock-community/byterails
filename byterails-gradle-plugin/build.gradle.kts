import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    `maven-publish`
}

description = "byterails Gradle plugin: checks compiled classes against byterails.kts"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // The plugin runs inside Gradle, which ships its own Kotlin standard library.
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
    }
}

// The tool: the default rules, the core and their runtime dependencies, handed to the functional tests so they need no repository.
val byterailsTool: Configuration by configurations.creating {
    isCanBeConsumed = false
}

val functionalTest: SourceSet by sourceSets.creating

configurations["functionalTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["functionalTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    byterailsTool(project(":byterails-core"))
    byterailsTool(project(":byterails-rules"))

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(gradleTestKit())
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    website.set("https://github.com/flock-community/byterails")
    vcsUrl.set("https://github.com/flock-community/byterails")
    testSourceSets(functionalTest)
    plugins {
        create("byterails") {
            id = "community.flock.byterails"
            implementationClass = "community.flock.byterails.gradle.ByterailsPlugin"
            displayName = "byterails"
            description = "Whitelist-based architecture guardrails checked on bytecode"
            tags.set(listOf("architecture", "guardrails", "bytecode", "kotlin"))
        }
    }
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("META-INF/byterails.properties") {
        expand("version" to pluginVersion)
    }
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs the plugin against real Gradle builds"
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
    val toolClasspath: FileCollection = byterailsTool.incoming.files
    inputs.files(toolClasspath).withPropertyName("byterailsTool").withNormalizer(ClasspathNormalizer::class)
    jvmArgumentProviders.add(ToolClasspathArgument(toolClasspath))
}

/** A named class rather than a lambda, so the configuration cache never sees the build script object. */
class ToolClasspathArgument(private val files: FileCollection) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Dbyterails.toolClasspath=${files.asPath}")
}

tasks.test {
    useJUnitPlatform()
}

tasks.check {
    dependsOn(functionalTestTask)
}
