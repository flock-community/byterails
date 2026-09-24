import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

description = "byterails DSL: the rule model, the byterails { } DSL, the default rule set API and the byterails.kts script definition"

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

dependencies {
    // The script definition: the annotation and the compilation configuration an IDE reads. Evaluating a script needs the host, which the core has.
    api(libs.kotlin.scripting.common)
    api(libs.kotlin.scripting.jvm)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlin.scripting.jvm.host)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
