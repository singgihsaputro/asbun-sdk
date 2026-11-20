package id.ojolmitra.driver.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import id.ojolmitra.driver.App
import id.ojolmitra.driver.voice.AndroidVoiceEngine

class MainActivity : ComponentActivity() {

    private lateinit var voiceEngine: AndroidVoiceEngine

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(this, "Izin mikrofon dibutuhkan untuk perintah suara. Kamu tetap bisa mengetik perintah.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        voiceEngine = AndroidVoiceEngine(this, requestPermission = ::requestMicPermission)
        setContent { App(voiceEngine) }
        requestMicPermission()
    }

    private fun requestMicPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        voiceEngine.shutdown()
        super.onDestroy()
    }
}
