package io.legado.app.ui.book.readaloud.cloudtts

import android.net.Uri
import androidx.compose.runtime.Stable
import io.legado.app.data.entities.HttpTTS
import io.legado.app.ui.widget.components.importComponents.BaseImportUiState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** 选择朗读默认引擎的范围：全局设置或当前书本。 */
enum class CloudTtsScope { Global, Book }

@Stable
data class CloudTtsUiState(
    /** 是否从某本书进入；为 false 时只能设置全局，不显示「本书」。 */
    val hasBookContext: Boolean = false,
    val cloudEngines: ImmutableList<CloudTtsEngineItemUi> = persistentListOf(),
    val httpEngines: ImmutableList<CloudTtsEngineItemUi> = persistentListOf(),
    val systemEngines: ImmutableList<CloudTtsEngineItemUi> = persistentListOf(),
    val voicePicker: CloudTtsVoicePickerUi? = null,
    val discoveredVoices: ImmutableList<CloudTtsDiscoveredVoiceUi> = persistentListOf(),
    val engineEditor: CloudTtsEngineEditorUi? = null,
    val voiceEditor: TtsVoicePresetEditorUi? = null,
    val httpTtsEditor: HttpTTS? = null,
    val httpTtsImportState: BaseImportUiState<HttpTTS> = BaseImportUiState.Idle,
    val activeDialog: CloudTtsDialog? = null,
    val testing: Boolean = false,
    val discovering: Boolean = false,
)

/** 引擎分组列表里的一项；同一类型按来源分区渲染。 */
@Stable
data class CloudTtsEngineItemUi(
    val engineType: String,
    val engineId: String,
    val title: String,
    val summary: String,
    /** 该引擎当前生效的音色名（未选中引擎时为空）。 */
    val voiceName: String = "",
    val globalSelected: Boolean = false,
    val bookSelected: Boolean = false,
    val editable: Boolean = false,
    val deletable: Boolean = false,
    val loginUrl: String = "",
)

/** 某个引擎的音色选择弹框。 */
@Stable
data class CloudTtsVoicePickerUi(
    val engineType: String,
    val engineId: String,
    val engineTitle: String,
    /** 该引擎当前是否为全局默认 / 本书默认；开/关开关会即时生效。 */
    val globalSelected: Boolean = false,
    val bookSelected: Boolean = false,
    val voices: ImmutableList<CloudTtsVoiceOptionUi> = persistentListOf(),
    val loading: Boolean = false,
    val error: String? = null,
    val canRefreshCatalog: Boolean = false,
)

/** 音色弹框里的一条音色（原生音色或用户预设）。 */
@Stable
data class CloudTtsVoiceOptionUi(
    val speakerId: String,
    val label: String,
    val description: String = "",
    val selected: Boolean = false,
    val editable: Boolean = false,
    val deletable: Boolean = false,
    val presetId: String? = null,
)

@Stable
data class CloudTtsDiscoveredVoiceUi(
    val id: String,
    val label: String,
    val locale: String,
    val styles: ImmutableList<String>,
    val roles: ImmutableList<String>,
)

@Stable
data class CloudTtsEngineEditorUi(
    val editingEngineId: String? = null,
    val name: String = "",
    val provider: String = "mimo",
    val baseUrl: String = "",
    val apiKey: String = "",
    val secretKey: String = "",
    val region: String = "",
    val appId: String = "",
    val model: String = "",
    val optionsJson: String = "{}",
)

@Stable
data class TtsVoicePresetEditorUi(
    val editingVoiceId: String? = null,
    val engineType: String = "",
    val engineId: String = "",
    val engineName: String = "",
    val voiceId: String = "",
    val voiceName: String = "",
    val locale: String = "",
    val style: String = "",
    val role: String = "",
    val instructions: String = "",
    val automaticEmotion: Boolean = true,
    val characterPersonality: Boolean = true,
    val thoughtPerformance: Boolean = true,
    val speed: String = "1.0",
    val pitch: String = "1.0",
    val volume: String = "1.0",
    val format: String = "mp3",
    val formatOptions: ImmutableList<String> = persistentListOf(),
)

sealed interface CloudTtsIntent {
    data class SetBookContext(val bookUrl: String?) : CloudTtsIntent

    data class OpenVoicePicker(val engineType: String, val engineId: String) : CloudTtsIntent
    data class ToggleVoiceScope(val scope: CloudTtsScope) : CloudTtsIntent
    data class SelectVoice(val speakerId: String) : CloudTtsIntent
    data object RefreshVoiceCatalog : CloudTtsIntent
    data object DismissVoicePicker : CloudTtsIntent

    data object AddVoicePreset : CloudTtsIntent
    data class EditVoicePreset(val voiceId: String) : CloudTtsIntent
    data class RequestDeleteVoice(val voiceId: String) : CloudTtsIntent
    data object ConfirmDeleteVoice : CloudTtsIntent
    data class UpdateVoiceEditor(val editor: TtsVoicePresetEditorUi) : CloudTtsIntent
    data object DismissVoiceEditor : CloudTtsIntent
    data class SelectEditorVoice(val voiceId: String) : CloudTtsIntent
    data object DiscoverEditorVoices : CloudTtsIntent

    data object AddEngine : CloudTtsIntent
    data class EditEngine(val engineId: String) : CloudTtsIntent
    data class DeleteEngine(val engineId: String) : CloudTtsIntent
    data class UpdateEngineEditor(val editor: CloudTtsEngineEditorUi) : CloudTtsIntent
    data object DismissEngineEditor : CloudTtsIntent

    data class EditHttpTts(val engineId: String? = null) : CloudTtsIntent
    data class SaveHttpTts(val value: HttpTTS) : CloudTtsIntent
    data class DeleteHttpTts(val engineId: String) : CloudTtsIntent
    data class OpenHttpTtsLogin(val engineId: String) : CloudTtsIntent
    data object DismissHttpTtsEditor : CloudTtsIntent
    data class ImportHttpTtsSource(val text: String) : CloudTtsIntent
    data object ImportHttpTtsFile : CloudTtsIntent
    data class ImportHttpTtsFileSelected(val uri: Uri) : CloudTtsIntent
    data object CancelHttpTtsImport : CloudTtsIntent
    data class ToggleHttpTtsImportSelection(val index: Int) : CloudTtsIntent
    data class ToggleHttpTtsImportAll(val selected: Boolean) : CloudTtsIntent
    data class UpdateHttpTtsImportItem(val index: Int, val value: HttpTTS) : CloudTtsIntent
    data object SaveImportedHttpTts : CloudTtsIntent
    data object ExportHttpTtsFile : CloudTtsIntent
    data class ExportHttpTtsFileSelected(val uri: Uri) : CloudTtsIntent
    data object ExportHttpTtsUrl : CloudTtsIntent
    data object ClearTtsCache : CloudTtsIntent

    data object TestEngine : CloudTtsIntent
    data object Preview : CloudTtsIntent
    data object Save : CloudTtsIntent
    data object DismissError : CloudTtsIntent
    data object CopyError : CloudTtsIntent
    data class ReportError(val message: String) : CloudTtsIntent
}

sealed interface CloudTtsEffect {
    data class ShowToast(val message: String) : CloudTtsEffect
    data class PlayPreview(val path: String) : CloudTtsEffect
    data class CopyText(val text: String, val message: String) : CloudTtsEffect
    data object OpenHttpTtsImportPicker : CloudTtsEffect
    data object OpenHttpTtsExportPicker : CloudTtsEffect
    data class OpenHttpTtsLogin(val engineId: Long) : CloudTtsEffect
}

sealed interface CloudTtsDialog {
    data class Error(val message: String) : CloudTtsDialog
    data class DeleteVoice(val id: String, val title: String) : CloudTtsDialog
}
