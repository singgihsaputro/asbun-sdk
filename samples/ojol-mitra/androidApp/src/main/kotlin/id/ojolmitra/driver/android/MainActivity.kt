package id.ojolmitra.driver.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import id.asbun.voice.LlmConfig
import id.asbun.voice.LlmProtocol
import id.asbun.voice.android.VoiceSdkAndroid
import id.ojolmitra.driver.App
import id.ojolmitra.driver.voice.OjolMitraVoice

class MainActivity : ComponentActivity() {

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(this, "Izin mikrofon dibutuhkan untuk perintah suara. Kamu tetap bisa mengetik perintah.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val config = OjolMitraVoice.config(llm = llmFromBuildConfig())
        setContent {
            // The SDK is released (engine shut down) when the composition is disposed.
            App { scope ->
                VoiceSdkAndroid.create(this, config, OjolMitraVoice.manifest, requestPermission = ::requestMicPermission, scope = scope)
            }
        }
        requestMicPermission()
    }

    /**
     * Optional LLM fallback, from `local.properties` (see the README). Demo only: a key in
     * the APK can be extracted, so production apps use a backend proxy or `tokenProvider`.
     */
    private fun llmFromBuildConfig(): LlmConfig? {
        if (BuildConfig.VOICE_LLM_BASE_URL.isBlank()) return null
        return LlmConfig(
            baseUrl = BuildConfig.VOICE_LLM_BASE_URL,
            model = BuildConfig.VOICE_LLM_MODEL,
            protocol = LlmProtocol.valueOf(BuildConfig.VOICE_LLM_PROTOCOL),
            apiKey = BuildConfig.VOICE_LLM_API_KEY.ifBlank { null },
        )
    }

    private fun requestMicPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
