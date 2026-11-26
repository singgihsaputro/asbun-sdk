package id.ojolmitra.driver.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureScreenRoboImage
import id.asbun.voice.VoiceEngine
import id.asbun.voice.VoiceEngineListener
import id.asbun.voice.VoiceSdk
import id.ojolmitra.driver.App
import id.ojolmitra.driver.core.DriverController
import id.ojolmitra.driver.sim.DemandForecast
import id.ojolmitra.driver.ui.screens.DriverApp
import id.ojolmitra.driver.ui.theme.OjolTheme
import id.ojolmitra.driver.voice.OjolMitraVoice
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Engine suara palsu: test "mengucapkan" kalimat lewat [partial] dan [say], sehingga seluruh
 * pipeline perintah suara (SDK: manifest -> deeplink/handler -> controller -> UI) teruji di build Android.
 */
private class ScriptedVoiceEngine : VoiceEngine {
    override val isRecognitionAvailable = true
    override var listener: VoiceEngineListener? = null
    var listening = false
        private set
    val spoken = mutableListOf<String>()

    override fun startListening(languageTag: String) {
        listening = true
        listener?.onListeningStarted()
        listener?.onSoundLevel(0.7f)
    }

    override fun stopListening() {}
    override fun cancelListening() {
        listening = false
    }

    override fun speak(text: String, utteranceId: String, languageTag: String) {
        spoken += text
        listener?.onSpeakingStarted(utteranceId)
        listener?.onSpeakingDone(utteranceId)
    }

    override fun stopSpeaking() {}
    override fun shutdown() {}

    fun partial(text: String) = listener?.onPartialResult(text)

    fun say(text: String) {
        check(listening) { "asisten tidak sedang mendengarkan" }
        listening = false
        listener?.onFinalResult(listOf(text))
    }
}

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class DriverJourneyScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val engine = ScriptedVoiceEngine()

    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(left, 200L)
            compose.mainClock.advanceTimeBy(step)
            left -= step
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) = captureScreenRoboImage("screenshots/$name.png")

    private fun tapMic() {
        compose.onAllNodesWithContentDescription("Asisten suara")[0].performClick()
        advance(100)
    }

    /** Tutup panel asisten (seperti driver menekan X) supaya tombol di bawahnya bisa ditekan. */
    private fun closeVoicePanel() {
        val close = compose.onAllNodesWithContentDescription("Tutup")
        if (close.fetchSemanticsNodes().isNotEmpty()) {
            close[0].performClick()
            advance(800)
        }
    }

    private fun speakCommand(text: String) {
        if (!engine.listening) tapMic()
        engine.say(text)
        advance(300)
    }

    @Test
    fun fullDriverJourneyWithVoiceCommands() {
        compose.mainClock.autoAdvance = false
        compose.setContent { App { scope -> VoiceSdk(OjolMitraVoice.config(), OjolMitraVoice.manifest, engine, scope) } }
        advance(500)
        shot("01_beranda_offline")

        // Online lalu tunggu order masuk (simulasi 4-9 detik)
        compose.onNodeWithText("Mulai Narik").performClick()
        advance(1_500)
        shot("02_beranda_online")
        advance(9_000)
        compose.onNodeWithText("Terima").assertExists()
        assertTrue("asisten harus langsung mendengarkan setelah membacakan order", engine.listening)
        engine.partial("oke terima")
        advance(400)
        shot("03_order_masuk_voice")

        // Terima lewat suara
        engine.say("oke terima")
        advance(3_000)
        compose.onNodeWithText("menuju", substring = true, ignoreCase = true).assertExists()
        shot("04_perjalanan_jemput")

        // Chat ke customer lewat suara
        tapMic()
        engine.partial("chat saya sudah dekat ya")
        advance(300)
        shot("05_panel_suara_chat")
        engine.say("chat saya sudah dekat ya")
        advance(6_000)
        closeVoicePanel()
        compose.onNodeWithContentDescription("Chat").performClick()
        advance(800)
        compose.onNodeWithText("Saya sudah dekat ya").assertExists()
        shot("06_chat_customer")
        compose.onNodeWithContentDescription("Kembali").performClick()
        advance(800)

        // Lapor lalu lintas lewat suara
        speakCommand("lapor macet parah di jalan sudirman")
        shot("07_lapor_macet_suara")

        // Lapor manual lewat form (tutup panel suara dulu)
        closeVoicePanel()
        compose.onNodeWithContentDescription("Lapor").performClick()
        advance(1_500)
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("Kirim laporan").performClick()
        advance(1_000)

        // Tahapan perjalanan via suara
        speakCommand("sudah sampai")
        speakCommand("penumpang sudah naik")
        advance(20_000)
        shot("09_antar_ke_tujuan")
        speakCommand("selesaikan order")
        advance(800)
        closeVoicePanel()
        compose.onNodeWithText("Order selesai!").assertExists()
        shot("10_order_selesai")
        compose.onNodeWithText("Lanjut narik").performClick()
        advance(500)

        // Tab lain
        compose.onNodeWithText("Pendapatan").performClick()
        advance(600)
        shot("11_pendapatan")
        compose.onNodeWithText("Lalu Lintas").performClick()
        advance(600)
        shot("12_lalu_lintas")
        compose.onNodeWithText("Asisten Suara").performClick()
        advance(600)
        shot("13_asisten_suara")

        assertTrue(engine.spoken.any { it.startsWith("Order selesai") })
        assertTrue(engine.spoken.any { it.startsWith("Laporan macet parah") })
    }

    /** Halaman Area Ramai pada jam tetap (17.40), supaya screenshot-nya sama setiap kali dijalankan. */
    @Test
    fun busyAreaPageIsReadAloudAndAnswersByVoice() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember {
                DriverController(
                    VoiceSdk(OjolMitraVoice.config(), OjolMitraVoice.manifest, engine, scope),
                    scope,
                    ambientSimulation = false,
                    timeOfDay = { 17 * 60 + 40 },
                )
            }
            OjolTheme { DriverApp(controller) }
        }
        advance(500)

        compose.onNodeWithText("Area Ramai").performClick()
        advance(1_000)
        assertTrue("halaman dibacakan saat dibuka", engine.spoken.any { it.startsWith("Ada ") && it.contains("area ramai") })
        // Daftar hanya menyusun kartu yang terlihat: cek area terdekat (nomor 1).
        val nearest = DemandForecast.forecast(17 * 60 + 40, DriverController.START_POSITION).first()
        compose.onNodeWithText(nearest.name).assertExists()
        shot("14_area_ramai")

        speakCommand("detail nomor 1")
        assertTrue(engine.spoken.last().startsWith("Nomor 1, "))
        shot("15_area_ramai_detail_suara")

        speakCommand("ada acara apa")
        assertTrue(engine.spoken.last().startsWith("Ada ") || engine.spoken.last().startsWith("Belum ada acara"))
    }
}
