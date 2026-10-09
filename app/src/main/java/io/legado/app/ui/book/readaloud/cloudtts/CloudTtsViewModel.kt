package io.legado.app.ui.book.readaloud.cloudtts

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.repository.UploadRepository
import io.legado.app.domain.gateway.CloudTtsEngineGateway
import io.legado.app.domain.gateway.HttpTtsEngineGateway
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.model.readaloud.CloudTtsEngine
import io.legado.app.domain.model.readaloud.CloudTtsProviderType
import io.legado.app.domain.model.readaloud.CloudTtsSynthesisRequest
import io.legado.app.domain.model.readaloud.CloudTtsVoiceCatalogType
import io.legado.app.domain.model.readaloud.CloudTtsVoiceConfig
import io.legado.app.domain.model.readaloud.ReadAloudEngineSelection
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SystemTtsVoiceConfig
import io.legado.app.domain.model.readaloud.TtsEngineDescriptor
import io.legado.app.domain.model.readaloud.TtsNativeVoice
import io.legado.app.domain.model.readaloud.profile
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.help.readaloud.playback.CloudTtsAudioSynthesizer
import io.legado.app.help.readaloud.playback.SystemTtsFileSynthesizer
import io.legado.app.help.readaloud.playback.SystemTtsVoiceCatalog
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.widget.components.importComponents.BaseImportUiState
import io.legado.app.ui.widget.components.importComponents.ImportItemWrapper
import io.legado.app.ui.widget.components.importComponents.ImportStatus
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isDataUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.uuid.Uuid

class CloudTtsViewModel(
    private val application: Application,
    private val engineGateway: CloudTtsEngineGateway,
    private val httpTtsEngineGateway: HttpTtsEngineGateway,
    private val voiceGateway: ReadAloudVoiceGateway,
    private val readAloudSettingsGateway: ReadAloudSettingsGateway,
    private val uploadRepository: UploadRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CloudTtsUiState())
    val uiState = _uiState.asStateFlow()
    private val _effects = MutableSharedFlow<CloudTtsEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()
    private val synthesizer = CloudTtsAudioSynthesizer(engineGateway)
    private val systemCatalog = SystemTtsVoiceCatalog(application)
    private val systemSynthesizer = SystemTtsFileSynthesizer(application)

    private var engines = emptyList<CloudTtsEngine>()
    private var voices = emptyList<ReadAloudVoice>()
    private var systemEngines = emptyList<TtsEngineDescriptor>()
    private var httpEngines = emptyList<TtsEngineDescriptor>()
    private var bookUrl: String? = null
    private var bookSelectionRaw: String? = null
    private var pickerNative = emptyList<TtsNativeVoice>()

    init {
        viewModelScope.launch {
            combine(
                engineGateway.observeAll(),
                httpTtsEngineGateway.observeAll(),
                voiceGateway.observeVoices(),
            ) { allEngines, allHttpEngines, allVoices ->
                Triple(allEngines, allHttpEngines, allVoices.filter { voice ->
                    voice.engineType in setOf(
                        ReadAloudVoice.ENGINE_CLOUD,
                        ReadAloudVoice.ENGINE_SYSTEM,
                        ReadAloudVoice.ENGINE_HTTP,
                    )
                })
            }.collect { (allEngines, allHttpEngines, allSavedVoices) ->
                val configuredHttpEngineIds = allHttpEngines.mapTo(mutableSetOf()) { it.sourceId }
                val orphanedVoiceIds = allSavedVoices.asSequence()
                    .filter { it.engineType == ReadAloudVoice.ENGINE_HTTP }
                    .filter { it.managedBy == ReadAloudVoice.MANAGED_BY_CONFIGURED_TTS }
                    .filter { it.engineId !in configuredHttpEngineIds }
                    .mapTo(mutableSetOf()) { it.id }
                allSavedVoices
                    .filter { it.id in orphanedVoiceIds }
                    .forEach { voiceGateway.deleteVoice(it) }
                engines = allEngines
                httpEngines = allHttpEngines
                voices = allSavedVoices.filterNot { it.id in orphanedVoiceIds }
                rebuildEngineItems()
            }
        }
        viewModelScope.launch {
            systemEngines = systemCatalog.getEngines()
            rebuildEngineItems()
        }
    }

    fun onIntent(intent: CloudTtsIntent) {
        when (intent) {
            is CloudTtsIntent.SetBookContext -> setBookContext(intent.bookUrl)

            is CloudTtsIntent.OpenVoicePicker -> openVoicePicker(intent.engineType, intent.engineId)
            is CloudTtsIntent.ToggleVoiceScope -> toggleVoiceScope(intent.scope)
            is CloudTtsIntent.SelectVoice -> selectVoice(intent.speakerId)
            CloudTtsIntent.ClearBookSelection -> clearBookSelection()
            CloudTtsIntent.RefreshVoiceCatalog -> refreshVoiceCatalog()
            CloudTtsIntent.DismissVoicePicker -> _uiState.update { it.copy(voicePicker = null) }

            CloudTtsIntent.AddVoicePreset -> addVoicePreset()
            is CloudTtsIntent.EditVoicePreset -> editVoice(intent.voiceId)
            is CloudTtsIntent.RequestDeleteVoice -> requestDeleteVoice(intent.voiceId)
            CloudTtsIntent.ConfirmDeleteVoice -> confirmDeleteVoice()
            is CloudTtsIntent.UpdateVoiceEditor -> _uiState.update { it.copy(voiceEditor = intent.editor) }
            CloudTtsIntent.DismissVoiceEditor -> _uiState.update { it.copy(voiceEditor = null) }
            is CloudTtsIntent.SelectEditorVoice -> selectEditorVoice(intent.voiceId)
            CloudTtsIntent.DiscoverEditorVoices -> discoverVoices()

            CloudTtsIntent.AddEngine -> _uiState.update { it.copy(
                engineEditor = CloudTtsEngineEditorUi(name = "MiMo", model = "mimo-v2.5-tts"),
            ) }
            is CloudTtsIntent.EditEngine -> editEngine(intent.engineId)
            is CloudTtsIntent.DeleteEngine -> deleteEngine(intent.engineId)
            is CloudTtsIntent.UpdateEngineEditor -> _uiState.update { it.copy(engineEditor = intent.editor) }
            CloudTtsIntent.DismissEngineEditor -> _uiState.update { it.copy(engineEditor = null) }

            is CloudTtsIntent.EditHttpTts -> editHttpTts(intent.engineId)
            is CloudTtsIntent.SaveHttpTts -> saveHttpTts(intent.value)
            is CloudTtsIntent.DeleteHttpTts -> deleteHttpTts(intent.engineId)
            is CloudTtsIntent.OpenHttpTtsLogin -> intent.engineId.toLongOrNull()?.let {
                _effects.tryEmit(CloudTtsEffect.OpenHttpTtsLogin(it))
            }

            CloudTtsIntent.DismissHttpTtsEditor -> _uiState.update { it.copy(httpTtsEditor = null) }
            is CloudTtsIntent.ImportHttpTtsSource -> importHttpTtsSource(intent.text)
            CloudTtsIntent.ImportHttpTtsFile -> _effects.tryEmit(CloudTtsEffect.OpenHttpTtsImportPicker)
            is CloudTtsIntent.ImportHttpTtsFileSelected -> importHttpTtsFile(intent.uri)
            CloudTtsIntent.CancelHttpTtsImport -> _uiState.update { it.copy(httpTtsImportState = BaseImportUiState.Idle) }
            is CloudTtsIntent.ToggleHttpTtsImportSelection -> toggleHttpTtsImportSelection(intent.index)
            is CloudTtsIntent.ToggleHttpTtsImportAll -> toggleHttpTtsImportAll(intent.selected)
            is CloudTtsIntent.UpdateHttpTtsImportItem -> updateHttpTtsImportItem(
                intent.index,
                intent.value
            )

            CloudTtsIntent.SaveImportedHttpTts -> saveImportedHttpTts()
            CloudTtsIntent.ExportHttpTtsFile -> _effects.tryEmit(CloudTtsEffect.OpenHttpTtsExportPicker)
            is CloudTtsIntent.ExportHttpTtsFileSelected -> exportHttpTtsFile(intent.uri)
            CloudTtsIntent.ExportHttpTtsUrl -> exportHttpTtsUrl()
            CloudTtsIntent.ClearTtsCache -> {
                io.legado.app.utils.TTSCacheUtils.clearTtsCache()
                toast(application.getString(R.string.clear_cache_success))
            }

            CloudTtsIntent.TestEngine -> testEngine()
            CloudTtsIntent.Preview -> preview()
            CloudTtsIntent.Save -> if (_uiState.value.engineEditor != null) saveEngine() else saveVoice()
            CloudTtsIntent.DismissError -> _uiState.update { it.copy(activeDialog = null) }
            CloudTtsIntent.CopyError -> copyError()
            is CloudTtsIntent.ReportError -> showError(intent.message)
        }
    }

    // --- 列表状态 ---

    private fun rebuildEngineItems() {
        val global = globalSelection()
        val book = bookSelection()
        _uiState.update { state ->
            state.copy(
                hasBookContext = bookUrl != null,
                cloudEngines = engines.map { engine ->
                    engineItem(
                        engineType = ReadAloudVoice.ENGINE_CLOUD,
                        engineId = engine.id,
                        title = engine.name,
                        summary = engine.provider.profile.displayName,
                        global = global,
                        book = book,
                        editable = true,
                        deletable = true,
                    )
                }.toImmutableList(),
                httpEngines = httpEngines.map { engine ->
                    engineItem(
                        engineType = ReadAloudVoice.ENGINE_HTTP,
                        engineId = engine.sourceId,
                        title = engine.displayName,
                        summary = engine.providerName,
                        global = global,
                        book = book,
                        editable = true,
                        deletable = true,
                        loginUrl = engine.loginUrl,
                    )
                }.toImmutableList(),
                systemEngines = buildList {
                    add(
                        engineItem(
                            engineType = ReadAloudVoice.ENGINE_SYSTEM,
                            engineId = "",
                            title = application.getString(R.string.system_tts),
                            summary = "",
                            global = global,
                            book = book,
                        )
                    )
                    systemEngines.forEach { engine ->
                        add(
                            engineItem(
                                engineType = ReadAloudVoice.ENGINE_SYSTEM,
                                engineId = engine.sourceId,
                                title = engine.displayName,
                                summary = engine.providerName,
                                global = global,
                                book = book,
                            )
                        )
                    }
                }.toImmutableList(),
            )
        }
    }

    private fun engineItem(
        engineType: String,
        engineId: String,
        title: String,
        summary: String,
        global: ReadAloudEngineSelection?,
        book: ReadAloudEngineSelection?,
        editable: Boolean = false,
        deletable: Boolean = false,
        loginUrl: String = "",
    ): CloudTtsEngineItemUi {
        val effective = when {
            matches(book, engineType, engineId) -> book
            matches(global, engineType, engineId) -> global
            else -> null
        }
        return CloudTtsEngineItemUi(
            engineType = engineType,
            engineId = engineId,
            title = title,
            summary = summary,
            voiceName = effective?.let { speakerLabel(engineType, engineId, it.speakerId) }.orEmpty(),
            globalSelected = matches(global, engineType, engineId),
            bookSelected = matches(book, engineType, engineId),
            editable = editable,
            deletable = deletable,
            loginUrl = loginUrl,
        )
    }

    private fun globalSelection(): ReadAloudEngineSelection? =
        ReadAloudEngineSelection.parse(readAloudSettingsGateway.currentSettings.ttsEngine)

    private fun bookSelection(): ReadAloudEngineSelection? =
        ReadAloudEngineSelection.parse(bookSelectionRaw)

    private fun matches(
        selection: ReadAloudEngineSelection?,
        engineType: String,
        engineId: String,
    ): Boolean = selection != null &&
        selection.engineType == engineType && selection.engineId == engineId

    private fun speakerLabel(engineType: String, engineId: String, speakerId: String): String {
        if (speakerId.isBlank()) return defaultVoiceLabel(engineType)
        val preset = voices.firstOrNull {
            it.engineType == engineType && it.engineId == engineId && it.speakerId == speakerId
        }
        return preset?.displayName?.takeIf(String::isNotBlank) ?: speakerId
    }

    private fun defaultVoiceLabel(engineType: String): String = when (engineType) {
        ReadAloudVoice.ENGINE_HTTP -> application.getString(R.string.cloud_tts_engine_default_voice)
        else -> application.getString(R.string.cloud_tts_default_voice)
    }

    private fun engineTitle(engineType: String, engineId: String): String = when (engineType) {
        ReadAloudVoice.ENGINE_SYSTEM -> if (engineId.isBlank()) {
            application.getString(R.string.system_tts)
        } else {
            systemEngines.firstOrNull { it.sourceId == engineId }?.displayName ?: engineId
        }

        ReadAloudVoice.ENGINE_CLOUD -> engines.firstOrNull { it.id == engineId }?.name ?: engineId
        ReadAloudVoice.ENGINE_HTTP -> httpEngines.firstOrNull {
            it.sourceId == engineId
        }?.displayName ?: engineId

        else -> engineId
    }

    // --- 音色弹框 ---

    private fun openVoicePicker(engineType: String, engineId: String) = viewModelScope.launch {
        _uiState.update { state ->
            state.copy(
                voicePicker = CloudTtsVoicePickerUi(
                    engineType = engineType,
                    engineId = engineId,
                    engineTitle = engineTitle(engineType, engineId),
                    globalTarget = matches(globalSelection(), engineType, engineId),
                    bookTarget = matches(bookSelection(), engineType, engineId),
                    followGlobal = bookSelection() == null,
                    loading = true,
                )
            )
        }
        loadPickerVoices()
    }

    private fun loadPickerVoices() = viewModelScope.launch {
        val picker = _uiState.value.voicePicker ?: return@launch
        _uiState.update { it.copy(voicePicker = it.voicePicker?.copy(loading = true, error = null)) }
        runCatching { fetchNativeVoices(picker.engineType, picker.engineId) }
            .onSuccess { native ->
                pickerNative = native
                _uiState.update { state ->
                    state.copy(
                        voicePicker = state.voicePicker?.copy(
                            loading = false,
                            canRefreshCatalog = canRefreshCatalog(picker.engineType, picker.engineId),
                        )
                    )
                }
                refreshPickerVoices()
            }
            .onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        voicePicker = state.voicePicker?.copy(
                            loading = false,
                            error = formatError(error),
                        )
                    )
                }
            }
    }

    private fun refreshVoiceCatalog() {
        if (_uiState.value.voicePicker != null) loadPickerVoices()
    }

    /** 重算弹框的“跟随全局”标记与音色选项（不改变已打开的目标范围）。 */
    private fun refreshPickerVoices() {
        val picker = _uiState.value.voicePicker ?: return
        val selectedSpeaker = selectedSpeakerFor(picker.engineType, picker.engineId, picker.markScope)
        _uiState.update { state ->
            state.copy(
                voicePicker = state.voicePicker?.copy(
                    followGlobal = bookSelection() == null,
                    voices = buildVoiceOptions(
                        picker.engineType,
                        picker.engineId,
                        pickerNative,
                        selectedSpeaker,
                    ).toImmutableList(),
                )
            )
        }
    }

    /** 独立开关某个目标范围（全局 / 本书可同时打开）。 */
    private fun toggleVoiceScope(scope: CloudTtsScope) {
        val picker = _uiState.value.voicePicker ?: return
        val globalTarget = if (scope == CloudTtsScope.Global) !picker.globalTarget else picker.globalTarget
        val bookTarget = if (scope == CloudTtsScope.Book) !picker.bookTarget else picker.bookTarget
        val markScope = if (bookTarget) CloudTtsScope.Book else CloudTtsScope.Global
        val selectedSpeaker = selectedSpeakerFor(picker.engineType, picker.engineId, markScope)
        _uiState.update { state ->
            state.copy(
                voicePicker = state.voicePicker?.copy(
                    globalTarget = globalTarget,
                    bookTarget = bookTarget,
                    voices = buildVoiceOptions(
                        picker.engineType,
                        picker.engineId,
                        pickerNative,
                        selectedSpeaker,
                    ).toImmutableList(),
                )
            )
        }
    }

    private fun selectedSpeakerFor(
        engineType: String,
        engineId: String,
        scope: CloudTtsScope,
    ): String? {
        val selection = if (scope == CloudTtsScope.Book) bookSelection() else globalSelection()
        return selection?.takeIf { matches(it, engineType, engineId) }?.speakerId
    }

    private fun buildVoiceOptions(
        engineType: String,
        engineId: String,
        native: List<TtsNativeVoice>,
        selectedSpeaker: String?,
    ): List<CloudTtsVoiceOptionUi> {
        val options = LinkedHashMap<String, CloudTtsVoiceOptionUi>()
        native.forEach { voice ->
            options[voice.id] = CloudTtsVoiceOptionUi(
                speakerId = voice.id,
                label = voice.displayName.ifBlank { voice.id },
                description = listOf(voice.locale, voice.gender)
                    .filter(String::isNotBlank).joinToString(" · "),
                selected = voice.id == selectedSpeaker,
            )
        }
        voices.asSequence()
            .filter { it.engineType == engineType && it.engineId == engineId }
            .forEach { preset ->
                val userManaged = preset.managedBy == ReadAloudVoice.MANAGED_BY_USER
                val existing = options[preset.speakerId]
                options[preset.speakerId] = CloudTtsVoiceOptionUi(
                    speakerId = preset.speakerId,
                    label = preset.displayName.takeIf(String::isNotBlank)
                        ?: existing?.label
                        ?: defaultVoiceLabel(engineType),
                    description = existing?.description.orEmpty(),
                    selected = preset.speakerId == selectedSpeaker,
                    editable = userManaged,
                    deletable = userManaged,
                    presetId = preset.id.takeIf { userManaged },
                )
            }
        return options.values.toList()
    }

    private suspend fun fetchNativeVoices(
        engineType: String,
        engineId: String,
    ): List<TtsNativeVoice> {
        val voices = when (engineType) {
            ReadAloudVoice.ENGINE_SYSTEM -> systemCatalog.getVoices(engineId)
            ReadAloudVoice.ENGINE_CLOUD -> {
                val engine = engines.firstOrNull { it.id == engineId }
                if (engine == null) {
                    emptyList()
                } else {
                    synthesizer.fetchVoices(engine).map { descriptor ->
                        TtsNativeVoice(
                            id = descriptor.id,
                            engineId = engineId,
                            displayName = descriptor.displayName,
                            locale = descriptor.locale,
                            gender = descriptor.gender,
                            styles = descriptor.styles,
                            roles = descriptor.roles,
                        )
                    }
                }
            }

            ReadAloudVoice.ENGINE_HTTP -> listOf(
                TtsNativeVoice(
                    id = "",
                    engineId = engineId,
                    displayName = application.getString(R.string.cloud_tts_engine_default_voice),
                )
            )

            else -> emptyList()
        }
        // 部分系统/云端引擎会返回同名音色，作为列表 key 会重复导致崩溃。
        return voices.distinctBy { it.id }
    }

    private fun canRefreshCatalog(engineType: String, engineId: String): Boolean = when (engineType) {
        ReadAloudVoice.ENGINE_SYSTEM -> true
        ReadAloudVoice.ENGINE_CLOUD -> engines.firstOrNull { it.id == engineId }
            ?.provider?.profile?.voiceCatalogType == CloudTtsVoiceCatalogType.Remote

        else -> false
    }

    private fun selectVoice(speakerId: String) = viewModelScope.launch {
        val picker = _uiState.value.voicePicker ?: return@launch
        val engineType = picker.engineType
        val engineId = picker.engineId
        val native = pickerNative.firstOrNull { it.id == speakerId }
        if (engineType == ReadAloudVoice.ENGINE_CLOUD) {
            ensureCloudPreset(engineId, speakerId, native)
        }
        val label = engineTitle(engineType, engineId)
        val value = ReadAloudEngineSelection.serialize(
            ReadAloudEngineSelection(
                engineType = engineType,
                engineId = engineId,
                speakerId = speakerId,
                displayName = label,
            )
        )
        // 设到所有已打开的目标范围；都没打开时按全局兜底。
        val targets = buildList {
            if (picker.globalTarget) add(CloudTtsScope.Global)
            if (picker.bookTarget) add(CloudTtsScope.Book)
        }.ifEmpty { listOf(CloudTtsScope.Global) }
        targets.forEach { scope ->
            applySelection(value, forBook = scope == CloudTtsScope.Book)
        }
        _uiState.update { it.copy(voicePicker = null) }
        toast(application.getString(R.string.read_aloud_default_engine_updated))
    }

    private suspend fun ensureCloudPreset(
        engineId: String,
        speakerId: String,
        native: TtsNativeVoice?,
    ) {
        val exists = voices.any {
            it.engineType == ReadAloudVoice.ENGINE_CLOUD &&
                it.engineId == engineId && it.speakerId == speakerId
        }
        if (exists) return
        val now = System.currentTimeMillis()
        voiceGateway.upsertVoice(
            ReadAloudVoice(
                id = SpeechIdentity.voiceId(ReadAloudVoice.ENGINE_CLOUD, engineId, speakerId),
                engineType = ReadAloudVoice.ENGINE_CLOUD,
                engineId = engineId,
                speakerId = speakerId,
                displayName = native?.displayName?.takeIf(String::isNotBlank) ?: speakerId,
                traitsJson = GSON.toJson(
                    CloudTtsVoiceConfig(
                        locale = native?.locale.orEmpty(),
                        format = defaultFormat(ReadAloudVoice.ENGINE_CLOUD, engineId),
                    )
                ),
                emotionCatalogJson = GSON.toJson(native?.styles.orEmpty()),
                managedBy = ReadAloudVoice.MANAGED_BY_USER,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    // --- 引擎/音色编辑 ---

    private fun editEngine(id: String) {
        val engine = engines.firstOrNull { it.id == id } ?: return
        _uiState.update { it.copy(
            engineEditor = CloudTtsEngineEditorUi(
                editingEngineId = engine.id,
                name = engine.name,
                provider = engine.provider.storageValue,
                baseUrl = engine.baseUrl,
                apiKey = engine.apiKey,
                secretKey = engine.secretKey,
                region = engine.region,
                appId = engine.appId,
                model = engine.model,
                optionsJson = engine.optionsJson,
            ),
        ) }
    }

    private fun addVoicePreset() {
        val picker = _uiState.value.voicePicker ?: return
        val engineType = picker.engineType
        val engineId = picker.engineId
        _uiState.update { state -> state.copy(
            voicePicker = null,
            voiceEditor = TtsVoicePresetEditorUi(
                engineType = engineType,
                engineId = engineId,
                engineName = picker.engineTitle,
                speed = if (engineType == ReadAloudVoice.ENGINE_SYSTEM) "" else "1.0",
                pitch = if (engineType == ReadAloudVoice.ENGINE_SYSTEM) "" else "1.0",
                format = defaultFormat(engineType, engineId),
                formatOptions = formatOptions(engineType, engineId),
            ),
            discoveredVoices = persistentListOf(),
        ) }
        discoverVoices()
    }

    private fun editVoice(id: String) {
        val voice = voices.firstOrNull {
            it.id == id && it.managedBy == ReadAloudVoice.MANAGED_BY_USER
        } ?: return
        val engineType = voice.engineType
        val engineId = voice.engineId
        val cloudConfig = if (engineType == ReadAloudVoice.ENGINE_CLOUD) {
            runCatching {
                GSON.fromJson(voice.traitsJson, CloudTtsVoiceConfig::class.java)
            }.getOrNull()
        } else null
        val systemConfig = if (engineType == ReadAloudVoice.ENGINE_SYSTEM) {
            runCatching {
                GSON.fromJson(voice.traitsJson, SystemTtsVoiceConfig::class.java)
            }.getOrNull()
        } else null
        _uiState.update { state -> state.copy(
            voiceEditor = TtsVoicePresetEditorUi(
                editingVoiceId = voice.id,
                engineType = engineType,
                engineId = engineId,
                engineName = engineTitle(engineType, engineId),
                voiceId = voice.speakerId.ifBlank {
                    if (engineType == ReadAloudVoice.ENGINE_HTTP) DEFAULT_ENGINE_VOICE_ID else ""
                },
                voiceName = voice.displayName,
                locale = cloudConfig?.locale.orEmpty(),
                style = cloudConfig?.style.orEmpty(),
                role = cloudConfig?.role.orEmpty(),
                instructions = cloudConfig?.instructions.orEmpty(),
                automaticEmotion = cloudConfig?.automaticEmotion != false,
                characterPersonality = cloudConfig?.characterPersonality != false,
                thoughtPerformance = cloudConfig?.thoughtPerformance != false,
                speed = when {
                    cloudConfig != null -> cloudConfig.speed.toString()
                    systemConfig?.speechRate != null -> systemConfig.speechRate.toString()
                    else -> ""
                },
                pitch = when {
                    cloudConfig != null -> cloudConfig.pitch.toString()
                    systemConfig?.pitch != null -> systemConfig.pitch.toString()
                    else -> ""
                },
                volume = cloudConfig?.volume?.toString() ?: "1.0",
                format = cloudConfig?.format ?: defaultFormat(engineType, engineId),
                formatOptions = formatOptions(engineType, engineId),
            ),
            discoveredVoices = persistentListOf(),
        ) }
        discoverVoices()
    }

    private fun selectEditorVoice(id: String) {
        if (_uiState.value.voiceEditor?.editingVoiceId != null) {
            return toast(application.getString(R.string.cloud_tts_cannot_change_native_voice))
        }
        val voice = _uiState.value.discoveredVoices.firstOrNull { it.id == id } ?: return
        _uiState.update { state -> state.copy(voiceEditor = state.voiceEditor?.copy(
            voiceId = voice.id,
            voiceName = voice.label.substringBefore(" · "),
            locale = voice.locale,
            style = "",
            role = "",
        )) }
    }

    private fun discoverVoices() = viewModelScope.launch {
        val editor = _uiState.value.voiceEditor ?: return@launch
        _uiState.update { it.copy(discovering = true) }
        runCatching {
            if (editor.engineType == ReadAloudVoice.ENGINE_CLOUD &&
                engines.none { it.id == editor.engineId }
            ) {
                error(application.getString(R.string.cloud_tts_engine_missing))
            }
            fetchNativeVoices(editor.engineType, editor.engineId).map { voice ->
                CloudTtsDiscoveredVoiceUi(
                    id = if (editor.engineType == ReadAloudVoice.ENGINE_HTTP) {
                        DEFAULT_ENGINE_VOICE_ID
                    } else {
                        voice.id
                    },
                    label = listOf(voice.displayName, voice.locale, voice.gender)
                        .filter(String::isNotBlank).joinToString(" · "),
                    locale = voice.locale,
                    styles = voice.styles.toImmutableList(),
                    roles = voice.roles.toImmutableList(),
                )
            }
        }.onSuccess { catalog ->
            _uiState.update { state ->
                val defaultVoice = catalog.singleOrNull()
                    ?.takeIf { editor.engineType == ReadAloudVoice.ENGINE_HTTP }
                state.copy(
                    discoveredVoices = catalog.toImmutableList(),
                    voiceEditor = if (defaultVoice == null) state.voiceEditor else {
                        state.voiceEditor?.copy(
                            voiceId = defaultVoice.id,
                            voiceName = editor.engineName,
                        )
                    },
                )
            }
            toast(application.getString(R.string.cloud_tts_voice_count, catalog.size))
        }.onFailure { showError(formatError(it)) }
        _uiState.update { it.copy(discovering = false) }
    }

    private fun saveEngine() = viewModelScope.launch {
        val engine = buildEngine() ?: return@launch
        engineGateway.upsert(engine)
        _uiState.update { it.copy(engineEditor = null) }
        toast(application.getString(R.string.cloud_tts_engine_saved))
    }

    private fun saveVoice() = viewModelScope.launch {
        val voice = buildVoice() ?: return@launch
        voiceGateway.upsertVoice(voice)
        _uiState.value.voiceEditor?.editingVoiceId
            ?.takeIf { it != voice.id }
            ?.let { oldId -> voices.firstOrNull { it.id == oldId } }
            ?.let { oldVoice -> voiceGateway.deleteVoice(oldVoice) }
        _uiState.update { it.copy(voiceEditor = null, discoveredVoices = persistentListOf()) }
        toast(application.getString(R.string.cloud_tts_voice_saved))
    }

    private fun testEngine() = viewModelScope.launch {
        val engine = buildEngine() ?: return@launch
        _uiState.update { it.copy(testing = true) }
        runCatching {
            val voice = synthesizer.fetchVoices(engine).firstOrNull()
                ?: error(application.getString(R.string.cloud_tts_no_test_voice))
            val file = File(application.cacheDir, "cloud_tts_preview/${engine.id.hashCode()}.audio")
            val request = CloudTtsSynthesisRequest(
                text = application.getString(R.string.cloud_tts_connection_test_text),
                voiceId = voice.id,
            )
            check(synthesizer.synthesize(engine, request, file)) {
                application.getString(R.string.cloud_tts_no_audio)
            }
        }.onSuccess { toast(application.getString(R.string.cloud_tts_connection_success)) }
            .onFailure { showError(formatError(it)) }
        _uiState.update { it.copy(testing = false) }
    }

    private fun preview() = viewModelScope.launch {
        val editor = _uiState.value.voiceEditor ?: return@launch
        if (editor.voiceId.isBlank()) {
            return@launch toast(application.getString(R.string.cloud_tts_select_voice_first))
        }
        _uiState.update { it.copy(testing = true) }
        runCatching {
            val file = File(application.cacheDir, "cloud_tts_preview/${editor.engineId.hashCode()}.audio")
            if (editor.engineType == ReadAloudVoice.ENGINE_SYSTEM) {
                check(systemSynthesizer.synthesize(
                    engine = editor.engineId,
                    voiceName = editor.voiceId,
                    text = application.getString(R.string.system_tts_preview_text),
                    output = file,
                    speechRate = editor.speed.toFloatOrNull() ?: 1f,
                )) { application.getString(R.string.system_tts_preview_failed) }
            } else {
                val engine = engines.firstOrNull { it.id == editor.engineId }
                    ?: error(application.getString(R.string.cloud_tts_engine_missing))
                val request = buildRequest(editor, editor.voiceId) ?: return@launch
                check(synthesizer.synthesize(engine, request, file)) {
                    application.getString(R.string.cloud_tts_no_audio)
                }
            }
            file
        }.onSuccess { _effects.tryEmit(CloudTtsEffect.PlayPreview(it.absolutePath)) }
            .onFailure { showError(formatError(it)) }
        _uiState.update { it.copy(testing = false) }
    }

    private fun buildEngine(): CloudTtsEngine? {
        val editor = _uiState.value.engineEditor ?: return null
        val provider = CloudTtsProviderType.entries.firstOrNull {
            it.storageValue == editor.provider
        } ?: return null.also { toast(application.getString(R.string.cloud_tts_select_provider)) }
        if (editor.name.isBlank()) return null.also { toast(application.getString(R.string.cloud_tts_engine_name_required)) }
        if (editor.apiKey.isBlank()) return null.also { toast(application.getString(R.string.cloud_tts_api_key_required)) }
        if (provider == CloudTtsProviderType.AwsPolly && editor.secretKey.isBlank()) {
            return null.also { toast(application.getString(R.string.cloud_tts_aws_secret_required)) }
        }
        if (provider in setOf(CloudTtsProviderType.AzureSpeech, CloudTtsProviderType.AwsPolly) &&
            editor.region.isBlank() && editor.baseUrl.isBlank()
        ) return null.also { toast(application.getString(R.string.cloud_tts_region_or_url_required)) }
        if (provider == CloudTtsProviderType.Volcengine && editor.appId.isBlank()) {
            return null.also { toast(application.getString(R.string.cloud_tts_volc_app_id_required)) }
        }
        val old = editor.editingEngineId?.let { id -> engines.firstOrNull { it.id == id } }
        val now = System.currentTimeMillis()
        return CloudTtsEngine(
            id = old?.id ?: Uuid.random().toString(),
            name = editor.name.trim(),
            provider = provider,
            baseUrl = editor.baseUrl.trim(),
            apiKey = editor.apiKey.trim(),
            secretKey = editor.secretKey.trim(),
            region = editor.region.trim(),
            appId = editor.appId.trim(),
            model = editor.model.trim(),
            optionsJson = editor.optionsJson.trim().ifBlank { "{}" },
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
        )
    }

    private fun buildVoice(): ReadAloudVoice? {
        val editor = _uiState.value.voiceEditor ?: return null
        if (editor.voiceId.isBlank()) return null.also { toast(application.getString(R.string.cloud_tts_select_voice_first)) }
        val speakerId = editor.voiceId.takeUnless { it == DEFAULT_ENGINE_VOICE_ID }.orEmpty()
        val request = if (editor.engineType == ReadAloudVoice.ENGINE_CLOUD) {
            buildRequest(editor, editor.voiceId) ?: return null
        } else null
        val systemConfig = if (editor.engineType == ReadAloudVoice.ENGINE_SYSTEM) {
            val speechRate = editor.speed.takeIf(String::isNotBlank)?.toFloatOrNull()
            val pitch = editor.pitch.takeIf(String::isNotBlank)?.toFloatOrNull()
            if (editor.speed.isNotBlank() && speechRate == null ||
                editor.pitch.isNotBlank() && pitch == null
            ) return null.also { toast(application.getString(R.string.cloud_tts_system_params_numeric)) }
            if (speechRate != null && (!speechRate.isFinite() || speechRate !in 0.1f..4f) ||
                pitch != null && (!pitch.isFinite() || pitch !in 0.1f..4f)
            ) return null.also { toast(application.getString(R.string.cloud_tts_system_params_range)) }
            SystemTtsVoiceConfig(speechRate = speechRate, pitch = pitch)
        } else null
        val now = System.currentTimeMillis()
        val id = SpeechIdentity.voiceId(editor.engineType, editor.engineId, speakerId)
        val old = editor.editingVoiceId?.let { editingId -> voices.firstOrNull { it.id == editingId } }
            ?: voices.firstOrNull { it.id == id }
        return ReadAloudVoice(
            id = id,
            engineType = editor.engineType,
            engineId = editor.engineId,
            speakerId = speakerId.trim(),
            displayName = editor.voiceName.trim().ifBlank { engineTitle(editor.engineType, editor.engineId) },
            traitsJson = request?.let { value -> GSON.toJson(CloudTtsVoiceConfig(
                locale = value.locale,
                style = value.style,
                role = value.role,
                instructions = value.instructions,
                automaticEmotion = editor.automaticEmotion,
                characterPersonality = editor.characterPersonality,
                thoughtPerformance = editor.thoughtPerformance,
                speed = value.speed,
                pitch = value.pitch,
                volume = value.volume,
                format = value.format,
            )) } ?: systemConfig?.let(GSON::toJson) ?: "{}",
            emotionCatalogJson = GSON.toJson(
                _uiState.value.discoveredVoices.firstOrNull { it.id == editor.voiceId }?.styles.orEmpty()
            ),
            createdAt = old?.createdAt ?: now,
            revision = (old?.revision ?: 0L) + 1L,
            updatedAt = now,
        )
    }

    private fun buildRequest(editor: TtsVoicePresetEditorUi, voiceId: String): CloudTtsSynthesisRequest? {
        val speed = editor.speed.toFloatOrNull()
        val pitch = editor.pitch.toFloatOrNull()
        val volume = editor.volume.toFloatOrNull()
        if (speed == null || pitch == null || volume == null) {
            toast(application.getString(R.string.cloud_tts_numeric_voice_parameters))
            return null
        }
        return CloudTtsSynthesisRequest(
            text = application.getString(R.string.cloud_tts_voice_preview_text),
            voiceId = voiceId,
            locale = editor.locale,
            style = editor.style,
            role = editor.role,
            instructions = editor.instructions,
            speed = speed,
            pitch = pitch,
            volume = volume,
            format = editor.format.ifBlank { "mp3" },
        )
    }

    private fun defaultFormat(engineType: String, engineId: String): String =
        if (engineType == ReadAloudVoice.ENGINE_CLOUD) {
            engines.firstOrNull { it.id == engineId }
                ?.provider?.profile?.audioFormats?.firstOrNull().orEmpty().ifBlank { "mp3" }
        } else {
            ""
        }

    private fun formatOptions(
        engineType: String,
        engineId: String,
    ): kotlinx.collections.immutable.ImmutableList<String> =
        if (engineType == ReadAloudVoice.ENGINE_CLOUD) {
            engines.firstOrNull { it.id == engineId }
                ?.provider?.profile?.audioFormats.orEmpty().toImmutableList()
        } else {
            persistentListOf()
        }

    // --- 默认引擎写入 ---

    private fun setBookContext(value: String?) = viewModelScope.launch {
        bookUrl = value
        bookSelectionRaw = withContext(Dispatchers.IO) {
            value?.let(appDb.bookDao::getBook)?.getTtsEngine()
        }
        rebuildEngineItems()
    }

    /** 写入当前书的引擎覆盖；null 表示清除覆盖（跟随全局）。 */
    private suspend fun writeBookSelection(value: String?) {
        val url = bookUrl ?: return
        ReadBook.book?.takeIf { it.bookUrl == url }?.setTtsEngine(value)
        withContext(Dispatchers.IO) {
            appDb.bookDao.getBook(url)?.let { book ->
                book.setTtsEngine(value)
                appDb.bookDao.update(book)
            }
        }
        bookSelectionRaw = value
    }

    private suspend fun applySelection(value: String, forBook: Boolean) {
        if (forBook && bookUrl != null) {
            writeBookSelection(value)
        } else {
            // 全局与本书互相独立：设置全局不清除本书覆盖。
            readAloudSettingsGateway.update { it.copy(ttsEngine = value) }
        }
        ReadAloud.upReadAloudClass()
        rebuildEngineItems()
    }

    private fun clearBookSelection() = viewModelScope.launch {
        if (bookUrl == null) return@launch
        writeBookSelection(null)
        ReadAloud.upReadAloudClass()
        // 本书不再覆盖：关闭「本书」目标开关，并重算“跟随全局”与音色标记。
        _uiState.update { state ->
            state.copy(voicePicker = state.voicePicker?.copy(bookTarget = false))
        }
        refreshPickerVoices()
        rebuildEngineItems()
    }

    private fun deleteEngine(id: String) = viewModelScope.launch {
        voices.filter { it.engineId == id }.forEach { voiceGateway.deleteVoice(it) }
        engines.firstOrNull { it.id == id }?.let { engineGateway.delete(it) }
        if (matches(globalSelection(), ReadAloudVoice.ENGINE_CLOUD, id)) {
            readAloudSettingsGateway.update { it.copy(ttsEngine = null) }
            ReadAloud.upReadAloudClass()
        }
    }

    private fun requestDeleteVoice(id: String) {
        val voice = voices.firstOrNull {
            it.id == id && it.managedBy == ReadAloudVoice.MANAGED_BY_USER
        } ?: return
        _uiState.update {
            it.copy(
                activeDialog = CloudTtsDialog.DeleteVoice(
                    voice.id,
                    voice.displayName
                )
            )
        }
    }

    private fun confirmDeleteVoice() = viewModelScope.launch {
        val id = (_uiState.value.activeDialog as? CloudTtsDialog.DeleteVoice)?.id ?: return@launch
        val voice = voices.firstOrNull {
            it.id == id && it.managedBy == ReadAloudVoice.MANAGED_BY_USER
        }
        voice?.let { voiceGateway.deleteVoice(it) }
        val global = globalSelection()
        if (voice != null && matches(global, voice.engineType, voice.engineId) &&
            global?.speakerId == voice.speakerId
        ) {
            readAloudSettingsGateway.update { it.copy(ttsEngine = null) }
            ReadAloud.upReadAloudClass()
        }
        _uiState.update { it.copy(activeDialog = null) }
        rebuildEngineItems()
    }

    // --- HTTP 引擎 ---

    private fun editHttpTts(engineId: String?) = viewModelScope.launch {
        val value = withContext(Dispatchers.IO) {
            engineId?.toLongOrNull()?.let(appDb.httpTTSDao::get) ?: HttpTTS()
        }
        _uiState.update { it.copy(httpTtsEditor = value) }
    }

    private fun saveHttpTts(value: HttpTTS) = viewModelScope.launch {
        withContext(Dispatchers.IO) { appDb.httpTTSDao.insert(value) }
        _uiState.update { it.copy(httpTtsEditor = null) }
        toast(application.getString(R.string.success))
    }

    private fun deleteHttpTts(engineId: String) = viewModelScope.launch {
        val id = engineId.toLongOrNull() ?: return@launch
        withContext(Dispatchers.IO) { appDb.httpTTSDao.get(id)?.let(appDb.httpTTSDao::delete) }
        if (matches(globalSelection(), ReadAloudVoice.ENGINE_HTTP, engineId)) {
            readAloudSettingsGateway.update { it.copy(ttsEngine = null) }
            ReadAloud.upReadAloudClass()
        }
    }

    private fun importHttpTtsFile(uri: Uri) = viewModelScope.launch {
        val text = withContext(Dispatchers.IO) {
            application.contentResolver.openInputStream(uri)?.bufferedReader()
                ?.use { it.readText() }
        }
        if (!text.isNullOrBlank()) importHttpTtsSource(text)
    }

    private fun importHttpTtsSource(text: String) = viewModelScope.launch {
        _uiState.update { it.copy(httpTtsImportState = BaseImportUiState.Loading) }
        runCatching {
            val source = text.trim()
            val list = withContext(Dispatchers.IO) { parseHttpTtsSource(source) }
            val items = withContext(Dispatchers.IO) {
                list.map { value ->
                    val old = appDb.httpTTSDao.get(value.id)
                    val status = when {
                        old == null -> ImportStatus.New
                        value.lastUpdateTime > old.lastUpdateTime -> ImportStatus.Update
                        else -> ImportStatus.Existing
                    }
                    ImportItemWrapper(
                        data = value,
                        oldData = old,
                        isSelected = status != ImportStatus.Existing,
                        status = status,
                    )
                }
            }
            if (items.isEmpty()) throw NoStackTraceException(application.getString(R.string.wrong_format))
            BaseImportUiState.Success(source, items)
        }.onSuccess { result -> _uiState.update { it.copy(httpTtsImportState = result) } }
            .onFailure { error ->
                _uiState.update {
                    it.copy(
                        httpTtsImportState = BaseImportUiState.Error(
                            error.localizedMessage ?: application.getString(R.string.wrong_format)
                        )
                    )
                }
            }
    }

    private suspend fun parseHttpTtsSource(text: String): List<HttpTTS> = when {
        text.isHttpTtsImportUri() -> Uri.parse(text).getQueryParameter("src")
            ?.let { parseHttpTtsSource(it) }
            ?: throw NoStackTraceException(application.getString(R.string.wrong_format))

        text.isJsonObject() -> listOf(HttpTTS.fromJson(text).getOrThrow())
        text.isJsonArray() -> HttpTTS.fromJsonArray(text).getOrThrow()
        text.isDataUrl() -> {
            val data = AppPattern.dataUriRegex.find(text)?.groupValues?.getOrNull(1)
                ?: throw NoStackTraceException(application.getString(R.string.wrong_format))
            parseHttpTtsSource(Base64.decode(data, Base64.DEFAULT).toString(Charsets.UTF_8))
        }

        text.isAbsUrl() -> parseHttpTtsSource(okHttpClient.newCallResponseBody {
            if (text.endsWith("#requestWithoutUA")) {
                url(text.substringBeforeLast("#requestWithoutUA")); header(AppConst.UA_NAME, "null")
            } else url(text)
        }.decompressed().text())

        else -> throw NoStackTraceException(application.getString(R.string.wrong_format))
    }

    private fun toggleHttpTtsImportSelection(index: Int) {
        val state =
            _uiState.value.httpTtsImportState as? BaseImportUiState.Success<HttpTTS> ?: return
        if (index !in state.items.indices) return
        val items = state.items.toMutableList()
        items[index] = items[index].copy(isSelected = !items[index].isSelected)
        _uiState.update { it.copy(httpTtsImportState = state.copy(items = items)) }
    }

    private fun toggleHttpTtsImportAll(selected: Boolean) {
        val state =
            _uiState.value.httpTtsImportState as? BaseImportUiState.Success<HttpTTS> ?: return
        _uiState.update {
            it.copy(
                httpTtsImportState = state.copy(
                items = state.items.map { item -> item.copy(isSelected = selected) }
            ))
        }
    }

    private fun updateHttpTtsImportItem(index: Int, value: HttpTTS) {
        val state =
            _uiState.value.httpTtsImportState as? BaseImportUiState.Success<HttpTTS> ?: return
        if (index !in state.items.indices) return
        val items = state.items.toMutableList()
        items[index] = items[index].copy(data = value)
        _uiState.update {
            it.copy(
                httpTtsImportState = state.copy(
                    items = items,
                    version = state.version + 1
                )
            )
        }
    }

    private fun saveImportedHttpTts() = viewModelScope.launch {
        val state = _uiState.value.httpTtsImportState as? BaseImportUiState.Success<HttpTTS>
            ?: return@launch
        val selected = state.items.filter { it.isSelected }.map { it.data }
        if (selected.isEmpty()) return@launch
        withContext(Dispatchers.IO) { appDb.httpTTSDao.insert(*selected.toTypedArray()) }
        _uiState.update { it.copy(httpTtsImportState = BaseImportUiState.Idle) }
        toast(application.getString(R.string.success))
    }

    private fun exportHttpTtsFile(uri: Uri) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val json = GSON.toJson(appDb.httpTTSDao.all)
            application.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
        }
        toast(application.getString(R.string.export_success))
    }

    private fun exportHttpTtsUrl() = viewModelScope.launch {
        val url = withContext(Dispatchers.IO) {
            uploadRepository.upload(
                "httpTTS.json",
                GSON.toJson(appDb.httpTTSDao.all),
                "application/json"
            )
        }
        _effects.tryEmit(CloudTtsEffect.CopyText(url, application.getString(R.string.copy_url)))
    }

    private fun toast(message: String) { _effects.tryEmit(CloudTtsEffect.ShowToast(message)) }
    private fun showError(message: String) { _uiState.update { it.copy(activeDialog = CloudTtsDialog.Error(message)) } }
    private fun copyError() {
        val message = (_uiState.value.activeDialog as? CloudTtsDialog.Error)?.message ?: return
        _effects.tryEmit(
            CloudTtsEffect.CopyText(
                message,
                application.getString(R.string.cloud_tts_error_copied),
            )
        )
    }
    private fun formatError(error: Throwable): String = buildString {
        append(application.getString(R.string.cloud_tts_request_failed))
        generateSequence(error) { it.cause }.mapNotNull { cause ->
            cause.localizedMessage?.takeIf(String::isNotBlank)?.let {
                "${cause::class.java.simpleName}: $it"
            }
        }.distinct().toList().takeIf { it.isNotEmpty() }?.let {
            append("\n\n").append(it.joinToString("\nCaused by: "))
        }
    }
}

private fun String.isHttpTtsImportUri(): Boolean {
    val uri = runCatching { Uri.parse(this) }.getOrNull() ?: return false
    return uri.scheme in setOf("legado", "yuedu") &&
            uri.host == "import" && uri.path.equals("/httpTTS", ignoreCase = true)
}

private const val DEFAULT_ENGINE_VOICE_ID = "__engine_default__"
