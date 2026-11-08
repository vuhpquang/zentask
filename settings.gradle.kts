rootProject.name = "zentask"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":shared")
include(":server")
// The Android app (P7) builds only where an Android SDK is installed (not in the server's Docker build).
if (System.getenv("ANDROID_HOME") != null || file("local.properties").exists()) {
    include(":android")
    project(":android").projectDir = file("apps/android")
}
