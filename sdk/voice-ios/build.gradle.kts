import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

kotlin {
    explicitApi()

    // `./gradlew :sdk:voice-ios:assembleVoiceSdkXCFramework` (on macOS) builds VoiceSdk.xcframework
    // for Swift apps; it exports the whole voice-core API. `zipVoiceSdkXCFramework` packs the
    // release build for Xcode or a Swift Package binary target.
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

// dist/VoiceSdk-<version>.xcframework.zip, with VoiceSdk.xcframework at the top of the zip as
// Swift Package Manager expects. Needs a Mac with Xcode.
tasks.register<Zip>("zipVoiceSdkXCFramework") {
    group = "publishing"
    description = "Builds the release VoiceSdk.xcframework and zips it into dist/."
    dependsOn("assembleVoiceSdkReleaseXCFramework")
    from(layout.buildDirectory.dir("XCFrameworks/release"))
    include("VoiceSdk.xcframework/**")
    archiveFileName.set("VoiceSdk-${project.version}.xcframework.zip")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("dist"))
}
