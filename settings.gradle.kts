rootProject.name = "byterails"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("byterails-dsl", "byterails-core", "byterails-rules", "byterails-gradle-plugin")
