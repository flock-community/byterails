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

include("byterails-core", "byterails-rules", "byterails-gradle-plugin")
