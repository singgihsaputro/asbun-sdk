plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

// Publishing the Voice Command SDK so other projects can depend on it by Maven coordinates
// (id.asbun.voice:voice-android:<version>, ...) instead of copying the source.
// `./gradlew sdkMavenRepoZip` writes dist/asbun-voice-sdk-<version>-maven-repo.zip.
val sdkGroup = "id.asbun.voice"
val sdkVersion = providers.gradleProperty("sdkVersion").get()
val sdkModules = listOf(":sdk:voice-core", ":sdk:voice-android", ":sdk:voice-ios")
val sdkRepoDir = layout.buildDirectory.dir("sdk-maven-repo")

subprojects {
    if (path !in sdkModules) return@subprojects
    group = sdkGroup
    version = sdkVersion
    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                // A plain folder laid out as a Maven repository; the zip task packs it.
                maven {
                    name = "sdkRepo"
                    url = uri(sdkRepoDir)
                }
            }
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set("Voice Command SDK: ${project.name}")
                    description.set("Turns speech into app actions on Android and iOS from a JSON command manifest.")
                    url.set("https://github.com/singgihsaputro/asbun-sdk")
                }
            }
        }
    }
}

tasks.register<Zip>("sdkMavenRepoZip") {
    group = "publishing"
    description = "Publishes the SDK modules to a local Maven repository and zips it into dist/."
    dependsOn(sdkModules.map { "$it:publishAllPublicationsToSdkRepoRepository" })
    from(sdkRepoDir)
    // Only this version, in case the folder still holds an earlier one.
    include("**/$sdkVersion/**")
    archiveFileName.set("asbun-voice-sdk-$sdkVersion-maven-repo.zip")
    destinationDirectory.set(layout.projectDirectory.dir("dist"))
}
