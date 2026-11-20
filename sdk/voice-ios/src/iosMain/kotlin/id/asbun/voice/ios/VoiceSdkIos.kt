package id.asbun.voice.ios

import id.asbun.voice.CommandManifest
import id.asbun.voice.HttpLlmClient
import id.asbun.voice.LinkOpener
import id.asbun.voice.LlmClient
import id.asbun.voice.VoiceConfig
import id.asbun.voice.VoiceSdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/** Opens a deeplink with `UIApplication.open`; returns false when the URL cannot be opened. */
public class IosLinkOpener : LinkOpener {
    override fun open(url: String): Boolean {
        val nsUrl = NSURL.URLWithString(url) ?: return false
        val app = UIApplication.sharedApplication
        if (!app.canOpenURL(nsUrl)) return false
        app.openURL(nsUrl, options = emptyMap<Any?, Any?>(), completionHandler = null)
        return true
    }
}

/** Builds a [VoiceSdk] wired to Apple's Speech and AVFAudio frameworks. */
public object VoiceSdkIos {

    /**
     * For Swift, which cannot use Kotlin default arguments:
     * `VoiceSdkIos.shared.create(config: config, manifest: manifest)`. Runs on the main queue.
     * Add `NSMicrophoneUsageDescription` and `NSSpeechRecognitionUsageDescription` to Info.plist.
     */
    public fun create(config: VoiceConfig, manifest: CommandManifest): VoiceSdk = create(config, manifest, MainScope())

    /** Kotlin: pass your own main-thread [scope] to tie the SDK to a lifecycle. */
    public fun create(
        config: VoiceConfig,
        manifest: CommandManifest,
        scope: CoroutineScope = MainScope(),
        llmClient: LlmClient? = config.llm?.let { HttpLlmClient(it) },
    ): VoiceSdk {
        val engine = IosVoiceEngine(
            endOfSpeechMs = config.dialog.endOfSpeechMs,
            listenTimeoutMs = config.dialog.listenTimeoutMs,
            defaultLanguageTag = config.locale,
        )
        return VoiceSdk(config, manifest, engine, scope, IosLinkOpener(), llmClient)
    }
}
