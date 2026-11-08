plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersion = rootProject.layout.projectDirectory.file("VERSION").asFile.readText().trim()

android {
    namespace = "com.vuhpquang.zentask.wear"
    compileSdk = 37

    defaultConfig {
        // Same id as the phone app (Wear apps are paired by package name); a separate install on the watch.
        applicationId = "com.vuhpquang.zentask"
        minSdk = 34 // Wear OS 5: SQLite with json_extract (the shared core uses it)
        targetSdk = 36
        versionCode = appVersion.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10_000 + b * 100 + c }
        versionName = appVersion
    }

    buildTypes {
        debug { applicationIdSuffix = ".dev" }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material.icons)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
}
