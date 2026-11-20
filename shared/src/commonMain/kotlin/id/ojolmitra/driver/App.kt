package id.ojolmitra.driver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import id.ojolmitra.driver.core.DriverController
import id.ojolmitra.driver.ui.screens.DriverApp
import id.ojolmitra.driver.ui.theme.OjolTheme
import id.ojolmitra.driver.voice.VoiceEngine

/**
 * Entry point Compose Multiplatform. Android memanggilnya dari MainActivity,
 * iOS dari MainViewController() — UI & logika 100% sama.
 */
@Composable
fun App(voiceEngine: VoiceEngine) {
    val scope = rememberCoroutineScope()
    val controller = remember { DriverController(voiceEngine, scope) }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    OjolTheme {
        DriverApp(controller)
    }
}
