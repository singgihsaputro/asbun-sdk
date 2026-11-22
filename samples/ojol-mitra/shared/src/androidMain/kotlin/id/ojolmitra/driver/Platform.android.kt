package id.ojolmitra.driver

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual fun epochMillis(): Long = System.currentTimeMillis()

actual fun formatClock(epochMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))

actual val platformName: String
    get() = "Android ${android.os.Build.VERSION.RELEASE ?: ""}".trim()

actual fun playWakeTone() {
    val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80)
    tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
    // ponytail: leaks a native handle if never released; release after the tone when called often
}

@androidx.compose.runtime.Composable
actual fun KeepScreenOn(enabled: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}
