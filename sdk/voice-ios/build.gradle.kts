import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()

    // `./gradlew :sdk:voice-ios:assembleVoiceSdkXCFramework` (on macOS) builds VoiceSdk.xcframework
    // for Swift apps; it exports the whole voice-core API.
    val xcframework = XCFramework("VoiceSdk")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "VoiceSdk"
            isStatic = true
            export(project(":sdk:voice-core"))
            xcframework.add(this)
        }
    }

    sourceSets {
        iosMain.dependencies {
            api(project(":sdk:voice-core"))
        }
    }
}
