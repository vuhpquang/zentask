plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersion = rootProject.layout.projectDirectory.file("VERSION").asFile.readText().trim()

android {
    namespace = "com.vuhpquang.zentask.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vuhpquang.zentask"
        minSdk = 34 // Android 14: SQLite with json_extract built in (the shared core uses it)
        targetSdk = 36
        // 0.8.0 -> 800, 1.2.3 -> 10203
        versionCode = appVersion.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10_000 + b * 100 + c }
        versionName = appVersion
    }

    buildTypes {
        debug { applicationIdSuffix = ".dev" }
        release {
            isMinifyEnabled = false
            // Signed with the debug key until a release key is set up (personal sideload).
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
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.compose.ui.tooling)
}
