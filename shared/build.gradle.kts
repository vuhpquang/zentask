import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.skie)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.android.kmp.library)
}

sqldelight {
    databases {
        create("ZenTaskDatabase") {
            packageName.set("com.vuhpquang.zentask.db")
            // json_extract() for indexed-free field lookups (parentId, taskId, repeatTaskId).
            module(libs.sqldelight.json.get().toString())
        }
    }
}

// Generates ZenTaskBuild.VERSION from the root VERSION file (single source of the app version).
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val versionFile = rootProject.layout.projectDirectory.file("VERSION")
    val outDir = layout.buildDirectory.dir("generated/buildinfo/commonMain/kotlin")
    inputs.file(versionFile)
    outputs.dir(outDir)
    doLast {
        val version = versionFile.asFile.readText().trim()
        val file = outDir.get().file("com/vuhpquang/zentask/ZenTaskBuild.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.vuhpquang.zentask
            |
            |internal object ZenTaskBuild {
            |    const val VERSION: String = "$version"
            |}
            |""".trimMargin()
        )
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    jvm()

    // P7: the Android app uses this core. minSdk 34 = Android 14, whose SQLite has json_extract built in.
    androidLibrary {
        namespace = "com.vuhpquang.zentask.shared"
        compileSdk = 37
        minSdk = 34
    }

    // Kotlin/Native targets only configure on macOS so Linux (Docker) builds stay JVM-only.
    if (HostManager.hostIsMac) {
        val xcf = XCFramework("ZenTaskShared")
        macosArm64 {
            binaries.framework {
                baseName = "ZenTaskShared"
                isStatic = true
                binaryOption("bundleId", "com.vuhpquang.zentask.shared")
                xcf.add(this)
            }
        }
    }

    sourceSets {
        all { languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi") }
        commonMain {
            kotlin.srcDir(generateBuildInfo)
            dependencies {
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.datetime)
                api(libs.sqldelight.coroutines)
                api(libs.ktor.client.core)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android)
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.jvm)
            implementation(libs.ktor.client.cio)
        }
        if (HostManager.hostIsMac) {
            macosMain.dependencies {
                implementation(libs.sqldelight.native)
                implementation(libs.ktor.client.darwin)
            }
        }
    }
}
