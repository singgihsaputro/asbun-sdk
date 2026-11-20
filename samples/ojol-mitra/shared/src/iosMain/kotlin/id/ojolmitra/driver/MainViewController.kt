package id.ojolmitra.driver

import androidx.compose.ui.window.ComposeUIViewController
import id.asbun.voice.ios.VoiceSdkIos
import id.ojolmitra.driver.voice.OjolMitraVoice
import platform.UIKit.UIViewController

/** Dipanggil dari Swift: `MainViewControllerKt.MainViewController()`. */
fun MainViewController(): UIViewController = ComposeUIViewController {
    App { scope -> VoiceSdkIos.create(OjolMitraVoice.config(), OjolMitraVoice.manifest, scope) }
}
