package id.ojolmitra.driver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import id.asbun.voice.VoiceSdk
import id.ojolmitra.driver.core.DriverController
import id.ojolmitra.driver.ui.screens.DriverApp
import id.ojolmitra.driver.ui.theme.OjolTheme
import kotlinx.coroutines.CoroutineScope

/**
 * Entry point Compose Multiplatform. Android memanggilnya dari MainActivity,
 * iOS dari MainViewController() — UI & logika 100% sama.
 *
 * [createVoice] membuat Voice Command SDK untuk platform ini dengan scope UI.
 */
@Composable
fun App(createVoice: (CoroutineScope) -> VoiceSdk) {
    val scope = rememberCoroutineScope()
    val controller = remember { DriverController(createVoice(scope), scope) }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    OjolTheme {
        DriverApp(controller)
    }
}
