package io.legado.app.ui.book.readaloud.engines

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.domain.gateway.CloudTtsEngineGateway
import io.legado.app.domain.gateway.HttpTtsEngineGateway
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.model.readaloud.ReadAloudEngineSelection
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.TtsEngineDescriptor
import io.legado.app.model.ReadBook
import io.legado.app.help.readaloud.playback.ReadAloudEngineCatalog
import io.legado.app.help.readaloud.playback.ReadAloudEngineGroup
import io.legado.app.help.readaloud.playback.ReadAloudEngineScope
import io.legado.app.help.readaloud.playback.ReadAloudEngineSource
import io.legado.app.help.readaloud.playback.ReadAloudEngineSwitch
import io.legado.app.help.readaloud.playback.ReadAloudEngineVoiceRef
import io.legado.app.help.readaloud.playback.SystemTtsVoiceCatalog
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/** 引擎切换后按当前位置重启朗读（系统↔HTTP 由不同 Service 承载，无法原地热换）。 */
fun interface ReadAloudEngineRestarter {
    suspend fun restartAtCurrentPosition()
}

/**
 * 「朗读引擎」快捷切换状态（数据部分）。
 *
 * 面板可见性与作用域选择由各宿主 Composable 自持——本 VM 是三宿主共享的 Koin single，
 * 放这里会在“设置浮层压着阅读菜单”等同时组合的场景里把开关同步给所有宿主。
 */
@Stable
data class ReadAloudEnginesUiState(
    val bookUrl: String? = null,
    val loading: Boolean = true,
    /** 目录加载失败（Binder/DB 异常）：面板显示重试入口。 */
    val loadFailed: Boolean = false,
    /** 面板当前作用域下的分组（仅影响面板内的选中标记）。 */
    val groups: ImmutableList<ReadAloudEngineGroup> = persistentListOf(),
    /** 入口行三件套：固定按“本书生效选择”（本书 ?: 全局）计算，不随面板作用域变。 */
    val currentName: String = "",
    val currentVoiceName: String = "",
    val bookOverride: Boolean = false,
    /** 面板作用域（共享：切换书时重置回本书）。 */
    val scope: ReadAloudEngineScope = ReadAloudEngineScope.Book,
)

class ReadAloudEnginesViewModel(
    private val settingsGateway: ReadAloudSettingsGateway,
    private val voiceGateway: ReadAloudVoiceGateway,
    private val httpTtsGateway: HttpTtsEngineGateway,
    private val cloudTtsGateway: CloudTtsEngineGateway,
    private val restarter: ReadAloudEngineRestarter,
    /** 系统 TTS 引擎目录（需 Binder；测试可替换）。 */
    private val systemEngines: suspend () -> List<TtsEngineDescriptor> =
        { SystemTtsVoiceCatalog(appCtx).getEngines() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 系统引擎目录缓存时长：一次面板打开要绑一次 TTS 服务（约百毫秒级）。 */
    private val systemEngineCacheMs: Long = 60_000,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReadAloudEnginesUiState())
    val uiState = _uiState.asStateFlow()

    private var sources: List<ReadAloudEngineSource> = emptyList()
    private var voiceNames: Map<String, String> = emptyMap()
    private var bookSelection: ReadAloudEngineSelection? = null
    private var globalSelection: ReadAloudEngineSelection? = null

    /** 会话内每个引擎上次使用的音色：A→B→A 切换不会互相覆盖音色。 */
    private val voiceMemory = mutableMapOf<String, ReadAloudEngineVoiceRef>()

    /** 在途目录加载；选择写入前取消，防止旧快照覆盖刚写入的选择。 */
    private var reloadJob: Job? = null

    /** 加载代数：被取消后仍跑完的旧任务按代数丢弃结果。 */
    private var loadGeneration = 0

    private var systemEngineCache: Pair<Long, List<TtsEngineDescriptor>>? = null

    fun bindBook(bookUrl: String?) {
        val bookChanged = _uiState.value.bookUrl != bookUrl
        _uiState.update {
            if (bookChanged) it.copy(bookUrl = bookUrl, scope = ReadAloudEngineScope.Book) else it
        }
        if (!bookChanged && sources.isNotEmpty() && !_uiState.value.loading) return
        reload()
    }

    /** 面板打开时刷新目录（引擎可能在“引擎与音色”页被增删）。 */
    fun refresh() = reload()

    fun retry() = reload()

    fun setScope(scope: ReadAloudEngineScope) {
        _uiState.update { it.copy(scope = scope) }
        rebuild()
    }

    /**
     * 切换到指定引擎：写入当前作用域，生效选择变化时按当前位置重启朗读
     * （系统↔HTTP 需换服务类；本书覆盖存在时改全局默认不影响本书，跳过重启）。
     */
    fun select(engineType: String, engineId: String) {
        val source = sources.firstOrNull {
            it.engineType == engineType && it.engineId == engineId
        } ?: return
        viewModelScope.launch {
            // 在途目录加载作废：其读取的选择快照可能早于本次写入
            reloadJob?.cancel()
            loadGeneration++

            val scope = _uiState.value.scope
            val previousEffective = ReadAloudEngineSwitch.effectiveSelection(
                bookSelection, globalSelection, ReadAloudEngineScope.Book,
            )
            // 记住切换前生效引擎的音色：A→B→A 切回时不会掉回引擎默认
            previousEffective?.let { previous ->
                sources.firstOrNull {
                    it.engineType == previous.engineType && it.engineId == previous.engineId
                }?.let { previousSource ->
                    voiceMemory[ReadAloudEngineSwitch.key(previous.engineType, previous.engineId)] =
                        ReadAloudEngineSwitch.voiceFor(
                            previousSource, bookSelection, globalSelection, voiceMemory, voiceNames,
                        )
                }
            }
            val voice = ReadAloudEngineSwitch.voiceFor(
                source, bookSelection, globalSelection, voiceMemory, voiceNames,
            )
            val selection = ReadAloudEngineSwitch.buildSelection(source, voice)
            val raw = ReadAloudEngineSelection.serialize(selection)
            voiceMemory[ReadAloudEngineSwitch.key(engineType, engineId)] = voice
            when (scope) {
                ReadAloudEngineScope.Book -> {
                    val url = _uiState.value.bookUrl
                    if (url != null) {
                        voiceGateway.setBookTtsSelection(url, raw)
                        ReadBook.book?.takeIf { it.bookUrl == url }?.setTtsEngine(raw)
                        bookSelection = selection
                    } else {
                        // 没有书上下文（入口不显示，防御）：退化为改全局默认
                        settingsGateway.update { it.copy(ttsEngine = raw) }
                        globalSelection = selection
                    }
                }

                ReadAloudEngineScope.Global -> {
                    settingsGateway.update { it.copy(ttsEngine = raw) }
                    globalSelection = selection
                }
            }
            rebuild()
            val effective = ReadAloudEngineSwitch.effectiveSelection(
                bookSelection, globalSelection, ReadAloudEngineScope.Book,
            )
            val changedEngine = effective?.let {
                it.engineType != previousEffective?.engineType ||
                    it.engineId != previousEffective?.engineId
            } ?: (previousEffective != null)
            if (changedEngine) {
                restarter.restartAtCurrentPosition()
            }
        }
    }

    private fun reload() {
        val generation = ++loadGeneration
        reloadJob?.cancel()
        _uiState.update { it.copy(loading = true, loadFailed = false) }
        reloadJob = viewModelScope.launch {
            val url = _uiState.value.bookUrl
            val loaded = withContext(ioDispatcher) {
                runCatching { loadDirectory(url) }.getOrNull()
            }
            if (generation != loadGeneration) return@launch
            if (loaded == null) {
                _uiState.update { it.copy(loading = false, loadFailed = true) }
                return@launch
            }
            sources = ReadAloudEngineCatalog.merge(
                system = loaded.system,
                http = loaded.http,
                cloud = loaded.cloud,
                prefixedLabel = false,
            ).map { ReadAloudEngineSource(it.engineType, it.engineId, it.label) }
            voiceNames = loaded.voices.associate {
                ReadAloudEngineSwitch.voiceKey(it.engineType, it.engineId, it.speakerId) to
                    it.displayName
            }
            bookSelection = ReadAloudEngineSelection.parse(loaded.bookRaw)
            globalSelection = ReadAloudEngineSelection.parse(loaded.globalRaw)
            rebuild()
        }
    }

    private suspend fun loadDirectory(url: String?): Loaded {
        val now = System.currentTimeMillis()
        val cached = systemEngineCache
        val system = if (cached != null && now - cached.first < systemEngineCacheMs) {
            cached.second
        } else {
            systemEngines().also { systemEngineCache = now to it }
        }
        return Loaded(
            system = system,
            http = httpTtsGateway.observeAll().first(),
            cloud = cloudTtsGateway.getAll(),
            voices = voiceGateway.getVoices(),
            bookRaw = url?.let { voiceGateway.getBookTtsSelection(it) },
            globalRaw = settingsGateway.currentSettings.ttsEngine,
        )
    }

    private fun rebuild() {
        val state = _uiState.value
        // 入口行固定按“本书生效选择”展示：面板切到全局默认时，入口不应谎报本书换引擎
        val effective = ReadAloudEngineSwitch.effectiveSelection(
            bookSelection, globalSelection, ReadAloudEngineScope.Book,
        )
        val currentSource = sources.firstOrNull {
            effective != null &&
                it.engineType == effective.engineType && it.engineId == effective.engineId
        }
        val currentVoice = currentSource?.let {
            ReadAloudEngineSwitch.voiceFor(
                it, bookSelection, globalSelection, voiceMemory, voiceNames,
            )
        }
        val groups = ReadAloudEngineSwitch.buildGroups(
            sources = sources,
            bookSelection = bookSelection,
            globalSelection = globalSelection,
            scope = state.scope,
            voiceMemory = voiceMemory,
            voiceNames = voiceNames,
        )
        _uiState.update {
            it.copy(
                loading = false,
                loadFailed = false,
                groups = groups.toImmutableList(),
                currentName = currentSource?.name
                    ?: effective?.displayName.orEmpty(),
                currentVoiceName = currentVoice?.displayName.orEmpty(),
                bookOverride = bookSelection != null,
            )
        }
    }

    private class Loaded(
        val system: List<TtsEngineDescriptor>,
        val http: List<TtsEngineDescriptor>,
        val cloud: List<io.legado.app.domain.model.readaloud.CloudTtsEngine>,
        val voices: List<ReadAloudVoice>,
        val bookRaw: String?,
        val globalRaw: String?,
    )
}
