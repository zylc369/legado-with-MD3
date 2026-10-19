package io.legado.app.feature.customtts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opensecurity.customtts.model.CustomTtsCatalog
import com.opensecurity.customtts.model.CustomTtsParams
import com.opensecurity.customtts.model.CustomTtsVoiceParams
import com.opensecurity.customtts.model.HybridTtsConfig
import com.opensecurity.customtts.segmenter.SegmentMath
import com.opensecurity.remotelink.gateway.ConfigStore
import com.opensecurity.remotelink.gateway.RemoteLinkManager
import io.legado.app.domain.gateway.CloudTtsEngineGateway
import io.legado.app.domain.gateway.CustomTtsCatalogGateway
import io.legado.app.domain.gateway.CustomTtsSettingsGateway
import io.legado.app.domain.gateway.HttpTtsEngineGateway
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.model.settings.CustomTtsSettings
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.readaloud.playback.CustomTtsUrlBuilder
import io.legado.app.help.readaloud.playback.SystemTtsVoiceCatalog
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import splitties.init.appCtx
import java.util.concurrent.TimeUnit

/**
 * 定制 TTS 配置页 ViewModel（UDF/MVI；经 Gateway 访问数据，不直连 DAO）。
 */
class CustomTtsViewModel(
    private val settingsGateway: CustomTtsSettingsGateway,
    private val catalogGateway: CustomTtsCatalogGateway,
    private val httpTtsGateway: HttpTtsEngineGateway,
    private val cloudTtsGateway: CloudTtsEngineGateway,
    private val voiceGateway: ReadAloudVoiceGateway,
) : ViewModel() {

    private val configStore = ConfigStore(appCtx)

    private val _uiState = MutableStateFlow(CustomTtsUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<CustomTtsEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    init {
        _uiState.update {
            it.copy(
                relayHost = configStore.relayHost,
                relayPort = configStore.relayPort.toString(),
                psk = configStore.pskBase64,
                nodeId = configStore.nodeId,
                localPort = configStore.localPort.toString(),
                gatewayEnabled = configStore.enabled,
                gatewayRunning = RemoteLinkManager.currentPort > 0,
                gatewayError = RemoteLinkManager.lastError,
            )
        }
        viewModelScope.launch {
            settingsGateway.settings.collect { settings -> applySettings(settings) }
        }
        loadFallbackEngines()
        // 兜底音色在首个 settings 应用后加载（见 applySettings）
    }

    fun onIntent(intent: CustomTtsIntent) {
        when (intent) {
            is CustomTtsIntent.SetRelayHost -> update { copy(relayHost = intent.value) }
            is CustomTtsIntent.SetRelayPort -> update { copy(relayPort = intent.value) }
            is CustomTtsIntent.SetPsk -> update { copy(psk = intent.value) }
            is CustomTtsIntent.SetNodeId -> update { copy(nodeId = intent.value) }
            is CustomTtsIntent.SetLocalPort -> update { copy(localPort = intent.value) }
            is CustomTtsIntent.SetGatewayEnabled -> update { copy(gatewayEnabled = intent.value) }
            is CustomTtsIntent.SetDefaultVoice -> saveDefaultVoice(intent.voiceId)
            is CustomTtsIntent.SaveVoiceParams -> saveVoiceParams(intent.voiceId, intent.params)
            CustomTtsIntent.RefreshVoices -> refreshVoices()
            is CustomTtsIntent.PreviewVoice -> previewVoice(intent.voiceId, intent.params)
            is CustomTtsIntent.SetFirstSegmentFallback ->
                update { copy(firstSegmentFallback = intent.value) }
            is CustomTtsIntent.SetFallbackEngine -> {
                update {
                    copy(
                        fallbackEngineType = intent.engineType,
                        fallbackEngineId = intent.engineId,
                        fallbackSpeakerId = "",
                    )
                }
                loadFallbackVoices(intent.engineType, intent.engineId)
            }
            is CustomTtsIntent.SetFallbackVoice -> update { copy(fallbackSpeakerId = intent.speakerId) }
            is CustomTtsIntent.SetFallbackSpeed -> update { copy(fallbackSpeed = intent.value) }
            is CustomTtsIntent.SetCustomSpeed -> update { copy(customSpeed = intent.value) }
            is CustomTtsIntent.SetRemoteTimeout -> update { copy(remoteTimeoutMs = intent.value) }
            is CustomTtsIntent.SetSegmentStairs -> update { copy(segmentStairsText = intent.value) }
            is CustomTtsIntent.SetSegmentTarget -> update { copy(segmentTarget = intent.value) }
            CustomTtsIntent.TestConnection -> testConnection()
            CustomTtsIntent.Save -> save()
        }
    }

    /** 兜底引擎选项：系统 + HTTP + 云（排除定制引擎）。 */
    private fun loadFallbackEngines() {
        viewModelScope.launch {
            val options = ArrayList<CustomTtsEngineOptionUi>()
            runCatching { SystemTtsVoiceCatalog(appCtx).getEngines() }
                .getOrDefault(emptyList())
                .forEach { engine ->
                    options.add(
                        CustomTtsEngineOptionUi(
                            label = "系统: ${engine.displayName}",
                            engineType = "system",
                            engineId = engine.sourceId,
                        )
                    )
                }
            runCatching { httpTtsGateway.observeAll().first() }
                .getOrDefault(emptyList())
                .filter { it.sourceId != CustomTtsCatalog.ENGINE_ID.toString() }
                .forEach { engine ->
                    options.add(
                        CustomTtsEngineOptionUi(
                            label = "HTTP: ${engine.displayName}",
                            engineType = "http",
                            engineId = engine.sourceId,
                        )
                    )
                }
            runCatching { cloudTtsGateway.getAll() }
                .getOrDefault(emptyList())
                .filter { it.enabled }
                .forEach { engine ->
                    options.add(
                        CustomTtsEngineOptionUi(
                            label = "云: ${engine.name}",
                            engineType = "cloud_tts",
                            engineId = engine.id,
                        )
                    )
                }
            update { copy(fallbackEngines = options.toImmutableList()) }
        }
    }

    /** 兜底音色选项（随引擎类型联动）。 */
    private fun loadFallbackVoices(engineType: String, engineId: String) {
        if (engineId.isEmpty()) return
        viewModelScope.launch {
            val options = when (engineType) {
                "system" -> runCatching { SystemTtsVoiceCatalog(appCtx).getVoices(engineId) }
                    .getOrDefault(emptyList())
                    .map { CustomTtsVoiceOptionUi(it.displayName, it.id) }
                else -> runCatching { voiceGateway.getEnabledVoices() }
                    .getOrDefault(emptyList())
                    .filter { it.engineType == engineType && it.engineId == engineId }
                    .map { CustomTtsVoiceOptionUi(it.displayName, it.speakerId) }
            }
            update { copy(fallbackVoices = options.toImmutableList()) }
        }
    }

    /** 测试连接：本地网关 /health（5s 超时）。 */
    private fun testConnection() {
        val port = RemoteLinkManager.currentPort
        if (port <= 0) {
            update { copy(connectionStatus = "网关未运行（先保存并启用）", gatewayRunning = false) }
            return
        }
        update { copy(testing = true, connectionStatus = "连接中…") }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val client = okHttpClient.newBuilder()
                        .callTimeout(5, TimeUnit.SECONDS)
                        .build()
                    val request = Request.Builder()
                        .url("http://127.0.0.1:$port/health")
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        val body = response.body?.string().orEmpty()
                        if (!body.contains("\"ok\":true")) error("节点不可达")
                    }
                }
            }
            update {
                copy(
                    testing = false,
                    connectionStatus = result.fold(
                        onSuccess = { "已连通" },
                        onFailure = { e -> "失败: ${e.message ?: e.javaClass.simpleName}" },
                    ),
                )
            }
        }
    }

    private fun update(transform: CustomTtsUiState.() -> CustomTtsUiState) {
        _uiState.update(transform)
    }

    private var settingsInitialized = false

    /**
     * 设置投影：
     * - 首次（页面初始化）全量应用；
     * - 后续仅合并"音色参数/默认音色"——不得覆盖页面未保存的草稿
     *   （保存单个音色参数也会触发 settings 更新）。
     */
    private fun applySettings(settings: CustomTtsSettings) {
        val first = !settingsInitialized
        _uiState.update { state ->
            val voices = if (first) {
                settings.voices.map { (id, params) ->
                    CustomTtsVoiceItemUi(
                        id = id,
                        name = params.displayName.ifEmpty { id },
                        params = params,
                    )
                }.toImmutableList()
            } else {
                state.voices.map { item ->
                    val saved = settings.voices[item.id]
                    if (saved != null) {
                        item.copy(
                            name = saved.displayName.ifEmpty { item.name },
                            params = saved,
                        )
                    } else {
                        item
                    }
                }.toImmutableList()
            }
            if (first) {
                state.copy(
                    defaultVoiceId = settings.defaultVoiceId,
                    firstSegmentFallback = settings.hybrid.firstSegmentFallback,
                    fallbackEngineType = settings.hybrid.fallbackEngineType,
                    fallbackEngineId = settings.hybrid.fallbackEngineId,
                    fallbackSpeakerId = settings.hybrid.fallbackSpeakerId,
                    fallbackSpeed = settings.hybrid.fallbackSpeed,
                    customSpeed = settings.hybrid.customSpeed,
                    remoteTimeoutMs = settings.hybrid.remoteTimeoutMs.toString(),
                    segmentStairsText = settings.segmentStairs.joinToString(","),
                    segmentTarget = settings.segmentTarget.toString(),
                    voices = voices,
                )
            } else {
                state.copy(defaultVoiceId = settings.defaultVoiceId, voices = voices)
            }
        }
        settingsInitialized = true
        if (first) {
            loadFallbackVoices(
                _uiState.value.fallbackEngineType,
                _uiState.value.fallbackEngineId,
            )
        }
    }

    private fun saveDefaultVoice(voiceId: String) {
        update { copy(defaultVoiceId = voiceId) }
        viewModelScope.launch {
            settingsGateway.update { it.copy(defaultVoiceId = voiceId) }
        }
    }

    private fun saveVoiceParams(voiceId: String, params: CustomTtsVoiceParams) {
        val checked = CustomTtsParams.validate(params)
        checked.onFailure {
            emit(CustomTtsEffect.ShowToast(it.message ?: "参数非法"))
            return
        }
        viewModelScope.launch {
            settingsGateway.update { it.copy(voices = it.voices + (voiceId to checked.getOrThrow())) }
            update {
                copy(
                    voices = voices.map {
                        if (it.id == voiceId) it.copy(params = checked.getOrThrow()) else it
                    }.toImmutableList(),
                )
            }
        }
    }

    /** 拉取服务端音色目录（本地网关 → P2P → /api/tts/voices）。 */
    private fun refreshVoices() {
        val port = RemoteLinkManager.currentPort
        if (port <= 0) {
            update { copy(catalogError = "网关未运行（先保存并启用）") }
            return
        }
        update { copy(catalogLoading = true, catalogError = null) }
        viewModelScope.launch {
            val result = catalogGateway.fetchVoices(port)
            result.fold(
                onSuccess = { entries ->
                    val saved = settingsGateway.currentSettings.voices
                    update {
                        copy(
                            catalogLoading = false,
                            catalogError = null,
                            voices = entries.map { entry ->
                                val params = saved[entry.id]
                                    ?: CustomTtsVoiceParams(
                                        voiceId = entry.id,
                                        displayName = entry.name,
                                    )
                                CustomTtsVoiceItemUi(
                                    id = entry.id,
                                    name = entry.name,
                                    gender = entry.gender,
                                    description = entry.description,
                                    isDefault = entry.isDefault,
                                    available = entry.available,
                                    params = params,
                                )
                            }.toImmutableList(),
                        )
                    }
                },
                onFailure = { e ->
                    update {
                        copy(
                            catalogLoading = false,
                            catalogError = "拉取失败: ${e.message ?: e.javaClass.simpleName}",
                        )
                    }
                },
            )
        }
    }

    /** 保存：ConfigStore + 网关重启 → Settings → 引擎/音色同步。 */
    private fun save() {
        val s = _uiState.value
        val relayPort = s.relayPort.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return emit(CustomTtsEffect.ShowToast("中继端口非法"))
        val localPort = s.localPort.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return emit(CustomTtsEffect.ShowToast("本地端口非法"))
        val stairs = SegmentMath.normalizeStairs(
            s.segmentStairsText.split(',', '，', ' ', '、')
                .filter { it.isNotBlank() }
                .mapNotNull { it.toIntOrNull() }
        ) ?: return emit(CustomTtsEffect.ShowToast("阶梯序列非法（正整数、≤8 档、升序）"))
        val target = s.segmentTarget.toIntOrNull()?.coerceIn(1, 1000)
            ?: return emit(CustomTtsEffect.ShowToast("常规段目标非法"))
        val timeout = s.remoteTimeoutMs.toIntOrNull()?.coerceIn(1_000, 120_000)
            ?: return emit(CustomTtsEffect.ShowToast("远程超时非法"))
        val pskTrimmed = s.psk.trim()
        val pskBytes = runCatching {
            android.util.Base64.decode(pskTrimmed, android.util.Base64.DEFAULT)
        }.getOrNull()
        if (pskBytes == null || pskBytes.size != 32) {
            return emit(CustomTtsEffect.ShowToast("PSK 非法（base64，解码后 32 字节）"))
        }
        if (stairs.first() !in 5..200) {
            return emit(CustomTtsEffect.ShowToast("首段字数需在 5..200"))
        }

        configStore.relayHost = s.relayHost.trim()
        configStore.relayPort = relayPort
        configStore.pskBase64 = pskTrimmed
        configStore.nodeId = s.nodeId.trim().ifEmpty { "macmini" }
        configStore.localPort = localPort
        configStore.enabled = s.gatewayEnabled

        val settings = CustomTtsSettings(
            defaultVoiceId = s.defaultVoiceId,
            voices = s.voices.associate { it.id to it.params },
            hybrid = HybridTtsConfig(
                firstSegmentFallback = s.firstSegmentFallback,
                remoteTimeoutMs = timeout,
                fallbackEngineType = s.fallbackEngineType,
                fallbackEngineId = s.fallbackEngineId,
                fallbackSpeakerId = s.fallbackSpeakerId,
                fallbackSpeed = s.fallbackSpeed,
                customSpeed = s.customSpeed,
            ),
            segmentTarget = target,
            segmentStairs = stairs,
        )
        viewModelScope.launch {
            update { copy(saving = true) }
            // 网关重启会等待在途 P2P 请求（最长 95s）——必须离开主线程
            withContext(Dispatchers.IO) { RemoteLinkManager.restart(appCtx) }
            val saveResult = runCatching { settingsGateway.update { settings } }
            if (saveResult.isFailure) {
                update { copy(saving = false) }
                emit(
                    CustomTtsEffect.ShowToast(
                        "保存失败: ${saveResult.exceptionOrNull()?.message ?: "IO 错误"}"
                    )
                )
                return@launch
            }
            val runningPort = RemoteLinkManager.currentPort
            if (runningPort > 0) {
                catalogGateway.sync(settings, runningPort).onFailure {
                    emit(CustomTtsEffect.ShowToast("音色同步失败: ${it.message}"))
                }
            }
            update {
                copy(
                    saving = false,
                    segmentStairsText = stairs.joinToString(","),
                    gatewayRunning = RemoteLinkManager.currentPort > 0,
                    gatewayError = RemoteLinkManager.lastError,
                )
            }
            emit(
                CustomTtsEffect.ShowToast(
                    if (RemoteLinkManager.currentPort > 0) "已保存" else "已保存（网关未运行）"
                )
            )
        }
    }

    /** 试听：合成固定示例文本到缓存 → PlayPreview（MediaPlayer 在 Screen 侧）。 */
    private fun previewVoice(voiceId: String, paramsOverride: CustomTtsVoiceParams? = null) {
        val port = RemoteLinkManager.currentPort
        if (port <= 0) {
            emit(CustomTtsEffect.ShowToast("网关未运行（先保存并启用）"))
            return
        }
        val params = paramsOverride
            ?: settingsGateway.currentSettings.voices[voiceId]
            ?: _uiState.value.voices.firstOrNull { it.id == voiceId }?.params
            ?: CustomTtsVoiceParams(voiceId = voiceId)
        val checked = CustomTtsParams.validate(params)
        checked.onFailure {
            emit(CustomTtsEffect.ShowToast(it.message ?: "参数非法"))
            return
        }
        viewModelScope.launch {
            emit(CustomTtsEffect.ShowToast("合成中…"))
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val url = CustomTtsUrlBuilder.buildCustomTtsUrl(
                        com.opensecurity.customtts.model.CustomTtsCatalog.url(port),
                        PREVIEW_TEXT,
                        checked.getOrThrow(),
                    )
                    val request = Request.Builder().url(url).get().build()
                    val client = okHttpClient.newBuilder()
                        .callTimeout(60, TimeUnit.SECONDS)
                        .build()
                    val bytes = client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        val contentType = response.header("Content-Type").orEmpty()
                        if (!contentType.startsWith("audio/")) error("响应非音频: $contentType")
                        response.body?.bytes() ?: error("空响应")
                    }
                    val dir = java.io.File(appCtx.cacheDir, "custom_tts_preview").apply { mkdirs() }
                    val file = java.io.File(dir, "${voiceId.hashCode()}.wav")
                    file.writeBytes(bytes)
                    file.absolutePath
                }
            }
            result.fold(
                onSuccess = { path -> emit(CustomTtsEffect.PlayPreview(path)) },
                onFailure = { e ->
                    emit(CustomTtsEffect.ShowToast("试听失败: ${e.message ?: e.javaClass.simpleName}"))
                },
            )
        }
    }

    private fun emit(effect: CustomTtsEffect) {
        _effects.tryEmit(effect)
    }

    companion object {
        private const val PREVIEW_TEXT = "这是一段试听文本，用于确认音色与参数效果。"
    }
}
