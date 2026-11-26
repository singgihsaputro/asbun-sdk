package id.ojolmitra.driver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
 * [isForeground]: kata panggil hanya aktif saat true. iOS memakai default
 * (ponytail: iOS always foreground; observe UIApplication notifications if backgrounding matters).
 * [createVoice] membuat Voice Command SDK untuk platform ini dengan scope UI.
 */
@Composable
fun App(createVoice: (CoroutineScope) -> VoiceSdk, isForeground: Boolean = true) {
    val scope = rememberCoroutineScope()
    val controller = remember { DriverController(createVoice(scope), scope) }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    LaunchedEffect(isForeground) { controller.setAppForeground(isForeground) }
    OjolTheme {
        DriverApp(controller)
    }
}
