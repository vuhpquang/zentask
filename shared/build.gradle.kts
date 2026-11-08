import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.skie)
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
    jvm()

    // Kotlin/Native targets only configure on macOS so Linux (Docker) builds stay JVM-only.
    if (HostManager.hostIsMac) {
        val xcf = XCFramework("ZenTaskShared")
        macosArm64 {
            binaries.framework {
                baseName = "ZenTaskShared"
                isStatic = true
                xcf.add(this)
            }
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuildInfo)
            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
