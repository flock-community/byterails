import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

description = "byterails core: rule model, byterails.kts loader, bytecode analysis and reporting"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Compiled Kotlin and Java snippets that the tests analyse. They are never shipped.
val fixtures: SourceSet by sourceSets.creating

dependencies {
    implementation(libs.asm)
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
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

val byterailsSelfCheck = tasks.register<JavaExec>("byterailsSelfCheck") {
    description = "Checks byterails-core against the repository's own byterails.kts"
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("community.flock.byterails.cli.Main")
    val rules = rootProject.layout.projectDirectory.file("byterails.kts").asFile
    val classes: FileCollection = sourceSets.main.get().output.classesDirs
    val report = layout.buildDirectory.file("reports/byterails/self-check.json").get().asFile
    val cache = layout.buildDirectory.dir("byterails/script-cache").get().asFile
    inputs.file(rules).withPropertyName("rules").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(classes).withPropertyName("classes").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(report)
    argumentProviders.add(SelfCheckArguments(rules, classes, report, cache))
}

class SelfCheckArguments(
    private val rules: File,
    private val classes: FileCollection,
    private val report: File,
    private val cache: File,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("--rules", rules.path, "--classes", classes.asPath, "--report", report.path, "--cache", cache.path)
}

tasks.check {
    dependsOn(byterailsSelfCheck)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
