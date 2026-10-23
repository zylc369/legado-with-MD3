package io.legado.app.help.readaloud.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.constant.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.uuid.Uuid
import kotlin.coroutines.resume

/** Serializes Android TTS file synthesis and reuses the active engine between adjacent cues. */
class SystemTtsFileSynthesizer(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private var tts: TextToSpeech? = null
    private var activeEngine = ""

    suspend fun synthesize(
        engine: String,
        voiceName: String,
        text: String,
        output: File,
        speechRate: Float,
        pitch: Float = 1f,
    ): Boolean = mutex.withLock {
        val instance = ensureEngine(engine) ?: return@withLock false
        if (voiceName.isNotBlank()) {
            val voice = instance.voices.orEmpty().firstOrNull { it.name == voiceName }
                ?: return@withLock false
            if (instance.setVoice(voice) == TextToSpeech.ERROR) return@withLock false
        }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        val utteranceId = "legado-file-${Uuid.random()}"
        suspendCancellableCoroutine { continuation ->
            instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(completedId: String?) {
                    if (completedId == utteranceId && continuation.isActive) {
                        continuation.resume(output.exists() && output.length() > 0)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(failedId: String?) {
                    if (failedId == utteranceId && continuation.isActive) continuation.resume(false)
                }

                @Suppress("DEPRECATION")
                override fun onError(failedId: String?, errorCode: Int) = onError(failedId)
            })
            if (instance.setSpeechRate(speechRate) == TextToSpeech.ERROR ||
                instance.setPitch(pitch) == TextToSpeech.ERROR
            ) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            val result = instance.synthesizeToFile(text, null, output, utteranceId)
            if (result == TextToSpeech.ERROR && continuation.isActive) continuation.resume(false)
            continuation.invokeOnCancellation { instance.stop() }
        }
    }

    /**
     * 直接朗读一段（不落文件），复用同一个 TTS 引擎实例。
     *
     * [onRangeStart] 回传该段文本内的字符偏移（TTS 引擎逐词上报），已切回主线程；
     * 供混合朗读把系统兜底段直接交给 TTS 播放，无需先 synthesizeToFile 再交给 ExoPlayer。
     */
    suspend fun speak(
        engine: String,
        voiceName: String,
        text: String,
        speechRate: Float,
        pitch: Float = 1f,
        onRangeStart: (Int) -> Unit = {},
    ): Boolean = mutex.withLock {
        val instance = ensureEngine(engine) ?: return@withLock false
        if (voiceName.isNotBlank()) {
            val voice = instance.voices.orEmpty().firstOrNull { it.name == voiceName }
                ?: return@withLock false
            if (instance.setVoice(voice) == TextToSpeech.ERROR) return@withLock false
        }
        val speakId = "legado-speak-${Uuid.random()}"
        AppLog.putDebug(
            "混合TTS: 系统TTS speak engine=$engine voice=$voiceName" +
                " rate=$speechRate textLen=${text.length} id=$speakId"
        )
        var rangeCount = 0
        var firstRange = -1
        var lastRange = -1
        suspendCancellableCoroutine { continuation ->
            instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId == speakId) {
                        AppLog.putDebug("混合TTS: 系统TTS onStart id=$speakId")
                    }
                }

                override fun onDone(completedId: String?) {
                    if (completedId == speakId && continuation.isActive) {
                        AppLog.putDebug(
                            "混合TTS: 系统TTS onDone id=$speakId ranges=$rangeCount" +
                                " first=$firstRange last=$lastRange"
                        )
                        continuation.resume(true)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(failedId: String?) {
                    if (failedId == speakId && continuation.isActive) {
                        AppLog.putDebug(
                            "混合TTS: 系统TTS onError id=$speakId ranges=$rangeCount" +
                                " first=$firstRange last=$lastRange"
                        )
                        continuation.resume(false)
                    }
                }

                @Suppress("DEPRECATION")
                override fun onError(failedId: String?, errorCode: Int) = onError(failedId)

                override fun onStop(stoppedId: String?, interrupted: Boolean) {
                    if (stoppedId == speakId && continuation.isActive) {
                        AppLog.putDebug(
                            "混合TTS: 系统TTS onStop id=$speakId ranges=$rangeCount" +
                                " first=$firstRange last=$lastRange"
                        )
                        continuation.resume(false)
                    }
                }

                override fun onRangeStart(
                    utteranceId: String?,
                    start: Int,
                    end: Int,
                    frame: Int,
                ) {
                    if (utteranceId == speakId) {
                        rangeCount++
                        if (firstRange < 0) firstRange = start
                        lastRange = start
                        mainHandler.post { onRangeStart(start) }
                    }
                }
            })
            if (instance.setSpeechRate(speechRate) == TextToSpeech.ERROR ||
                instance.setPitch(pitch) == TextToSpeech.ERROR
            ) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            val result = instance.speak(text, TextToSpeech.QUEUE_FLUSH, null, speakId)
            if (result == TextToSpeech.ERROR && continuation.isActive) continuation.resume(false)
            continuation.invokeOnCancellation { instance.stop() }
        }
    }

    /** 停止当前直接朗读（取消协程时也会自动 stop）。 */
    fun stopSpeaking() {
        tts?.runCatching { stop() }
    }

    suspend fun close() = withContext(Dispatchers.Main.immediate) {
        tts?.stop()
        tts?.shutdown()
        tts = null
        activeEngine = ""
    }

    private suspend fun ensureEngine(engine: String): TextToSpeech? {
        if (tts != null && activeEngine == engine) return tts
        close()
        val instance = SystemTtsEngineFactory.create(appContext, engine) ?: return null
        tts = instance
        activeEngine = engine
        return instance
    }
}
