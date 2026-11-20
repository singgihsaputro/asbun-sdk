package id.ojolmitra.driver

import androidx.compose.ui.window.ComposeUIViewController
import id.asbun.voice.ios.VoiceSdkIos
import id.ojolmitra.driver.voice.OjolMitraVoice
import platform.UIKit.UIViewController

/**
 * Dipanggil dari Swift: `MainViewControllerKt.MainViewController()`.
 *
 * Untuk Claude pada perintah yang tidak dikenali kata kunci, ganti `OjolMitraVoice.config()` dengan
 * `OjolMitraVoice.config(llm = Claude.config(apiKey = "..."))` (hanya untuk build internal; lihat README).
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    App { scope -> VoiceSdkIos.create(OjolMitraVoice.config(), OjolMitraVoice.manifest, scope) }
}
