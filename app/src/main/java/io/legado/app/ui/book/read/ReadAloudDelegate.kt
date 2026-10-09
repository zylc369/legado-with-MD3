package io.legado.app.ui.book.read

import android.content.Context
import android.speech.tts.TextToSpeech
import io.legado.app.R
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.repository.HttpTtsRepository
import io.legado.app.data.repository.ReadAloudSettingsRepository
import io.legado.app.domain.model.PlaybackTimer
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.VoiceCatalogEntry
import io.legado.app.domain.model.settings.ReadAloudSettings
import io.legado.app.domain.model.settings.ReadAloudTimerMode
import io.legado.app.domain.usecase.SyncReadAloudVoicesUseCase
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudSessionStore
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.readaloud.ReadAloudPlayerOverlayBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 朗读域（R2.2 续批）。
 *
 * 管朗读设置的读写、播放传输控制、声音目录同步和 TTS 缓存清理。
 * 朗读配置内容的数值选择弹层已随「朗读设置」独立页迁出，不再由本委托承载。
 *
 * **无自持状态**：朗读的 20 来个字段散落在 [ReadBookUiState] 里，被 `ReadAloudScreen`、
 * `ReadBookRouteScreen` 等处直读——搬出去要同时改这些 composable 的入参。
 * 故与 [ReadConfigUpdateDelegate] / [ReadButtonConfigDelegate] 同形：状态留在 UiState，
 * 读写一律经 [Host]。
 */
class ReadAloudDelegate(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: Host,
    private val readAloudSettingsRepository: ReadAloudSettingsRepository,
    private val readAloudSessionStore: ReadAloudSessionStore,
    private val httpTtsRepository: HttpTtsRepository,
    private val syncReadAloudVoicesUseCase: SyncReadAloudVoicesUseCase,
) {

    interface Host {
        val uiState: ReadBookUiState

        /** 系统 TTS 引擎清单（VM 侧 lazy，构造代价高，只取一次）。 */
        val systemTtsEngines: List<TextToSpeech.EngineInfo>

        fun updateState(transform: (ReadBookUiState) -> ReadBookUiState)

        fun emitEffect(effect: ReadBookEffect)

        suspend fun emitEffectAwait(effect: ReadBookEffect)

        fun openReadMenuRoute(route: ReadBookMenuRoute)

        /** 朗读进度（TTS 回调上报的章内偏移），VM 用独立 flow 暴露给胶囊。 */
        fun publishReadAloudProgress(chapterStart: Int)
    }

    /** 订阅朗读设置，投影进 UiState。VM 构造时调一次。 */
    fun collectPreferences() {
        scope.launch {
            readAloudSettingsRepository.preferences.collect { prefs ->
                host.updateState {
                    it.copy(
                        readAloudTtsFollowSys = prefs.ttsFollowSys,
                        readAloudTtsSpeechRate = prefs.ttsSpeechRate,
                        readAloudTtsTimer = prefs.ttsTimer,
                        readAloudFinishCurrentChapterAfterTimer =
                            prefs.finishCurrentChapterAfterTimer,
                        readAloudTimerMode = prefs.timerMode,
                        readAloudTimerChapters = prefs.timerChapters,
                    )
                }
            }
        }
    }

    /**
     * 刷新声音目录。朗读引擎可能在「引擎与音色」页被新增/删除，
     * 打开朗读设置页和 VM 构造时各同步一次。
     */
    suspend fun syncConfiguredTtsVoices(
        systemTtsLabel: String = context.getString(R.string.system_tts),
        httpTtsList: List<HttpTTS> = httpTtsRepository.getAllSync(),
    ) {
        syncReadAloudVoicesUseCase(
            entries = buildList {
                add(
                    VoiceCatalogEntry(
                        engineType = ReadAloudVoice.ENGINE_SYSTEM,
                        engineId = "",
                        displayName = systemTtsLabel,
                    )
                )
                host.systemTtsEngines.forEach { engine ->
                    add(
                        VoiceCatalogEntry(
                            engineType = ReadAloudVoice.ENGINE_SYSTEM,
                            engineId = engine.name,
                            displayName = engine.label,
                        )
                    )
                }
                httpTtsList.forEach { httpTts ->
                    add(
                        VoiceCatalogEntry(
                            engineType = ReadAloudVoice.ENGINE_HTTP,
                            engineId = httpTts.id.toString(),
                            displayName = httpTts.name,
                            sourceRevision = httpTts.lastUpdateTime,
                        )
                    )
                }
            },
            managedSources = setOf(ReadAloudVoice.MANAGED_BY_CONFIGURED_TTS),
            removeMissingEngineTypes = setOf(ReadAloudVoice.ENGINE_HTTP),
        )
    }

    // --- 播放控制 ---

    fun updateProgress(chapterStart: Int) {
        if (BaseReadAloudService.isPlay() && chapterStart > 0) {
            host.publishReadAloudProgress(chapterStart)
        }
    }

    fun stop() {
        ReadAloud.stop(context)
        host.updateState { it.copy(isReadAloudRunning = false, isReadAloudPaused = false) }
    }

    fun prevParagraph() = ReadAloud.prevParagraph(context)

    fun nextParagraph() = ReadAloud.nextParagraph(context)

    /** 朗读面板换章：朗读驱动的章节移动，页面跟随朗读，不视为手动脱离。 */
    fun prevChapter() = BaseReadAloudService.withSpeechNavigation {
        ReadBook.moveToPrevChapter(upContent = true, toLast = false)
    }

    fun nextChapter() = BaseReadAloudService.withSpeechNavigation {
        ReadBook.moveToNextChapter(true)
    }

    /** 回到朗读位置：恢复页面跟随朗读，并跳到朗读所在章节/字符位置。全程不打断当前朗读。 */
    fun backToSpeakingPosition() {
        readAloudSessionStore.restoreReadAloudFollow()
        val speakingChapterIndex = BaseReadAloudService.currentChapterIndex
        val speakingChapterStart = BaseReadAloudService.currentProgress
        if (speakingChapterIndex < 0) return
        // currentProgress 为正在朗读的精确章内位置（onRangeStart 段内偏移上报），整段高亮落在当前段
        val chapterStart = speakingChapterStart.coerceAtLeast(0)
        if (speakingChapterIndex != ReadBook.durChapterIndex) {
            // 跳到朗读位置属于朗读相关的页面移动，不能触发手动脱离
            BaseReadAloudService.withSpeechNavigation {
                ReadBook.openChapter(speakingChapterIndex, chapterStart) {
                    ReadBook.upTextChapterAloudSpan(chapterStart)
                }
            }
        } else {
            ReadBook.syncReadAloudPage(speakingChapterIndex, chapterStart)
            ReadBook.upTextChapterAloudSpan(chapterStart)
        }
    }

    // --- 界面入口 ---

    /** 媒体键/胶囊触发的默认朗读界面：按设置决定开播放器还是经典控制面板。 */
    fun openDefaultInterface() {
        if (
            readAloudSettingsRepository.currentSettings.defaultInterface ==
            ReadAloudSettingsRepository.DEFAULT_INTERFACE_PLAYER
        ) {
            openPlayer()
        } else {
            host.openReadMenuRoute(ReadBookMenuRoute.ReadAloud)
        }
    }

    /**
     * 打开听书播放弹层。
     *
     * 播放弹层是 Activity 级全局浮层（[ReadAloudPlayerOverlayBus]），不占导航栈，
     * 所以这里只请求宿主把它拉起来；先把菜单与已有弹层收掉，
     * 关闭播放弹层时不会停在半开的菜单上。
     */
    fun openPlayer() {
        host.updateState { it.copy(menuState = ReadBookMenuState(), activeSheet = null) }
        ReadAloudPlayerOverlayBus.request()
    }

    /** 经典朗读控制面板：阅读菜单里的一页，不遮挡正文区域之外的交互。 */
    fun openClassicControls() {
        host.updateState { it.copy(activeSheet = null) }
        host.openReadMenuRoute(ReadBookMenuRoute.ReadAloud)
    }

    /**
     * 打开「朗读设置」独立路由页。
     *
     * 设置页是 Navigation 3 目的地，直接压在阅读界面之上；子页（引擎与音色 / 缓存 / 配音）
     * 从它再 push 一层，返回天然回到设置页——不再需要「离开收弹层、返回重开弹层」的补丁。
     *
     * 打开时同步一次声音目录：朗读引擎可能在「引擎与音色」页被新增/删除，
     * 目录在进入设置页时刷新才不会留下过期引擎。
     */
    fun openConfigRoute() {
        scope.launch { syncConfiguredTtsVoices() }
        host.emitEffect(ReadBookEffect.OpenReadAloudSettings)
    }

    fun setTtsFollowSys(value: Boolean) {
        updateSettings { it.copy(ttsFollowSys = value) }
        host.updateState { it.copy(readAloudTtsFollowSys = value) }
    }

    fun setTtsTimer(value: Int) {
        val timer = PlaybackTimer.normalize(value)
        ReadAloud.setTimer(context, timer)
        // 两种定时互斥：设分钟定时即切到分钟模式并清掉章节配额
        updateSettings {
            it.copy(
                ttsTimer = timer,
                timerMode = ReadAloudTimerMode.Minute.storageValue,
                timerChapters = 0,
            )
        }
        ReadAloud.setTimerChapters(context, 0)
        host.updateState {
            it.copy(
                readAloudTtsTimer = timer,
                readAloudTimerMode = ReadAloudTimerMode.Minute.storageValue,
                readAloudTimerChapters = 0,
            )
        }
    }

    fun setFinishCurrentChapterAfterTimer(value: Boolean) {
        updateSettings { it.copy(finishCurrentChapterAfterTimer = value) }
        host.updateState { it.copy(readAloudFinishCurrentChapterAfterTimer = value) }
    }

    fun setTimerMode(mode: ReadAloudTimerMode) {
        val prefs = readAloudSettingsRepository.currentSettings
        val minutes = if (mode == ReadAloudTimerMode.Minute) prefs.ttsTimer else 0
        val chapters = if (mode == ReadAloudTimerMode.Chapter) prefs.timerChapters else 0
        updateSettings {
            it.copy(
                timerMode = mode.storageValue,
                ttsTimer = minutes,
                timerChapters = chapters,
            )
        }
        ReadAloud.setTimer(context, minutes)
        ReadAloud.setTimerChapters(context, chapters)
        host.updateState {
            it.copy(
                readAloudTimerMode = mode.storageValue,
                readAloudTtsTimer = minutes,
                readAloudTimerChapters = chapters,
            )
        }
    }

    fun setTimerChapters(value: Int) {
        val chapters = PlaybackTimer.normalizeChapters(value)
        ReadAloud.setTimerChapters(context, chapters)
        updateSettings {
            it.copy(
                timerChapters = chapters,
                timerMode = ReadAloudTimerMode.Chapter.storageValue,
                ttsTimer = 0,
            )
        }
        // 切到章节模式要同时停掉正在跑的分钟倒计时
        ReadAloud.setTimer(context, 0)
        host.updateState {
            it.copy(
                readAloudTimerChapters = chapters,
                readAloudTimerMode = ReadAloudTimerMode.Chapter.storageValue,
                readAloudTtsTimer = 0,
            )
        }
    }

    fun setTtsSpeechRate(value: Int) {
        scope.launch {
            readAloudSettingsRepository.update { it.copy(ttsSpeechRate = value.coerceIn(0, 80)) }
            ReadAloud.upTtsSpeechRate(context)
        }
        host.updateState { it.copy(readAloudTtsSpeechRate = value) }
    }

    private inline fun updateSettings(
        crossinline transform: (ReadAloudSettings) -> ReadAloudSettings,
    ) {
        scope.launch {
            readAloudSettingsRepository.update { transform(it) }
        }
    }
}
