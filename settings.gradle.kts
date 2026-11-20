rootProject.name = "asbun-sdk"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// Voice Command SDK
include(":sdk:voice-core")
include(":sdk:voice-android")
include(":sdk:voice-ios")

// Sample app: Ojol Mitra driver prototype (first consumer of the SDK)
include(":samples:ojol-mitra:shared")
include(":samples:ojol-mitra:androidApp")
