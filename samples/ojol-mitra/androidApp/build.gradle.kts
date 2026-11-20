import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Optional LLM fallback for voice commands, read from the (git-ignored) root local.properties:
//   voice.llm.baseUrl=https://api.anthropic.com
//   voice.llm.model=<model id>
//   voice.llm.protocol=ANTHROPIC_MESSAGES   (or OPENAI_COMPATIBLE, CUSTOM_JSON)
//   voice.llm.apiKey=<key>
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun voiceProp(name: String, default: String = "") =
    "\"" + (localProps.getProperty("voice.llm.$name") ?: default).replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "id.ojolmitra.driver.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "id.ojolmitra.driver"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "VOICE_LLM_BASE_URL", voiceProp("baseUrl"))
        buildConfigField("String", "VOICE_LLM_MODEL", voiceProp("model"))
        buildConfigField("String", "VOICE_LLM_PROTOCOL", voiceProp("protocol", "OPENAI_COMPATIBLE"))
        buildConfigField("String", "VOICE_LLM_API_KEY", voiceProp("apiKey"))
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Screenshot Roborazzi disimpan ke androidApp/screenshots/
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("roborazzi.record.filePathStrategy", "relativePathFromCurrentDirectory")
                it.maxHeapSize = "3g"
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":samples:ojol-mitra:shared"))
    implementation(project(":sdk:voice-android"))
    implementation(libs.androidx.activity.compose)

    // Test UI end-to-end di JVM (Robolectric) + screenshot (Roborazzi)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
