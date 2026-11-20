package id.ojolmitra.driver

import androidx.compose.ui.window.ComposeUIViewController
import id.ojolmitra.driver.voice.IosVoiceEngine
import platform.UIKit.UIViewController

/** Dipanggil dari Swift: `MainViewControllerKt.MainViewController()`. */
fun MainViewController(): UIViewController {
    val voiceEngine = IosVoiceEngine()
    return ComposeUIViewController { App(voiceEngine) }
}
