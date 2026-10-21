package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.opensecurity.customtts.hybrid.HybridTtsPolicy
import com.opensecurity.customtts.model.CustomTtsCatalog
import com.opensecurity.customtts.model.CustomTtsSegment
import com.opensecurity.customtts.model.CustomTtsVoiceParams
import com.opensecurity.customtts.model.HybridTtsDecision
import com.opensecurity.customtts.model.HybridTtsSwitch
import com.opensecurity.remotelink.gateway.RemoteLinkManager
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.HttpTTS
import io.legado.app.domain.gateway.CloudTtsEngineGateway
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.domain.model.readaloud.ReadAloudPlaybackQueue
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechEngineRoute
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.readaloud.SpeechVoiceRouter
import io.legado.app.domain.model.readaloud.SystemTtsVoiceConfig
import io.legado.app.domain.model.settings.CustomTtsSettings
import io.legado.app.domain.model.settings.OtherSettings
import io.legado.app.domain.model.settings.ReadAloudContentSplitMode
import io.legado.app.domain.model.settings.ReadAloudSettings
import io.legado.app.domain.model.settings.ReadSettings
import io.legado.app.exception.NoStackTraceException
import io.legado.app.feature.reader.core.readaloud.ReaderReadAloudChapter
import io.legado.app.feature.reader.core.source.ReaderChapterSourceParser
import io.legado.app.feature.reader.platform.AndroidReaderHtmlSemanticTextResolver
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.readaloud.playback.CharacterPerformanceInstructionBuilder
import io.legado.app.help.readaloud.playback.CloudTtsAudioSynthesizer
import io.legado.app.help.readaloud.playback.CustomTtsUrlBuilder
import io.legado.app.help.readaloud.playback.HybridChapterBuilder
import io.legado.app.help.readaloud.playback.CloudTtsEmotionMapper
import io.legado.app.help.readaloud.playback.CloudTtsRoleInstructionMapper
import io.legado.app.help.readaloud.playback.SystemTtsFileSynthesizer
import io.legado.app.help.readaloud.playback.ReadAloudSpeed
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.readaloud.HybridSynthesisCoordinator
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Response
import org.koin.core.context.GlobalContext
import org.koin.java.KoinJavaComponent.get
import org.mozilla.javascript.WrappedException
import splitties.init.appCtx
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException

/** 系统 TTS 直读单段超时：部分 OEM 引擎可能永不回调 onDone/onError。 */
private const val HYBRID_SYSTEM_TTS_MIN_TIMEOUT_MS = 15_000L

/**
 * 在线朗读
 */
@SuppressLint("UnsafeOptInUsageError")
class HttpReadAloudService : BaseReadAloudService(),
    Player.Listener {
    override val useSpeechPlaybackQueue: Boolean = true

    protected override val currentSpeechRate: Float
        get() = synthesisSpeed(ReadAloud.httpTTS) * globalPlaybackSpeed

    private val readAloudSettingsGateway = GlobalContext.get().get<ReadAloudSettingsGateway>()
    private val readSettingsGateway = GlobalContext.get().get<ReadSettingsGateway>()
    private val otherSettingsGateway = GlobalContext.get().get<OtherSettingsGateway>()
    private var readAloudSettings: ReadAloudSettings = readAloudSettingsGateway.currentSettings
    private var readSettings: ReadSettings = readSettingsGateway.currentSettings
    private var otherSettings: OtherSettings = otherSettingsGateway.currentSettings

    private val speechRatePlay: Int
        get() = if (readAloudSettings.ttsFollowSys) {
            ReadAloudSpeed.DEFAULT_TENTHS
        } else {
            readAloudSettings.ttsSpeechRate
        }

    /**
     * 全局语速倍率, 由设置里的朗读语速换算而来, 在播放端 (ExoPlayer) 变速,
     * 对所有来源的音频 (http/系统/云端合成) 统一生效。
     */
    private val globalPlaybackSpeed: Float
        get() = ReadAloudSpeed.globalPlaybackSpeed(
            globalTenths = readAloudSettings.ttsSpeechRate,
            followSystem = readAloudSettings.ttsFollowSys,
        )

    /**
     * 源级合成语速倍率, 仅当源接口支持语速参数 ({{speakSpeed}}) 时影响返回的音频。
     * 全局语速不再参与合成, 避免与播放端变速叠加。
     */
    private fun synthesisSpeed(httpTts: HttpTTS?): Float =
        ((httpTts?.speed ?: DEFAULT_TTS_SPEED) + 5) / 10f

    private data class PreDownloadChapter(
        val chapterTitle: String,
        val queue: ReadAloudPlaybackQueue,
        val contentList: List<String>,
    )

    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build()
    }

    // 改为外部存储
    private val ttsFolderPath: String by lazy {
        val baseDir = externalCacheDir ?: cacheDir
        baseDir.absolutePath + File.separator + "httpTTS" + File.separator
    }

    private val cache by lazy {
        val baseDir = externalCacheDir ?: cacheDir
        SimpleCache(
            File(baseDir, "httpTTS_cache"),
            LeastRecentlyUsedCacheEvictor(128 * 1024 * 1024),
            StandaloneDatabaseProvider(appCtx)
        )
    }
    private val cacheDataSinkFactory by lazy {
        CacheDataSink.Factory()
            .setCache(cache)
    }
    private val loadErrorHandlingPolicy by lazy {
        CustomLoadErrorHandlingPolicy()
    }
    private var speechRate: Int = speechRatePlay
    private var downloadTask: Coroutine<*>? = null
    private var preDownloadJob: Job? = null
    private var playIndexJob: Job? = null
    private var paragraphIntervalJob: Coroutine<*>? = null
    private var downloadErrorNo: Int = 0
    private var playErrorNo = 0
    /** 「连续错误已暂停」只提示一次，直到下一次成功下载后再允许提示。 */
    private val consecutiveErrorToasted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val downloadTaskActiveLock = Mutex()
    private val systemTtsFileSynthesizer by lazy { SystemTtsFileSynthesizer(this) }
    private val cloudTtsAudioSynthesizer by lazy {
        CloudTtsAudioSynthesizer(get(CloudTtsEngineGateway::class.java))
    }
    private val customTtsSettingsGateway by lazy {
        GlobalContext.get().get<io.legado.app.domain.gateway.CustomTtsSettingsGateway>()
    }

    // ── 混合朗读状态（定制引擎专属；其余路径为空）────────────
    private var hybridSegments: List<CustomTtsSegment> = emptyList()
    private var hybridCoordinator: HybridSynthesisCoordinator? = null
    private var hybridDriverJob: Job? = null
    private var hybridAudioDone: CompletableDeferred<Unit>? = null
    /** 当前混合段是否由系统 TTS 直接 speak（决定暂停时是否需要取消驱动）。 */
    private var hybridCurrentSystemTts = false

    private fun isCustomHybridMode(): Boolean {
        val httpTts = ReadAloud.httpTTS ?: return false
        if (httpTts.id != CustomTtsCatalog.ENGINE_ID) return false
        return runCatching {
            customTtsSettingsGateway.currentSettings.hybrid.fallbackEngineId.isNotEmpty()
        }.getOrDefault(false)
    }

    private fun isHybridPlaybackActive(): Boolean =
        hybridSegments.isNotEmpty() && hybridCoordinator != null

    /**
     * 播放端实际倍速。
     *
     * 混合模式：兜底/定制速度就是**最终倍速**（分别在系统 TTS 合成、服务端合成各应用一次），
     * 播放端固定 1.0，避免全局语速再乘一层（1.5×2.0=3.0）。
     * 普通引擎：仍使用全局朗读语速。
     */
    private val effectivePlaybackSpeed: Float
        get() = if (isCustomHybridMode()) 1f else globalPlaybackSpeed

    private fun clearHybridState() {
        hybridDriverJob?.cancel()
        hybridDriverJob = null
        hybridAudioDone = null
        hybridCurrentSystemTts = false
        systemTtsFileSynthesizer.stopSpeaking()
        hybridCoordinator?.stop()
        hybridCoordinator = null
        hybridSegments = emptyList()
    }

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
        exoPlayer.setPlaybackSpeed(effectivePlaybackSpeed)
        lifecycleScope.launch {
            readAloudSettingsGateway.settings.collectLatest {
                readAloudSettings = it
                // 全局语速为播放端变速, 设置变化即时生效, 无需重新合成
                exoPlayer.setPlaybackSpeed(effectivePlaybackSpeed)
            }
        }
        lifecycleScope.launch {
            readSettingsGateway.settings.collectLatest { readSettings = it }
        }
        lifecycleScope.launch {
            otherSettingsGateway.settings.collectLatest { otherSettings = it }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadTask?.cancel()
        preDownloadJob?.cancel()
        exoPlayer.release()
        cache.release()
        Coroutine.async {
            systemTtsFileSynthesizer.close()
            removeCacheFile()
        }
    }

    override fun play() {
        pageChanged = false
        exoPlayer.stop()
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            super.play()
            // 引擎切换后（普通↔混合）播放倍速可能不同：混合固定 1.0，普通用全局语速。
            exoPlayer.setPlaybackSpeed(effectivePlaybackSpeed)
            if (isCustomHybridMode()) {
                playCustomHybrid()
            } else {
                clearHybridState()
                if (readAloudSettings.streamReadAloudAudio && !hasFileSynthesisCue()) {
                    downloadAndPlayAudiosStream()
                } else {
                    downloadAndPlayAudios()
                }
            }
        }
    }

    override fun playStop() {
        downloadTask?.cancel()
        playIndexJob?.cancel()
        preDownloadJob?.cancel()
        paragraphIntervalJob?.cancel()
        clearHybridState()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
    }

    private fun downloadAndPlayAudios() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        preDownloadJob?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")

                contentList.forEachIndexed { index, content ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    var text = content
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    val routedVoice = voiceForCue(playbackQueue, index, httpTts)
                    val cue = playbackQueue.cues.getOrNull(index)
                    val cueEmotion = cue?.emotion.orEmpty()
                    val characterPerformance = cue?.characterPerformance
                    val cueRoleType = cue?.roleType ?: SpeechRoleType.Unknown
                    val sourceKey = sourceKeyForCue(routedVoice, cue, httpTts)
                    val itemHttpTts = routedVoice.engineId.toLongOrNull()
                        ?.let(appDb.httpTTSDao::get) ?: httpTts
                    val fileName =
                        md5SpeakFileName(text, httpTts = itemHttpTts, sourceKey = sourceKey)
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                    if (speakText.isEmpty()) {
                        AppLog.put("阅读段落内容为空，使用无声音频代替。段落长度=${text.length}")
                        createSilentSound(fileName)
                    } else if (!hasSpeakFile(fileName)) {
                        runCatching {
                            when (routedVoice.engineType) {
                                ReadAloudVoice.ENGINE_SYSTEM -> {
                                    val output = getSpeakFileAsMd5(fileName)
                                    val config = runCatching {
                                        GSON.fromJson(
                                            routedVoice.traitsJson,
                                            SystemTtsVoiceConfig::class.java,
                                        )
                                    }.getOrNull() ?: SystemTtsVoiceConfig()
                                    // 全局语速已改为播放端变速, 系统合成只使用音色自带语速, 避免叠加
                                    if (!systemTtsFileSynthesizer.synthesize(
                                            routedVoice.engineId,
                                            routedVoice.speakerId,
                                            speakText,
                                            output,
                                            config.speechRate ?: 1f,
                                            config.pitch ?: 1f,
                                        )
                                    ) {
                                        createSilentSound(fileName)
                                    }
                                }

                                ReadAloudVoice.ENGINE_CLOUD -> {
                                    val output = getSpeakFileAsMd5(fileName)
                                    if (!cloudTtsAudioSynthesizer.synthesize(
                                            routedVoice,
                                            speakText,
                                            output,
                                            styleOverride = cueEmotion,
                                            characterPerformance = characterPerformance,
                                            roleType = cueRoleType,
                                        )
                                    ) {
                                        createSilentSound(fileName)
                                    }
                                }

                                else -> {
                                    val inputStream = getSpeakStream(
                                        itemHttpTts, speakText, customParamsFor(routedVoice))
                                    if (inputStream != null) {
                                        createSpeakFile(fileName, inputStream)
                                    } else {
                                        createSilentSound(fileName)
                                    }
                                }
                            }
                        }.onFailure {
                            when (it) {
                                is CancellationException -> Unit
                                else -> pauseReadAloud()
                            }
                            return@execute
                        }
                    }
                    if (speakText.isNotEmpty() && hasSpeakFile(fileName)) {
                        writeTextIndexEntry(fileName, speakText)
                    }
                    val file = getSpeakFileAsMd5(fileName)
                    val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                    launch(Main) {
                        if (readAloudSettings.ttsParagraphInterval > 0) {
                            if (index == nowSpeak && exoPlayer.mediaItemCount == 0) {
                                exoPlayer.setMediaItem(mediaItem)
                                if (!pause) {
                                    exoPlayer.prepare()
                                }
                                // 当前章开始播放后，立即异步启动后续章节预合成
                                launchPreDownload(httpTts)
                            }
                        } else {
                            if (exoPlayer.mediaItemCount == 0) {
                                exoPlayer.setMediaItem(mediaItem)
                                if (!pause) {
                                    exoPlayer.prepare()
                                }
                                // 当前章开始播放后，立即异步启动后续章节预合成
                                launchPreDownload(httpTts)
                            } else {
                                exoPlayer.addMediaItem(mediaItem)
                            }
                        }
                    }
                }
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    /** 混合朗读：前端分段 + 段级赛跑（远程优先，未就绪用兜底）。 */
    private fun playCustomHybrid() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        preDownloadJob?.cancel()
        clearHybridState()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val chapter = readerReadAloudChapter
                    ?: throw NoStackTraceException("章节未就绪")
                val phaseStart = System.currentTimeMillis()
                val settings = customTtsSettingsGateway.currentSettings
                val settingsMs = System.currentTimeMillis() - phaseStart
                val contentSplitMode = resolveContentSplitMode(
                    ReadAloudContentSplitMode.fromStorage(readAloudSettings.contentSplitMode)
                )
                val buildStart = System.currentTimeMillis()
                val built = HybridChapterBuilder.build(
                    chapter = chapter,
                    splitByPage = contentSplitMode == ReadAloudContentSplitMode.Page,
                    policy = contentSplitPolicy(contentSplitMode),
                    firstTargets = settings.segmentStairs,
                    target = settings.segmentTarget,
                )
                val buildMs = System.currentTimeMillis() - buildStart
                if (built.segments.isEmpty()) {
                    AppLog.put("混合朗读分段为空（章节内容为空）")
                    return@withLock
                }
                // 实际起点：readAloudNumber 是段落起点，paragraphStartPos 是段内偏移
                // （与普通路径 downloadAndPlayAudios 的 substring(paragraphStartPos) 一致）。
                // 混合模式此前直接 indexOfFirst{ >= readAloudNumber } 并丢弃 paragraphStartPos，
                // 导致从页中/下一段边界开始朗读。
                val requestedStart = readAloudNumber + paragraphStartPos
                AppLog.putDebug(
                    "混合朗读起点: page=$pageIndex" +
                        " pageStart=${readerReadAloudChapter?.pageStart(pageIndex)}" +
                        " readAloudNumber=$readAloudNumber" +
                        " paragraphStartPos=$paragraphStartPos" +
                        " requestedStart=$requestedStart"
                )
                val plan = HybridChapterBuilder.alignToStart(built, requestedStart)
                val segments = plan.segments
                val positions = plan.chapterPositions
                val startIndex = plan.startIndex
                val startChapterPos = plan.startChapterPosition
                hybridSegments = segments
                // 混合模式不使用 cue 队列（播放单元 = 分段）
                playbackQueue = ReadAloudPlaybackQueue.Empty
                // 播放单元表（文本 + 章节位置）——nextP/prevP/seek 依赖位置表
                replacePlaybackUnits(
                    segments.map { it.text },
                    positions.map { it as Int? },
                )
                nowSpeak = startIndex
                readAloudNumber = startChapterPos
                paragraphStartPos = 0

                val voiceParams = settings.voices[settings.defaultVoiceId]
                    ?: CustomTtsVoiceParams()
                val coordinator = HybridSynthesisCoordinator(
                    scope = lifecycleScope,
                    policy = HybridTtsPolicy(settings.hybrid),
                    remoteTimeoutMs = settings.hybrid.remoteTimeoutMs.toLong(),
                    remoteParams = voiceParams.copy(speed = settings.hybrid.customSpeed),
                    fallbackSpeed = settings.hybrid.fallbackSpeed,
                    remoteSynth = { segment, params ->
                        synthesizeRemoteSegment(segment, params, settings.hybrid.remoteTimeoutMs)
                    },
                    fallbackSynth = { segment, speed -> synthesizeFallbackSegment(segment, speed) },
                    log = { AppLog.putDebug(it) },
                    logError = { message, throwable -> AppLog.put(message, throwable) },
                    fallbackEnabled =
                        settings.hybrid.fallbackEngineType != ReadAloudVoice.ENGINE_SYSTEM,
                )
                hybridCoordinator = coordinator
                coordinator.start(segments)
                coordinator.prefetch(startIndex)
                AppLog.putDebug(
                    "混合朗读阶段: 设置 ${settingsMs}ms, 分段 ${buildMs}ms," +
                        " segments=${segments.size} start=$startIndex" +
                        " requestedStart=$requestedStart startPos=$startChapterPos" +
                        " firstTextLen=${segments[startIndex].text.length}"
                )
                AppLog.putDebug(
                    "混合朗读: segments=${segments.size} start=$startIndex" +
                        " defaultVoice=${settings.defaultVoiceId.ifEmpty { "<引擎默认>" }}" +
                        " firstFallback=${settings.hybrid.firstSegmentFallback}" +
                        " remoteTimeout=${settings.hybrid.remoteTimeoutMs}ms" +
                        " customSpeed=${settings.hybrid.customSpeed}" +
                        " fallbackSpeed=${settings.hybrid.fallbackSpeed}" +
                        " fallback=${settings.hybrid.fallbackEngineType}/" +
                        "${settings.hybrid.fallbackEngineId}/${settings.hybrid.fallbackSpeakerId}"
                )
                AppLog.putDebug(
                    "混合朗读: httpTts=${ReadAloud.httpTTS?.id}/${ReadAloud.httpTTS?.name}" +
                        " url=${ReadAloud.httpTTS?.url?.substringBefore("?")}" +
                        " gatewayPort=${RemoteLinkManager.currentPort}" +
                        " gatewayError=${RemoteLinkManager.lastError}"
                )
                hybridDriverJob?.cancel()
                hybridDriverJob = lifecycleScope.launch {
                    try {
                        runHybridDriver(
                            coordinator = coordinator,
                            segments = segments,
                            positions = positions,
                            startIndex = startIndex,
                            settings = settings,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.put("混合朗读出错\n${e.localizedMessage}", e, true)
                        pauseReadAloud()
                    }
                }
            }
        }.onError {
            AppLog.put("混合朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    private val isHybridDriverActive: Boolean
        get() = hybridDriverJob?.isActive == true

    /**
     * 混合朗读驱动：逐段播放，在**每段边界**决定用远端还是兜底。
     *
     * - 远端已就绪 → ExoPlayer 播远端文件；
     * - 未就绪且兜底是系统引擎 → `TextToSpeech.speak()` 直接朗读（不落文件，立即出声）；
     * - 未就绪且兜底是云/HTTP → ExoPlayer 播兜底文件。
     *
     * 远端在前一段播放期间预取，边界只做非阻塞判定；因此“播放下一段时远端好了就用远端，
     * 没好就用兜底”只有这一套逻辑。
     */
    private suspend fun runHybridDriver(
        coordinator: HybridSynthesisCoordinator,
        segments: List<CustomTtsSegment>,
        positions: List<Int>,
        startIndex: Int,
        settings: CustomTtsSettings,
    ) {
        val systemFallback = settings.hybrid.fallbackEngineType == ReadAloudVoice.ENGINE_SYSTEM
        for (index in startIndex..segments.lastIndex) {
            currentCoroutineContext().ensureActive()
            if (!isHybridPlaybackActive()) return
            // 段间停顿期间暂停：等待恢复，避免延迟结束后直接开始下一段（系统 TTS 无法 pause）。
            while (pause && isHybridPlaybackActive()) {
                delay(100)
            }
            if (!isHybridPlaybackActive()) return
            nowSpeak = index
            readAloudNumber = positions[index]
            paragraphStartPos = 0
            upMediaMetadata(showContent = true)

            // 预取后 2 段：远端有本段完整播放时间来完成。
            coordinator.prefetch(index + 1)
            coordinator.prefetch(index + 2)

            val remote = coordinator.remoteReady(index)
            val decision = coordinator.decide(index, remote != null)
            coordinator.drainSwitches().forEach { switch ->
                toastOnUi(
                    if (switch == HybridTtsSwitch.ToFallback) "已切换为兜底引擎"
                    else "已恢复定制引擎"
                )
            }

            val startedAt = System.currentTimeMillis()
            if (decision == HybridTtsDecision.Remote && remote != null) {
                AppLog.putDebug("混合TTS: 段 $index 使用远端 ${remote.name}")
                playHybridAudio(remote)
            } else if (systemFallback) {
                AppLog.putDebug(
                    "混合TTS: 段 $index 使用系统兜底 speak（remoteReady=${remote != null}）"
                )
                speakHybridSystem(index, segments[index], positions[index], settings)
            } else {
                val fallback = coordinator.fallbackFile(index)
                if (fallback != null) {
                    AppLog.putDebug("混合TTS: 段 $index 使用兜底文件 ${fallback.name}")
                    playHybridAudio(fallback)
                } else {
                    val fileName = md5SpeakFileName(segments[index].text)
                    createSilentSound(fileName)
                    AppLog.putDebug("混合TTS: 段 $index 兜底失败，使用无声")
                    playHybridAudio(getSpeakFileAsMd5(fileName))
                }
            }
            // 本段已结束：系统 TTS 不再占用，段间暂停走“保留驱动”的音频路径。
            hybridCurrentSystemTts = false
            AppLog.putDebug(
                "混合朗读阶段: 段 $index 播放完成 ${System.currentTimeMillis() - startedAt}ms"
            )

            val interval = readAloudSettings.ttsParagraphInterval.toLong()
            updateNextPos(naturalCompletion = true, skipUnreadable = false)
            if (!isHybridPlaybackActive()) return
            if (interval > 0 && index < segments.lastIndex) {
                delay(interval)
            }
        }
    }

    /** 播放一段本地音频，直到 STATE_ENDED（由播放器回调完成）。 */
    private suspend fun playHybridAudio(file: File) {
        hybridCurrentSystemTts = false
        playIndexJob?.cancel()
        val done = CompletableDeferred<Unit>()
        hybridAudioDone = done
        withContext(Main) {
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            exoPlayer.prepare()
            if (!pause) exoPlayer.play()
        }
        try {
            done.await()
        } finally {
            if (hybridAudioDone === done) hybridAudioDone = null
        }
        playIndexJob?.cancel()
    }

    /** 系统兜底：直接 TTS speak，不落文件；onRangeStart 驱动高亮。 */
    private suspend fun speakHybridSystem(
        index: Int,
        segment: CustomTtsSegment,
        startPosition: Int,
        settings: CustomTtsSettings,
    ) {
        hybridCurrentSystemTts = true
        playIndexJob?.cancel()
        withContext(Main) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }
        val speechRate = ReadAloudSpeed.systemTtsRate(
            globalTenths = readAloudSettings.ttsSpeechRate,
            traitsJson = null,
            override = settings.hybrid.fallbackSpeed,
            followSystem = false,
        )
        AppLog.putDebug(
            "混合TTS: 段 $index 系统兜底 speak rate=$speechRate" +
                " pos=$startPosition textLen=${segment.text.length}" +
                " globalPlayback=${exoPlayer.playbackParameters.speed}"
        )
        // 与普通系统朗读共用同一套进度契约（publishSystemTtsStart/Range）。
        publishSystemTtsStart(startPosition)
        // 按字数/倍速估算上限，避免长句或 0.5x 时被固定超时截断；15s 只作为下限。
        val timeoutMs = maxOf(
            HYBRID_SYSTEM_TTS_MIN_TIMEOUT_MS,
            (segment.text.length * 1000L / (2.0 * speechRate).coerceAtLeast(0.1)).toLong() +
                10_000L,
        )
        val ok = try {
            withTimeout(timeoutMs) {
                systemTtsFileSynthesizer.speak(
                    engine = settings.hybrid.fallbackEngineId,
                    voiceName = settings.hybrid.fallbackSpeakerId,
                    text = segment.text,
                    speechRate = speechRate,
                ) { offset ->
                    // 旧段迟到的 onRangeStart 不得污染当前段/当前章。
                    if (isHybridDriverActive && nowSpeak == index) {
                        val position = (startPosition + offset).coerceAtLeast(0)
                        paragraphStartPos = offset
                        publishSystemTtsRange(position)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            AppLog.put(
                "混合TTS: 系统兜底超时（${timeoutMs}ms）" +
                    " engine=${settings.hybrid.fallbackEngineId}" +
                    " textLen=${segment.text.length}"
            )
            false
        }
        AppLog.putDebug("混合TTS: 段 $index 系统兜底 speak 结束 ok=$ok")
        if (!ok) {
            AppLog.put(
                "混合TTS: 系统兜底朗读失败 engine=${settings.hybrid.fallbackEngineId}" +
                    " textLen=${segment.text.length}"
            )
        }
    }

    /** 远程段合成（本地网关→P2P→服务端；缓存键含参数）。 */
    private suspend fun synthesizeRemoteSegment(
        segment: CustomTtsSegment,
        params: CustomTtsVoiceParams,
        requestTimeoutMs: Int,
    ): File? {
        val httpTts = ReadAloud.httpTTS
        if (httpTts == null) {
            AppLog.put("混合TTS: 远程合成分派失败——ReadAloud.httpTTS 为空")
            return null
        }
        val sourceKey = "custom:${params.voiceId}:${GSON.toJson(params)}"
        val fileName = md5SpeakFileName(segment.text, httpTts = httpTts, sourceKey = sourceKey)
        if (hasSpeakFile(fileName)) {
            AppLog.putDebug("混合TTS: 远程缓存命中 ${segment.text.length}字 -> $fileName")
            return getSpeakFileAsMd5(fileName)
        }
        val stream = try {
            getSpeakStream(
                httpTts, segment.text, params, requestTimeoutMs = requestTimeoutMs)
        } catch (e: CancellationException) {
            // 章节切换/停止：不是合成失败，向上传播。
            throw e
        } catch (e: Exception) {
            AppLog.put(
                "混合TTS: 远程请求失败 voice=${params.voiceId} " +
                    "textLen=${segment.text.length}: ${e.localizedMessage}",
                e,
            )
            null
        }
        if (stream == null) {
            AppLog.putDebug(
                "混合TTS: 远程请求无响应 voice=${params.voiceId} textLen=${segment.text.length}"
            )
            return null
        }
        val file = writeAudioAtomically(fileName, stream)
        AppLog.putDebug(
            "混合TTS: 远程写入 voice=${params.voiceId} textLen=${segment.text.length}" +
                " -> ${file?.let { "${it.name}(${it.length()}B)" } ?: "失败"}"
        )
        return file
    }

    /** 音频原子写（临时文件 + rename）：失败不留半截文件被缓存误命中。 */
    private fun writeAudioAtomically(fileName: String, input: InputStream): File? {
        val output = getSpeakFileAsMd5(fileName)
        val tmp = File(output.parentFile, "${output.name}.tmp")
        return runCatching {
            tmp.outputStream().use { out -> input.use { it.copyTo(out) } }
            if (!tmp.renameTo(output)) {
                tmp.copyTo(output, overwrite = true)
                tmp.delete()
            }
            output
        }.getOrElse {
            runCatching { tmp.delete() }
            null
        }
    }

    /** 兜底段合成：系统/HTTP/云引擎分派（速度覆盖由调用方给出）。 */
    private suspend fun synthesizeFallbackSegment(
        segment: CustomTtsSegment,
        speedOverride: Float,
    ): File? {
        val hybrid = customTtsSettingsGateway.currentSettings.hybrid
        val engineType = hybrid.fallbackEngineType
        val engineId = hybrid.fallbackEngineId
        val speakerId = hybrid.fallbackSpeakerId
        // v2：HTTP 源速度刻度从 (v*10-5) 修正为 v*10，旧缓存必须失效。
        val sourceKey = "fallback:v2:$engineType:$engineId:$speakerId:$speedOverride"
        val fileName = md5SpeakFileName(segment.text, sourceKey = sourceKey)
        if (hasSpeakFile(fileName)) {
            AppLog.putDebug("混合TTS: 兜底缓存命中 engine=$engineType textLen=${segment.text.length}")
            return getSpeakFileAsMd5(fileName)
        }
        AppLog.putDebug(
            "混合TTS: 兜底合成开始 engine=$engineType/$engineId/$speakerId" +
                " speed=$speedOverride textLen=${segment.text.length}"
        )
        val output = getSpeakFileAsMd5(fileName)
        val ok = runCatching {
            when (engineType) {
                ReadAloudVoice.ENGINE_SYSTEM -> systemTtsFileSynthesizer.synthesize(
                    engineId,
                    speakerId,
                    segment.text,
                    output,
                    ReadAloudSpeed.systemTtsRate(
                        globalTenths = readAloudSettings.ttsSpeechRate,
                        traitsJson = null,
                        override = speedOverride,
                    ),
                    1f,
                )

                ReadAloudVoice.ENGINE_CLOUD -> {
                    val voice = GlobalContext.get()
                        .get<io.legado.app.domain.gateway.ReadAloudVoiceGateway>()
                        .getEnabledVoices()
                        .firstOrNull {
                            it.engineType == engineType && it.engineId == engineId &&
                                it.speakerId == speakerId
                        } ?: return@runCatching false
                    cloudTtsAudioSynthesizer.synthesize(
                        voice, segment.text, output, speedOverride = speedOverride)
                }

                else -> {
                    val http = engineId.toLongOrNull()?.let(appDb.httpTTSDao::get)
                        ?: return@runCatching false
                    // 兜底速度（倍速）→ HTTP 书源源参数刻度；转换只在 ReadAloudSpeed 一处。
                    val speedScale = ReadAloudSpeed.httpSourceSpeed(speedOverride)
                    val stream = getSpeakStream(
                        http, segment.text, speakSpeedOverride = speedScale)
                        ?: return@runCatching false
                    stream.use { input -> createSpeakFile(fileName, input) }
                    true
                }
            }
        }.getOrDefault(false)
        if (!ok) {
            AppLog.put("混合TTS: 兜底合成失败 engine=$engineType/$engineId/$speakerId textLen=${segment.text.length}")
            runCatching { output.delete() } // 防半文件被缓存误命中
            return null
        }
        writeTextIndexEntry(fileName, segment.text)
        AppLog.putDebug("混合TTS: 兜底合成完成 engine=$engineType -> ${output.name}(${output.length()}B)")
        return output
    }

    /**
     * 异步启动后续章节的预合成，与当前章节播放并行。
     * 不持有 downloadTaskActiveLock，不阻塞当前章节的合成和播放。
     */
    private fun launchPreDownload(httpTts: HttpTTS) {
        preDownloadJob?.cancel()
        preDownloadJob = lifecycleScope.launch {
            preDownloadAudios(httpTts)
        }
    }

    private suspend fun getPreDownloadChapter(
        book: Book,
        chapter: BookChapter,
    ): PreDownloadChapter? {
        val content = BookHelp.getContent(book, chapter) ?: return null
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val displayTitle = chapter.getDisplayTitle(
            contentProcessor.getTitleReplaceRules(),
            book.getUseReplaceRule(otherSettings.replaceEnableDefault),
            chineseConverterType = readSettings.chineseConverterType,
        )
        val processedContent = contentProcessor.getContent(
            book,
            chapter,
            content,
            includeTitle = false,
        )
        val source = ReaderChapterSourceParser.parse(
            chapterIndex = chapter.index,
            title = displayTitle,
            paragraphs = processedContent.textList,
            includeTitle = false,
            adaptSpecialStyle = readSettings.adaptSpecialStyle,
            htmlSemanticTextResolver = AndroidReaderHtmlSemanticTextResolver,
        )
        // 预合成必须与实时朗读用同一种划分方式解析，否则预合成好的音频与实际朗读单元对不上
        val contentSplitMode = resolveContentSplitMode(
            ReadAloudContentSplitMode.fromStorage(readAloudSettings.contentSplitMode)
        )
        val readAloudChapter = ReaderReadAloudChapter.create(
            chapterIndex = chapter.index,
            title = displayTitle,
            semanticContent = source.semanticContent,
            pageStarts = ReadBook.readerPagination(chapter.index)?.pageStarts.orEmpty(),
            contentSplitMode = contentSplitMode,
        )
        val splitPolicy = contentSplitPolicy(contentSplitMode)
        val splitByPage = contentSplitMode == ReadAloudContentSplitMode.Page
        val plan = buildSpeechPlan(
            bookUrl = book.bookUrl,
            chapterIndex = chapter.index,
            paragraphs = readAloudChapter.canonicalSpeechParagraphs(splitByPage, splitPolicy),
            splitPolicy = splitPolicy,
        )
        val queue = runCatching { ReadAloudPlaybackQueue.from(plan).withChapterTitle(displayTitle) }
            .getOrDefault(ReadAloudPlaybackQueue.Empty)
        val contentList = if (!queue.isEmpty) {
            queue.cues.map { it.text }
        } else {
            listOf(displayTitle.trim()).filter { it.isNotEmpty() } +
                    readAloudChapter.paragraphs(splitByPage, splitPolicy)
                        .map { it.text.replace(Regex("[袮祢꧁\uFFFC]"), " ") }
        }
        return PreDownloadChapter(displayTitle, queue, contentList)
    }

    private suspend fun preDownloadAudios(httpTts: HttpTTS) {
        val book = ReadBook.book ?: return
        val currentIdx = ReadBook.durChapterIndex
        val limit = readAloudSettings.audioPreDownloadNum
        val concurrency = readAloudSettings.ttsPreSynthesisConcurrency.coerceIn(1, 8)
        var consecutiveFailures = 0

        try {
            for (i in 1..limit) {
                currentCoroutineContext().ensureActive()
                if (consecutiveFailures >= 3) {
                    AppLog.put("TTS预合成连续失败${consecutiveFailures}章，已停止预合成")
                    break
                }
                val targetIndex = currentIdx + i
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, targetIndex) ?: break
                val prepared = getPreDownloadChapter(book, chapter) ?: continue
                val chapterFailed = synthesizeChapterCues(prepared, httpTts, concurrency)
                consecutiveFailures = if (chapterFailed) consecutiveFailures + 1 else 0
            }
        } catch (e: Exception) {
            AppLog.put("听书预下载异常: ${e.localizedMessage}", e)
        }
    }

    /**
     * 并行合成一个章节的所有 cue，通过 Semaphore 控制并发。
     * 返回 true 表示该章节合成失败（超过半数 cue 失败）。
     */
    private suspend fun synthesizeChapterCues(
        prepared: PreDownloadChapter,
        httpTts: HttpTTS,
        concurrency: Int,
    ): Boolean = coroutineScope {
        val semaphore = kotlinx.coroutines.sync.Semaphore(concurrency)
        var failedCount = 0
        val totalCues = prepared.contentList.size

        prepared.contentList.mapIndexed { index, content ->
            async {
                semaphore.acquire()
                try {
                    val routedVoice = voiceForCue(prepared.queue, index, httpTts)
                    if (routedVoice.engineType == ReadAloudVoice.ENGINE_SYSTEM) {
                        return@async
                    }
                    val cue = prepared.queue.cues.getOrNull(index)
                    val sourceKey = sourceKeyForCue(routedVoice, cue, httpTts)
                    val fileName = md5SpeakFileName(
                        content, prepared.chapterTitle, sourceKey = sourceKey,
                    )
                    if (hasSpeakFile(fileName)) return@async

                    val success = synthesizeSingleCueWithRetry(
                        routedVoice, cue, content, prepared.chapterTitle, httpTts,
                    )
                    if (!success) {
                        createSilentSound(fileName)
                        failedCount++
                    }
                } finally {
                    semaphore.release()
                }
            }
        }.awaitAll()

        failedCount > totalCues / 2
    }

    /**
     * 单个 cue 合成 + 重试 1 次（500ms 延迟）
     */
    private suspend fun synthesizeSingleCueWithRetry(
        routedVoice: ReadAloudVoice,
        cue: io.legado.app.domain.model.readaloud.ReadAloudPlaybackCue?,
        content: String,
        chapterTitle: String,
        httpTts: HttpTTS,
    ): Boolean {
        if (synthesizeSingleCue(routedVoice, cue, content, chapterTitle, httpTts)) {
            return true
        }
        delay(500)
        return synthesizeSingleCue(routedVoice, cue, content, chapterTitle, httpTts)
    }

    /**
     * 单个 cue 合成核心方法
     */
    private suspend fun synthesizeSingleCue(
        routedVoice: ReadAloudVoice,
        cue: io.legado.app.domain.model.readaloud.ReadAloudPlaybackCue?,
        content: String,
        chapterTitle: String,
        httpTts: HttpTTS,
    ): Boolean {
        val sourceKey = sourceKeyForCue(routedVoice, cue, httpTts)
        val fileName = md5SpeakFileName(content, chapterTitle, sourceKey = sourceKey)
        val speakText = content.replace(AppPattern.notReadAloudRegex, "")
        if (speakText.isEmpty()) {
            createSilentSound(fileName)
            return true
        }
        val success = runCatching {
            when (routedVoice.engineType) {
                ReadAloudVoice.ENGINE_CLOUD -> {
                    val output = getSpeakFileAsMd5(fileName)
                    cloudTtsAudioSynthesizer.synthesize(
                        routedVoice, speakText, output,
                        styleOverride = cue?.emotion.orEmpty(),
                        characterPerformance = cue?.characterPerformance,
                        roleType = cue?.roleType ?: SpeechRoleType.Unknown,
                    )
                }

                ReadAloudVoice.ENGINE_HTTP -> {
                    val itemHttpTts = routedVoice.engineId.toLongOrNull()
                        ?.let(appDb.httpTTSDao::get) ?: httpTts
                    val inputStream = getSpeakStream(
                        itemHttpTts, speakText, customParamsFor(routedVoice))
                    if (inputStream != null) {
                        createSpeakFile(fileName, inputStream)
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }.getOrElse {
            when (it) {
                is CancellationException -> throw it
                else -> {
                    AppLog.put("TTS预合成cue失败: ${it.localizedMessage}")
                    false
                }
            }
        }
        if (success && speakText.isNotEmpty()) {
            writeTextIndexEntry(fileName, speakText)
        }
        return success
    }

    /**
     * 将合成的文件名和对应文字写入索引，供缓存管理界面显示。
     */
    private fun writeTextIndexEntry(fileName: String, text: String) {
        try {
            val baseDir = externalCacheDir ?: cacheDir
            val indexFile = File(baseDir, "httpTTS/tts_cache_index.json")
            val index = mutableMapOf<String, String>()
            if (indexFile.exists()) {
                val json = indexFile.readText()
                val regex = Regex("\"([^\"]+)\":\"([^\"]*)\"")
                regex.findAll(json).forEach { match ->
                    index[match.groupValues[1]] = match.groupValues[2]
                        .replace("\\n", "\n")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                }
            }
            val shortText = if (text.length > 200) text.substring(0, 200) + "…" else text
            index[fileName] = shortText
            // 限制索引大小，最多保留 2000 条
            if (index.size > 2000) {
                val keys = index.keys.toList().takeLast(2000)
                val trimmed = linkedMapOf<String, String>()
                keys.forEach { trimmed[it] = index[it]!! }
                indexFile.writeText(buildIndexJson(trimmed))
            } else {
                indexFile.writeText(buildIndexJson(index))
            }
        } catch (_: Exception) {
        }
    }

    private fun buildIndexJson(index: Map<String, String>): String = buildString {
        append("{")
        index.entries.forEachIndexed { i, (key, value) ->
            if (i > 0) append(",")
            val escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
            append("\"$key\":\"$escaped\"")
        }
        append("}")
    }

    private fun downloadAndPlayAudiosStream() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        preDownloadJob?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val downloaderChannel = Channel<Downloader>()
                launch {
                    for (downloader in downloaderChannel) {
                        downloader.download(null)
                    }
                }
                var preDownloadLaunched = false
                contentList.forEachIndexed { index, content ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    var text = content
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                    if (speakText.isEmpty()) {
                        AppLog.put("阅读段落内容为空，使用无声音频代替。段落长度=${speakText.length}")
                    }
                    val routedVoice = voiceForCue(playbackQueue, index, httpTts)
                    val itemHttpTts = httpTtsForCue(index, httpTts)
                    val cue = playbackQueue.cues.getOrNull(index)
                    val sourceKey = sourceKeyForCue(routedVoice, cue, itemHttpTts)
                    val fileName = md5SpeakFileName(
                        text, httpTts = itemHttpTts, sourceKey = sourceKey)
                    val dataSourceFactory = createDataSourceFactory(
                        itemHttpTts, speakText, customParamsFor(routedVoice))
                    val downloader = createDownloader(dataSourceFactory, fileName)
                    downloaderChannel.send(downloader)
                    val mediaSource = createMediaSource(dataSourceFactory, fileName)
                    launch(Main) {
                        if (readAloudSettings.ttsParagraphInterval > 0) {
                            if (index == nowSpeak && exoPlayer.mediaItemCount == 0) {
                                exoPlayer.setMediaSource(mediaSource)
                                if (!pause) {
                                    exoPlayer.prepare()
                                }
                                if (!preDownloadLaunched) {
                                    preDownloadLaunched = true
                                    launchPreDownloadStream(httpTts, downloaderChannel)
                                }
                            }
                        } else {
                            if (exoPlayer.mediaItemCount == 0) {
                                exoPlayer.setMediaSource(mediaSource)
                                if (!pause) {
                                    exoPlayer.prepare()
                                }
                                if (!preDownloadLaunched) {
                                    preDownloadLaunched = true
                                    launchPreDownloadStream(httpTts, downloaderChannel)
                                }
                            } else {
                                exoPlayer.addMediaSource(mediaSource)
                            }
                        }
                    }
                }
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun launchPreDownloadStream(httpTts: HttpTTS, downloaderChannel: Channel<Downloader>) {
        preDownloadJob?.cancel()
        preDownloadJob = lifecycleScope.launch {
            preDownloadAudiosStream(httpTts, downloaderChannel)
        }
    }

    private suspend fun preDownloadAudiosStream(
        httpTts: HttpTTS,
        downloaderChannel: Channel<Downloader>
    ) {
        val book = ReadBook.book ?: return
        val currentIdx = ReadBook.durChapterIndex
        val limit = readAloudSettings.audioPreDownloadNum
        val concurrency = readAloudSettings.ttsPreSynthesisConcurrency.coerceIn(1, 8)
        var consecutiveFailures = 0

        try {
            for (i in 1..limit) {
                currentCoroutineContext().ensureActive()
                if (consecutiveFailures >= 3) {
                    AppLog.put("TTS流式预合成连续失败${consecutiveFailures}章，已停止")
                    break
                }
                val targetIndex = currentIdx + i
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, targetIndex) ?: break
                val prepared = getPreDownloadChapter(book, chapter) ?: continue
                val chapterFailed = synthesizeChapterCuesStream(
                    prepared, httpTts, concurrency, downloaderChannel,
                )
                consecutiveFailures = if (chapterFailed) consecutiveFailures + 1 else 0
            }
        } catch (e: Exception) {
            AppLog.put("听书流式预下载异常: ${e.localizedMessage}", e)
        }
    }

    /**
     * 流式模式下并行预合成一个章节的 cue
     */
    private suspend fun synthesizeChapterCuesStream(
        prepared: PreDownloadChapter,
        httpTts: HttpTTS,
        concurrency: Int,
        downloaderChannel: Channel<Downloader>,
    ): Boolean = coroutineScope {
        val semaphore = kotlinx.coroutines.sync.Semaphore(concurrency)
        var failedCount = 0
        val totalCues = prepared.contentList.size

        prepared.contentList.mapIndexed { index, content ->
            async {
                semaphore.acquire()
                try {
                    val routedVoice = voiceForCue(prepared.queue, index, httpTts)
                    if (routedVoice.engineType == ReadAloudVoice.ENGINE_SYSTEM) {
                        return@async
                    }
                    val cue = prepared.queue.cues.getOrNull(index)
                    if (routedVoice.engineType == ReadAloudVoice.ENGINE_CLOUD) {
                        val sourceKey = sourceKeyForCue(routedVoice, cue, httpTts)
                        val fileName = md5SpeakFileName(
                            content, prepared.chapterTitle, sourceKey = sourceKey,
                        )
                        if (hasSpeakFile(fileName)) return@async
                        val success = synthesizeSingleCueWithRetry(
                            routedVoice, cue, content, prepared.chapterTitle, httpTts,
                        )
                        if (!success) {
                            createSilentSound(fileName)
                            failedCount++
                        }
                    } else {
                        val speakText = content.replace(AppPattern.notReadAloudRegex, "")
                        val sourceKey = sourceKeyForCue(routedVoice, cue, httpTts)
                        val fileName = md5SpeakFileName(
                            content, prepared.chapterTitle, sourceKey = sourceKey,
                        )
                        val dataSourceFactory = createDataSourceFactory(
                            httpTts, speakText, customParamsFor(routedVoice))
                        val downloader = createDownloader(dataSourceFactory, fileName)
                        downloaderChannel.send(downloader)
                    }
                } finally {
                    semaphore.release()
                }
            }
        }.awaitAll()

        failedCount > totalCues / 2
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS,
        speakText: String,
        voiceParams: CustomTtsVoiceParams? = null,
    ): CacheDataSource.Factory {
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                if (speakText.isEmpty()) {
                    null
                } else {
                    kotlin.runCatching {
                        runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                            getSpeakStream(httpTts, speakText, voiceParams)
                        }
                    }.onFailure {
                        when (it) {
                            is InterruptedException,
                            is CancellationException -> Unit

                            else -> pauseReadAloud()
                        }
                    }.getOrThrow()
                } ?: resources.openRawResource(R.raw.silent_sound)
            }
        }
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
        return factory
    }

    private fun createDownloader(factory: CacheDataSource.Factory, fileName: String): Downloader {
        val uri = fileName.toUri()
        val request = DownloadRequest.Builder(fileName, uri).build()
        return DefaultDownloaderFactory(factory, okHttpClient.dispatcher.executorService)
            .createDownloader(request)
    }

    private fun createMediaSource(factory: DataSource.Factory, fileName: String): MediaSource {
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(MediaItem.fromUri(fileName))
    }

    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String,
        voiceParams: CustomTtsVoiceParams? = null,
        speakSpeedOverride: Int? = null,
        requestTimeoutMs: Int? = null,
    ): InputStream? {
        while (true) {
            try {
                val url = if (voiceParams != null) {
                    CustomTtsUrlBuilder.buildCustomTtsUrl(
                        httpTts.url, speakText, voiceParams, requestTimeoutMs)
                } else {
                    httpTts.url
                }
                val analyzeUrl = AnalyzeUrl(
                    url,
                    speakText = speakText,
                    speakSpeed = speakSpeedOverride ?: ((httpTts.speed ?: DEFAULT_TTS_SPEED) + 5),
                    source = httpTts,
                    readTimeout = 300 * 1000L,
                    coroutineContext = currentCoroutineContext()
                )
                var response = analyzeUrl.getResponseAwait()
                currentCoroutineContext().ensureActive()
                if (voiceParams != null) {
                    AppLog.putDebug(
                        "混合TTS: 远程请求响应 status=${response.code}" +
                            " type=${response.headers["Content-Type"]}" +
                            " len=${response.headers["Content-Length"]}" +
                            " url=${url.substringBefore("?")}" +
                            " textLen=${speakText.length}"
                    )
                }
                val checkJs = httpTts.loginCheckJs
                if (checkJs?.isNotBlank() == true) {
                    response = analyzeUrl.evalJS(checkJs, response) as Response
                }
                response.headers["Content-Type"]?.let { contentType ->
                    val contentType = contentType.substringBefore(";")
                    val ct = httpTts.contentType
                    if (contentType == "application/json" || contentType.startsWith("text/")) {
                        throw NoStackTraceException(response.body.string())
                    } else if (ct?.isNotBlank() == true) {
                        if (!contentType.matches(ct.toRegex())) {
                            throw NoStackTraceException(
                                "TTS服务器返回错误：" + response.body.string()
                            )
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                response.body.byteStream().let { stream ->
                    downloadErrorNo = 0
                    consecutiveErrorToasted.set(false)
                    return stream
                }
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        AppLog.put("js错误\n${e.localizedMessage}", e, true)
                        e.printOnDebug()
                        throw e
                    }

                    is SocketTimeoutException, is ConnectException -> {
                        downloadErrorNo++
                        if (downloadErrorNo > 5) {
                            val msg = "tts超时或连接错误超过5次\n${e.localizedMessage}"
                            AppLog.put(msg, e, toast = consecutiveErrorToasted.compareAndSet(false, true))
                            throw e
                        }
                    }

                    else -> {
                        downloadErrorNo++
                        val msg = "tts下载错误\n${e.localizedMessage}"
                        AppLog.put(msg, e)
                        e.printOnDebug()
                        if (downloadErrorNo > 5) {
                            val msg1 = "TTS服务器连续5次错误，已暂停阅读。"
                            AppLog.put(msg1, e, toast = consecutiveErrorToasted.compareAndSet(false, true))
                            throw e
                        } else {
                            AppLog.put("TTS下载音频出错，使用无声音频代替。段落长度=${speakText.length}")
                            break
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * 生成音频文件名
     */
    private fun md5SpeakFileName(
        content: String,
        chapterTitle: String = readerReadAloudChapter?.title.orEmpty(),
        httpTts: HttpTTS? = ReadAloud.httpTTS,
        sourceKey: String = httpTts?.url.orEmpty(),
    ): String {
        return MD5Utils.md5Encode16(chapterTitle) + "_" +
                MD5Utils.md5Encode16("$sourceKey-|-$speechRate-|-$content")
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private fun hasFileSynthesisCue(): Boolean = playbackQueue.cues.indices.any { index ->
        voiceForCue(playbackQueue, index, ReadAloud.httpTTS ?: return@any false).engineType in
            setOf(
                ReadAloudVoice.ENGINE_SYSTEM,
                ReadAloudVoice.ENGINE_CLOUD,
            )
    }

    /**
     * 根据引擎类型生成用于文件名和缓存的 sourceKey
     */
    /** 定制引擎的 voice 级参数（非定制引擎返回 null——原路径零改动）。 */
    private fun customParamsFor(routedVoice: ReadAloudVoice): CustomTtsVoiceParams? {
        if (routedVoice.engineType != ReadAloudVoice.ENGINE_HTTP) return null
        if (routedVoice.engineId != CustomTtsCatalog.ENGINE_ID.toString()) return null
        return runCatching {
            GSON.fromJson(routedVoice.traitsJson, CustomTtsVoiceParams::class.java)
        }.getOrNull()
    }

    private fun sourceKeyForCue(
        routedVoice: ReadAloudVoice,
        cue: io.legado.app.domain.model.readaloud.ReadAloudPlaybackCue?,
        httpTts: HttpTTS,
    ): String {
        val cueEmotion = cue?.emotion.orEmpty()
        val characterPerformance = cue?.characterPerformance
        val cueRoleType = cue?.roleType ?: SpeechRoleType.Unknown
        return when (routedVoice.engineType) {
            ReadAloudVoice.ENGINE_SYSTEM ->
                "system:${routedVoice.id}:${routedVoice.revision}:${routedVoice.engineId}:${routedVoice.speakerId}"

            ReadAloudVoice.ENGINE_CLOUD ->
                "cloud:${routedVoice.id}:${routedVoice.revision}:" +
                        "${CloudTtsEmotionMapper.VERSION}:$cueEmotion:" +
                        "${CharacterPerformanceInstructionBuilder.VERSION}:" +
                        "${characterPerformance?.characterId.orEmpty()}:" +
                        "${characterPerformance?.updatedAt.orZero()}:" +
                        "${CloudTtsRoleInstructionMapper.VERSION}:" +
                        cueRoleType.storageValue

            else -> {
                val itemHttpTts = routedVoice.engineId.toLongOrNull()
                    ?.let(appDb.httpTTSDao::get) ?: httpTts
                if (itemHttpTts.id == CustomTtsCatalog.ENGINE_ID) {
                    // 定制引擎: 参数必须进入缓存键（否则换参数后回放旧音频）
                    "custom:${routedVoice.speakerId}:${routedVoice.traitsJson}"
                } else {
                    itemHttpTts.url
                }
            }
        }
    }

    private fun voiceForCue(
        queue: ReadAloudPlaybackQueue,
        index: Int,
        default: HttpTTS,
    ): ReadAloudVoice {
        val cue = queue.cues.getOrNull(index)
            ?: return ReadAloudVoice(
                id = "runtime-http:${default.id}",
                engineType = ReadAloudVoice.ENGINE_HTTP,
                engineId = default.id.toString(),
                speakerId = "",
                displayName = default.name,
            )
        return SpeechVoiceRouter.route(
            cue = cue,
            supportedEngineTypes = setOf(
                ReadAloudVoice.ENGINE_HTTP,
                ReadAloudVoice.ENGINE_SYSTEM,
                ReadAloudVoice.ENGINE_CLOUD,
            ),
            defaultRoute = SpeechEngineRoute(
                engineType = ReadAloud.coordinatorDefaultEngineType,
                engineId = ReadAloud.coordinatorDefaultEngineId,
                speakerId = ReadAloud.coordinatorDefaultSpeakerId,
            ),
        ).voice!!
    }

    private fun httpTtsForCue(index: Int, default: HttpTTS): HttpTTS {
        return httpTtsForCue(playbackQueue, index, default)
    }

    private fun httpTtsForCue(
        queue: ReadAloudPlaybackQueue,
        index: Int,
        default: HttpTTS,
    ): HttpTTS {
        val cue = queue.cues.getOrNull(index) ?: return default
        val routed = SpeechVoiceRouter.route(
            cue = cue,
            supportedEngineTypes = setOf(ReadAloudVoice.ENGINE_HTTP),
            defaultRoute = SpeechEngineRoute(
                engineType = ReadAloudVoice.ENGINE_HTTP,
                engineId = default.id.toString(),
            ),
        ).voice ?: return default
        val id = routed.engineId.toLongOrNull() ?: return default
        return appDb.httpTTSDao.get(id) ?: default
    }

    private fun createSilentSound(fileName: String) {
        val file = createSpeakFile(fileName)
        file.writeBytes(resources.openRawResource(R.raw.silent_sound).readBytes())
    }

    private fun hasSpeakFile(name: String): Boolean {
        return FileUtils.exist("${ttsFolderPath}$name.mp3")
    }

    private fun getSpeakFileAsMd5(name: String): File {
        return File("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String): File {
        return FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String, inputStream: InputStream) {
        FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3").outputStream().use { out ->
            inputStream.use {
                it.copyTo(out)
            }
        }
    }

    /**
     * 移除缓存文件
     * 如果时间设置为0，则不再保护当前章节，退出即全删。
     */
    private fun removeCacheFile() {
        val keepTime = readAloudSettings.audioCacheCleanTime * 60 * 1000L
        // 只有当时间大于0时，才需要保护当前章节。如果为0，说明用户想彻底不留缓存。
        val protectCurrentChapter = keepTime > 0
        val titleMd5 = if (protectCurrentChapter) MD5Utils.md5Encode16(readerReadAloudChapter?.title.orEmpty()) else ""

        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
            val isSilentSound = it.length() == 2160L

            // 判断逻辑：
            // 1. 如果是无声文件 -> 删
            // 2. 如果保留时间设为0 -> 删 (不管是不是当前章节)
            // 3. 如果保留时间>0 -> 保护当前章节，且只删过期的
            val shouldDelete = if (keepTime == 0L) {
                // 模式：即听即焚 (保留时间0)
                true
            } else {
                // 模式：保留一段时间
                // 条件：(不是当前章节) 且 (时间过期了)
                !it.name.startsWith(titleMd5) && (System.currentTimeMillis() - it.lastModified() > keepTime)
            }

            if (shouldDelete || isSilentSound) {
                FileUtils.delete(it.absolutePath)
            }
        }
    }


    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        if (isHybridDriverActive && hybridCurrentSystemTts) {
            // 系统 TTS 不支持暂停续播：取消驱动并停止当前朗读，恢复时按当前词位置重建。
            // ExoPlayer 播放的远端/兜底文件则保留驱动，pause()/play() 从暂停点继续。
            hybridDriverJob?.cancel()
            hybridDriverJob = null
            systemTtsFileSynthesizer.stopSpeaking()
        }
        kotlin.runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        if (isCustomHybridMode() && !isHybridDriverActive) {
            // 混合模式暂停时驱动已被取消：按 readAloudNumber + paragraphStartPos 重建。
            play()
            return
        }
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                exoPlayer.play()
                upPlayPos()
            }
        }
    }

    private fun upPlayPos() {
        playIndexJob?.cancel()
        if (readerReadAloudChapter == null) return
        playIndexJob = lifecycleScope.launch {
            if (isChapterTitleAt(nowSpeak)) return@launch
            if (exoPlayer.duration <= 0) {
                upTtsProgress(readAloudNumber + paragraphStartPos + 1)
                return@launch
            }
            val startOffset = paragraphStartPos
            val speakTextLength = contentList[nowSpeak].length - startOffset
            if (speakTextLength <= 0) {
                return@launch
            }
            // 媒体时长是 1x 口径；ExoPlayer 按全局倍速播放时，字符推进必须同步加速，
            // 否则高亮会按 1x 速度落后于音频。
            val playbackSpeed = exoPlayer.playbackParameters.speed.coerceAtLeast(0.1f)
            val sleep = (exoPlayer.duration / speakTextLength / playbackSpeed)
                .toLong()
                .coerceAtLeast(1L)
            val start = httpReadAloudParagraphOffset(
                contentList[nowSpeak].length, startOffset,
                exoPlayer.currentPosition, exoPlayer.duration,
            )
            upTtsProgress(readAloudNumber + start)
            for (i in start until contentList[nowSpeak].length) {
                val chapterPosition = readAloudNumber + i
                updateReadAloudProgressSnapshot(chapterPosition)
                if (moveToReadAloudPage(chapterPosition)) {
                    upTtsProgress(chapterPosition)
                }
                delay(sleep)
            }
        }
    }

    /**
     * 更新朗读速度
     * 全局语速已改为播放端 (ExoPlayer) 变速, 对已合成音频即时生效, 无需重新下载。
     */
    override fun upSpeechRate(reset: Boolean) {
        exoPlayer.setPlaybackSpeed(effectivePlaybackSpeed)
        upMediaMetadata()
        // 倍速变化后按新速度重启字符进度推进，避免高亮继续用旧 sleep。
        upPlayPos()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        if (playbackState == Player.STATE_ENDED && isHybridDriverActive) {
            // 混合驱动按段播放：完成当前段，由驱动决定下一段用远端还是兜底。
            // 先取出并置空再完成：Main.immediate 下 complete 会同步恢复驱动，
            // 驱动会立刻给 hybridAudioDone 赋新值；先置空可避免把新值清掉。
            val done = hybridAudioDone
            hybridAudioDone = null
            done?.complete(Unit)
            return
        }
        when (playbackState) {
            Player.STATE_IDLE -> {
                // 空闲
            }

            Player.STATE_BUFFERING -> {
                // 缓冲中
            }

            Player.STATE_READY -> {
                // 准备好
                if (pause) return
                exoPlayer.play()
                upPlayPos()
            }

            Player.STATE_ENDED -> {
                // 结束
                playErrorNo = 0
                val interval = readAloudSettings.ttsParagraphInterval.toLong()
                if (interval > 0) {
                    val isLastParagraph = nowSpeak >= contentList.lastIndex
                    updateNextPos(naturalCompletion = true, skipUnreadable = false)
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    if (!pause && !isLastParagraph) {
                        AppLog.putDebug("HttpTTS段落开始停顿: $interval 毫秒")
                        paragraphIntervalJob?.cancel()
                        paragraphIntervalJob = execute {
                            delay(interval)
                            if (!pause) {
                                launch(Main) {
                                    if (!pause) {
                                        play()
                                        AppLog.putDebug("HttpTTS段落停顿结束，恢复播放")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    updateNextPos(naturalCompletion = true, skipUnreadable = false)
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                }
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }

            else -> {}
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
        }
        // 混合驱动自己按段推进（上一段播完后才决定下一段），这里不能再 updateNextPos，
        // 否则会双跳段。非混合路径保持原行为。
        if (!isHybridDriverActive) {
            updateNextPos(naturalCompletion = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, skipUnreadable = false)
        }
        upPlayPos()
        upMediaMetadata(showContent = true)
    }

    override fun onPlayerError(error: PlaybackException) {
        if (isHybridDriverActive) {
            // 单段音频失败不应中断整章：完成本段，驱动会继续下一段/兜底。
            AppLog.put("混合TTS: 段音频播放错误，跳过: ${error.localizedMessage}", error)
            // 先取出并置空再完成：Main.immediate 下 complete 会同步恢复驱动，
            // 驱动会立刻给 hybridAudioDone 赋新值；先置空可避免把新值清掉。
            val done = hybridAudioDone
            hybridAudioDone = null
            done?.complete(Unit)
            return
        }
        super.onPlayerError(error)
        AppLog.put("朗读错误", error)
        deleteCurrentSpeakFile()
        playErrorNo++
        if (playErrorNo >= 5) {
            toastOnUi("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})")
            AppLog.put("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})", error)
            pauseReadAloud()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else {
                exoPlayer.clearMediaItems()
                updateNextPos(naturalCompletion = true, skipUnreadable = false)
            }
        }
    }

    private fun deleteCurrentSpeakFile() {
        if (readAloudSettings.streamReadAloudAudio) {
            return
        }
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration!!.uri.path!!
        File(filePath).delete()
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<HttpReadAloudService>(actionStr)
    }

    class CustomLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            return C.TIME_UNSET
        }
    }

}

/** 音频仅合成定位点后的文字，其时间轴须映射到裁剪后的段落范围。 */
internal fun httpReadAloudParagraphOffset(
    paragraphLength: Int,
    startOffset: Int,
    positionMs: Long,
    durationMs: Long,
): Int {
    val offset = startOffset.coerceIn(0, paragraphLength.coerceAtLeast(0))
    if (durationMs <= 0) return offset
    val remaining = (paragraphLength - offset).coerceAtLeast(0)
    return offset + (remaining * (positionMs.coerceIn(0, durationMs).toDouble() / durationMs)).toInt()
}

/** 源级语速默认值, 对应 1 倍速, 与全局语速共用 0..80 的刻度 */
private const val DEFAULT_TTS_SPEED = 5
