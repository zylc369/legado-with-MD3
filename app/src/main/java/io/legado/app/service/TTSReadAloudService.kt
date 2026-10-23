@file:Suppress("DEPRECATION")
package io.legado.app.service

import android.app.PendingIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.model.readaloud.PlaybackPositionMap
import io.legado.app.domain.model.readaloud.ReadAloudEngineSelection
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechEngineRoute
import io.legado.app.domain.model.readaloud.SpeechVoiceRouter
import io.legado.app.domain.model.readaloud.SystemTtsVoiceConfig
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.MediaHelp
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.readaloud.playback.ReadAloudSpeed
import io.legado.app.help.readaloud.playback.SystemTtsEngineFactory
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.config.readConfig.ReadConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 本地朗读
 */
class TTSReadAloudService : BaseReadAloudService(), KoinComponent {

    override val useSpeechPlaybackQueue: Boolean = true

    protected override val currentSpeechRate: Float
        get() = if (ReadConfig.ttsFollowSys) 1f else ReadAloudSpeed.fromTenths(speechRateSetting)

    private val readAloudSettingsGateway: ReadAloudSettingsGateway by inject()
    @Volatile
    private var speechRateSetting: Int = 10

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private val ttsUtteranceListener = TTSUtteranceListener()
    private var speakJob: Coroutine<*>? = null
    private var utteranceStartPos = 0
    private var utteranceStartMap: PlaybackPositionMap? = null
    private var needParagraphInterval = false // 是否需要进行段落间隔延迟
    private var activeEngine = ""
    private var activeVoiceName = ""
    private var activeTraitsJson = ""
    private var defaultVoiceName = ""
    private var initGeneration = 0

    /** 播放会话号: 每次真正发声、停止、暂停、清理或整体换章时递增, 用于拒收旧会话的回调 */
    @Volatile
    private var speakSession = 0
    private val TAG = "TTSReadAloudService"

    override fun onCreate() {
        super.onCreate()
        speechRateSetting = readAloudSettingsGateway.currentSettings.ttsSpeechRate
        lifecycleScope.launch {
            readAloudSettingsGateway.settings.collect {
                speechRateSetting = it.ttsSpeechRate
            }
        }
        initTts()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearTTS()
    }

    @Synchronized
    private fun initTts(engineOverride: String? = null) {
        ttsInitFinish = false
        val engine = engineOverride
            ?: ReadAloudEngineSelection.parse(ReadAloud.ttsEngine)
                ?.takeIf { it.engineType == ReadAloudVoice.ENGINE_SYSTEM }?.engineId
        activeEngine = engine.orEmpty()
        val generation = ++initGeneration
        LogUtils.d(TAG, "initTts engine:$engine")
        textToSpeech = SystemTtsEngineFactory.create(this, engine.orEmpty()) { tts ->
            onTtsInitialized(tts, generation)
        }
        upSpeechRate()
    }

    @Synchronized
    fun clearTTS() {
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
        activeVoiceName = ""
        defaultVoiceName = ""
        initGeneration++
        speakSession++
    }

    private fun onTtsInitialized(tts: TextToSpeech?, generation: Int) {
        if (generation != initGeneration) {
            tts?.shutdown()
            return
        }
        if (tts != null) {
            textToSpeech = tts
            tts.setOnUtteranceProgressListener(ttsUtteranceListener)
            defaultVoiceName = tts.defaultVoice?.name.orEmpty()
            activeVoiceName = defaultVoiceName
            ttsInitFinish = true
            // 首次启动时 TTS 初始化常早于后台取章/分段完成，此刻列表还是空的。若直接 play()，
            // 会走「列表为空」兜底去 ReadBook.readAloud()（不带选中位置），把正在进行的、带
            // 选中位置的准备任务顶掉，于是从页首开始朗读。准备完成后的 play() 会再触发播放。
            if (contentList.isNotEmpty()) play()
        } else {
            toastOnUi(R.string.tts_init_failed)
        }
    }

    @Synchronized
    override fun play() {
        if (hasSpeechPlaybackQueue) {
            val route = systemVoiceForCurrentCue()
            val requiredEngine = route.engineId
            if (requiredEngine != activeEngine || textToSpeech == null) {
                AppLog.putDebug(
                    "朗读：需切换/初始化 TTS 引擎 required=$requiredEngine active=$activeEngine " +
                        "ttsNull=${textToSpeech == null}"
                )
                clearTTS()
                initTts(requiredEngine)
                return
            }
            applyVoice(route.speakerId)
            applyPreset(route)
        }
        if (!ttsInitFinish) {
            AppLog.putDebug("朗读未开始：TTS 尚未初始化完成，等 onTtsInitialized 回调后再播放")
            return
        }
        if (!requestFocus()) {
            AppLog.putDebug("朗读未开始：未获取到音频焦点")
            return
        }
        if (contentList.isEmpty()) {
            AppLog.put("朗读列表为空，重新准备朗读")
            ReadBook.readAloud()
            return
        }
        super.play()
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        
        // 捕获本次是否需要进行段落延迟，并将标志位复位（防多次触发）
        val isDelay = needParagraphInterval
        needParagraphInterval = false
        
        speakJob?.cancel()
        speakJob = execute {
            val interval = ReadConfig.ttsParagraphInterval.toLong()
            AppLog.putDebug("TTS_PLAY: nowSpeak=$nowSpeak, isDelay=$isDelay, interval=$interval")
            
            if (hasSpeechPlaybackQueue || interval > 0) {
                // 段落间隔模式：单段播放
                if (isDelay) {
                    AppLog.putDebug("TTS开始延迟: $interval 毫秒")
                    delay(interval)
                    AppLog.putDebug("TTS延迟结束，准备播放")
                }
                ensureActive()

                LogUtils.d(TAG, "朗读列表大小 ${contentList.size}")
                val session = ++speakSession
                val tts = textToSpeech ?: throw NoStackTraceException("tts is null")
                var text = contentList[nowSpeak]
                if (paragraphStartPos > 0) {
                    text = text.substring(paragraphStartPos)
                }
                if (text.matches(AppPattern.notReadAloudRegex)) {
                    AppLog.putDebug("TTS段落全标点跳过: nowSpeak=$nowSpeak")
                    ttsUtteranceListener.onDone(ttsUtteranceId(AppConst.APP_TAG, session, nowSpeak))
                    return@execute
                }
                AppLog.putDebug("TTS开始Speak: 段落长度=${text.length}")
                val result = tts.runCatching {
                    speak(text, TextToSpeech.QUEUE_FLUSH, null, ttsUtteranceId(AppConst.APP_TAG, session, nowSpeak))
                }.getOrElse {
                    AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                    TextToSpeech.ERROR
                }
                if (result == TextToSpeech.ERROR) {
                    AppLog.put("tts出错 尝试重新初始化")
                    clearTTS()
                    initTts()
                    return@execute
                }
                LogUtils.d(TAG, "朗读内容添加完成")
            } else {
                // 无间隔模式：保持原有的队列式连续播放，确保无缝衔接
                LogUtils.d(TAG, "朗读列表大小 ${contentList.size}")
                LogUtils.d(TAG, "朗读页数 ${readerReadAloudChapter?.pageCount}")
                val session = ++speakSession
                val tts = textToSpeech ?: throw NoStackTraceException("tts is null")
                val contentList = contentList
                var isAddedText = false
                for (i in nowSpeak until contentList.size) {
                    ensureActive()
                    var text = contentList[i]
                    if (paragraphStartPos > 0 && i == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    if (text.matches(AppPattern.notReadAloudRegex)) {
                        continue
                    }
                    if (!isAddedText) {
                        val result = tts.runCatching {
                            speak(text, TextToSpeech.QUEUE_FLUSH, null, ttsUtteranceId(AppConst.APP_TAG, session, i))
                        }.getOrElse {
                            AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                            TextToSpeech.ERROR
                        }
                        if (result == TextToSpeech.ERROR) {
                            AppLog.put("tts出错 尝试重新初始化")
                            clearTTS()
                            initTts()
                            return@execute
                        }
                    } else {
                        val result = tts.runCatching {
                            speak(text, TextToSpeech.QUEUE_ADD, null, ttsUtteranceId(AppConst.APP_TAG, session, i))
                        }.getOrElse {
                            AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                            TextToSpeech.ERROR
                        }
                        if (result == TextToSpeech.ERROR) {
                            AppLog.put("tts朗读出错: 段落长度=${text.length}")
                        }
                    }
                    isAddedText = true
                }
                LogUtils.d(TAG, "朗读内容添加完成")
                if (!isAddedText) {
                    // 本协程仍需执行延迟后的章末处理，不能通过 playStop 取消自身。
                    speakSession++
                    needParagraphInterval = false
                    tts.runCatching { stop() }
                    delay(1000)
                    completeCurrentChapter()
                }
            }
        }.onError {
            AppLog.putDebug("TTS协程异常: ${it.localizedMessage}")
        }
    }

    private fun systemVoiceForCurrentCue(): ReadAloudVoice {
        val selection = ReadAloudEngineSelection.parse(ReadAloud.ttsEngine)
            ?.takeIf { it.engineType == ReadAloudVoice.ENGINE_SYSTEM }
        val configured = selection?.engineId.orEmpty()
        val configuredSpeaker = selection?.speakerId.orEmpty()
        val fallback = ReadAloudVoice(
            id = "runtime-system:$configured:$configuredSpeaker",
            engineType = ReadAloudVoice.ENGINE_SYSTEM,
            engineId = configured,
            speakerId = configuredSpeaker,
            displayName = configured,
        )
        val cue = playbackQueue.cues.getOrNull(nowSpeak) ?: return fallback
        return SpeechVoiceRouter.route(
            cue = cue,
            supportedEngineTypes = setOf(ReadAloudVoice.ENGINE_SYSTEM),
            defaultRoute = SpeechEngineRoute(
                ReadAloudVoice.ENGINE_SYSTEM,
                configured,
                configuredSpeaker,
            ),
        ).voice ?: fallback
    }

    private fun applyVoice(voiceName: String) {
        val requestedName = voiceName.ifBlank { defaultVoiceName }
        if (requestedName == activeVoiceName) return
        val tts = textToSpeech ?: return
        val voice = tts.voices.orEmpty().firstOrNull { it.name == requestedName }
        if (voice == null) {
            AppLog.putDebug("系统 TTS 音色不可用: $requestedName")
            return
        }
        if (tts.setVoice(voice) == TextToSpeech.SUCCESS) {
            activeVoiceName = requestedName
        } else {
            AppLog.putDebug("系统 TTS 音色切换失败: $requestedName")
        }
    }

    private fun applyPreset(voice: ReadAloudVoice) {
        activeTraitsJson = voice.traitsJson
        val config = runCatching {
            GSON.fromJson(voice.traitsJson, SystemTtsVoiceConfig::class.java)
        }.getOrNull() ?: SystemTtsVoiceConfig()
        textToSpeech?.apply {
            setSpeechRate(
                ReadAloudSpeed.systemTtsRate(
                    globalTenths = ReadConfig.ttsSpeechRate,
                    traitsJson = voice.traitsJson,
                    followSystem = ReadConfig.ttsFollowSys,
                )
            )
            setPitch(config.pitch ?: 1f)
        }
    }

    override fun playStop() {
        speakSession++
        speakJob?.cancel()
        needParagraphInterval = false
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        if (ReadConfig.ttsFollowSys) {
            if (reset) {
                clearTTS()
                initTts()
            }
        } else {
            val speechRate = ReadAloudSpeed.systemTtsRate(
                globalTenths = speechRateSetting,
                traitsJson = activeTraitsJson,
            )
            textToSpeech?.setSpeechRate(speechRate)
            upMediaMetadata()
            if (reset && !pause) {
                play()
            }
        }
    }

    /**
     * 暂停朗读
     */
    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        speakSession++
        speakJob?.cancel()
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 恢复朗读
     */
    override fun resumeReadAloud() {
        super.resumeReadAloud()
        play()
    }

    override fun onPlaybackStateReplaced() {
        // 换章/重新定位后, 旧章节发言的迟到回调不得再改新队列状态
        speakSession++
    }

    /**
     * 朗读监听
     */
    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"
        private var rangeCount = 0
        private var firstRange = -1
        private var lastRange = -1

        override fun onStart(s: String) {
            if (!isCurrentUtterance(s)) return
            LogUtils.d(TAG, "onStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$s")
            rangeCount = 0
            firstRange = -1
            lastRange = -1
            utteranceStartPos = paragraphStartPos
            utteranceStartMap = playbackUnits.getOrNull(nowSpeak)?.map
            if (isChapterTitleAt(nowSpeak)) {
                upMediaMetadata(showContent = true)
                return
            }
            readerReadAloudChapter?.let {
                val position = utteranceStartMap?.positionAt(utteranceStartPos) ?: return@let
                if (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex)) {
                    updateNextPos(naturalCompletion = true)
                }
                if (pageIndex + 1 < it.pageCount
                    && position + 1 > it.pageStart(pageIndex + 1)
                ) {
                    pageIndex++
                    // This is the TTS engine advancing across a page boundary, not a user turn.
                    // Mark it so ReadBook neither detaches the session nor restarts TTS at page two.
                    withSpeechNavigation { ReadBook.moveToNextPage() }
                }
                AppLog.putDebug(
                    "TTS: onStart nowSpeak=$nowSpeak pos=$position" +
                        " textLen=${contentList[nowSpeak].length}"
                )
                publishSystemTtsStart(position)
                upMediaMetadata(showContent = true)
            }
        }

        override fun onDone(s: String) {
            if (!isCurrentUtterance(s)) return
            LogUtils.d(TAG, "onDone utteranceId:$s")
            AppLog.putDebug(
                "TTS: onDone nowSpeak=$nowSpeak ranges=$rangeCount first=$firstRange last=$lastRange"
            )
            // 章节自然完结时本章播放状态已作废, 恢复播放由换章后的 newReadAloud 负责;
            // 若此处仍用旧队列 play(), 会把本章最后一段反复重新入队
            val paragraphAdvanced = updateNextPos(naturalCompletion = true)
            if (paragraphAdvanced &&
                !pause &&
                (hasSpeechPlaybackQueue || ReadConfig.ttsParagraphInterval > 0)
            ) {
                needParagraphInterval = ReadConfig.ttsParagraphInterval > 0
                play()
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            super.onRangeStart(utteranceId, start, end, frame)
            if (!isCurrentUtterance(utteranceId)) return
            paragraphStartPos = utteranceStartPos + start
            if (isChapterTitleAt(nowSpeak)) return
            // 正在朗读的精确章内位置：单元内偏移查锚点表得到，保持 readAloudNumber 的"段起点"语义
            val position = utteranceStartMap?.positionAt(paragraphStartPos) ?: return
            rangeCount++
            if (firstRange < 0) firstRange = start
            lastRange = start
            val msg =
                "onRangeStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId start:$start end:$end frame:$frame"
            LogUtils.d(TAG, msg)
            publishSystemTtsRange(position)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (!isCurrentUtterance(utteranceId)) return
            LogUtils.d(
                TAG,
                "onError nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId errorCode:$errorCode"
            )
            AppLog.putDebug(
                "TTS: onError nowSpeak=$nowSpeak ranges=$rangeCount first=$firstRange last=$lastRange"
            )
            val paragraphAdvanced = updateNextPos(naturalCompletion = true)
            if (paragraphAdvanced &&
                !pause &&
                (hasSpeechPlaybackQueue || ReadConfig.ttsParagraphInterval > 0)
            ) {
                needParagraphInterval = ReadConfig.ttsParagraphInterval > 0
                play()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
            if (!isCurrentUtterance(s)) return
            LogUtils.d(TAG, "onError nowSpeak:$nowSpeak pageIndex:$pageIndex s:$s")
            AppLog.putDebug(
                "TTS: onError nowSpeak=$nowSpeak ranges=$rangeCount first=$firstRange last=$lastRange"
            )
            val paragraphAdvanced = updateNextPos(naturalCompletion = true)
            if (paragraphAdvanced &&
                !pause &&
                (hasSpeechPlaybackQueue || ReadConfig.ttsParagraphInterval > 0)
            ) {
                needParagraphInterval = ReadConfig.ttsParagraphInterval > 0
                play()
            }
        }

        private fun isCurrentUtterance(utteranceId: String?): Boolean =
            utteranceId != null &&
                utteranceId == ttsUtteranceId(AppConst.APP_TAG, speakSession, nowSpeak)

    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }

}

/**
 * TTS 回调只回传 utteranceId, 播放会话号必须编码进 id,
 * 否则旧会话(暂停/停止/换章后迟到)的回调无法与当前队列区分
 */
internal fun ttsUtteranceId(appTag: String, session: Int, index: Int): String =
    "$appTag$session:$index"
