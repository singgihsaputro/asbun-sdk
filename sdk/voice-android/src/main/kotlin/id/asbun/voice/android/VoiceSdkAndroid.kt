package id.asbun.voice.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import id.asbun.voice.CommandManifest
import id.asbun.voice.HttpLlmClient
import id.asbun.voice.LinkOpener
import id.asbun.voice.LlmClient
import id.asbun.voice.VoiceConfig
import id.asbun.voice.VoiceSdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

/** Opens a deeplink with `Intent.ACTION_VIEW`; returns false when no app can handle it. */
public class AndroidLinkOpener(context: Context) : LinkOpener {
    private val appContext = context.applicationContext

    override fun open(url: String): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/** Builds a [VoiceSdk] wired to Android speech services. */
public object VoiceSdkAndroid {

    /**
     * @param requestPermission called when the microphone permission is missing; ask for
     *   `RECORD_AUDIO` there (e.g. with an ActivityResult launcher).
     * @param scope main-thread scope; defaults to a new [MainScope]. Pass your own (for
     *   example `lifecycleScope`) to tie the SDK to a lifecycle.
     */
    @JvmOverloads
    public fun create(
        context: Context,
        config: VoiceConfig,
        manifest: CommandManifest,
        requestPermission: () -> Unit = {},
        scope: CoroutineScope = MainScope(),
        llmClient: LlmClient? = config.llm?.let { HttpLlmClient(it) },
    ): VoiceSdk {
        val engine = AndroidVoiceEngine(context, requestPermission, config.dialog.endOfSpeechMs)
        return VoiceSdk(config, manifest, engine, scope, AndroidLinkOpener(context), llmClient)
    }
}
