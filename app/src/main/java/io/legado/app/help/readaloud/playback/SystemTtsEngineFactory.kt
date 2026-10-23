package io.legado.app.help.readaloud.playback

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 系统 TTS 实例创建的唯一入口。
 *
 * TTSReadAloudService、SystemTtsVoiceCatalog、SystemTtsFileSynthesizer、help/TTS
 * 都必须用它创建实例，避免各自的 engine/回调/初始化逻辑漂移。
 */
object SystemTtsEngineFactory {

    fun create(
        context: Context,
        enginePackage: String,
        onInit: (TextToSpeech?) -> Unit,
    ): TextToSpeech {
        lateinit var instance: TextToSpeech
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                onInit(instance)
            } else {
                instance.shutdown()
                onInit(null)
            }
        }
        instance = if (enginePackage.isBlank()) {
            TextToSpeech(context.applicationContext, listener)
        } else {
            TextToSpeech(context.applicationContext, listener, enginePackage)
        }
        return instance
    }

    suspend fun create(context: Context, enginePackage: String): TextToSpeech? =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val instance = create(context, enginePackage) { tts ->
                    if (!continuation.isActive) {
                        tts?.shutdown()
                    } else {
                        continuation.resume(tts)
                    }
                }
                continuation.invokeOnCancellation { instance.shutdown() }
            }
        }
}
