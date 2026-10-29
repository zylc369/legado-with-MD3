package io.legado.app.feature.customtts

import androidx.compose.runtime.Immutable
import com.opensecurity.customtts.model.CustomTtsVoiceParams
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** 兜底引擎选项（系统/HTTP/云，排除定制引擎）。 */
@Immutable
data class CustomTtsEngineOptionUi(
    val label: String,
    val engineType: String,
    val engineId: String,
)

/** 兜底音色选项（随引擎类型联动）。 */
@Immutable
data class CustomTtsVoiceOptionUi(
    val label: String,
    val speakerId: String,
)

/** 服务端音色目录条目（配置页展示 + 参数编辑）。 */
@Immutable
data class CustomTtsVoiceItemUi(
    val id: String,
    val name: String,
    val gender: String = "",
    val description: String = "",
    val isDefault: Boolean = false,
    val available: Boolean = true,
    val enabled: Boolean = true,
    val params: CustomTtsVoiceParams = CustomTtsVoiceParams(),
)

@Immutable
data class CustomTtsUiState(
    // 连接（ConfigStore）
    val relayHost: String = "",
    val relayPort: String = "41000",
    val psk: String = "",
    val nodeId: String = "macmini",
    val localPort: String = "18888",
    val gatewayEnabled: Boolean = false,
    val gatewayRunning: Boolean = false,
    val gatewayError: String? = null,
    val testing: Boolean = false,
    val connectionStatus: String = "",
    // 音色目录
    val voices: ImmutableList<CustomTtsVoiceItemUi> = persistentListOf(),
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val defaultVoiceId: String = "",
    // 混合朗读
    val firstSegmentFallback: Boolean = true,
    val fallbackEngineType: String = ReadAloudVoice.ENGINE_SYSTEM,
    val fallbackEngineId: String = "",
    val fallbackSpeakerId: String = "",
    val fallbackSpeed: Float = 1.0f,
    val customSpeed: Float = 1.0f,
    val localSpeed: Boolean = true,
    val remoteTimeoutMs: String = "10000",
    val fallbackEngines: ImmutableList<CustomTtsEngineOptionUi> = persistentListOf(),
    val fallbackVoices: ImmutableList<CustomTtsVoiceOptionUi> = persistentListOf(),
    // 分段设置（首段字数 = stairs[0]）
    val segmentStairsText: String = "20,40,60,80",
    val segmentTarget: String = "80",
    // 保存
    val saving: Boolean = false,
)

sealed interface CustomTtsIntent {
    data class SetRelayHost(val value: String) : CustomTtsIntent
    data class SetRelayPort(val value: String) : CustomTtsIntent
    data class SetPsk(val value: String) : CustomTtsIntent
    data class SetNodeId(val value: String) : CustomTtsIntent
    data class SetLocalPort(val value: String) : CustomTtsIntent
    data class SetGatewayEnabled(val value: Boolean) : CustomTtsIntent
    data object TestConnection : CustomTtsIntent

    data object RefreshVoices : CustomTtsIntent
    data class SetDefaultVoice(val voiceId: String) : CustomTtsIntent

    data class SaveVoiceParams(val voiceId: String, val params: CustomTtsVoiceParams) : CustomTtsIntent
    /** [params] 非空 = 使用未保存的草稿参数试听。 */
    data class PreviewVoice(
        val voiceId: String,
        val params: CustomTtsVoiceParams? = null,
    ) : CustomTtsIntent

    data class SetFirstSegmentFallback(val value: Boolean) : CustomTtsIntent
    data class SetFallbackEngine(val engineType: String, val engineId: String) : CustomTtsIntent
    data class SetFallbackVoice(val speakerId: String) : CustomTtsIntent
    data class SetFallbackSpeed(val value: Float) : CustomTtsIntent
    data class SetCustomSpeed(val value: Float) : CustomTtsIntent
    data class SetLocalSpeed(val value: Boolean) : CustomTtsIntent
    data class SetRemoteTimeout(val value: String) : CustomTtsIntent
    data class SetSegmentStairs(val value: String) : CustomTtsIntent
    data class SetSegmentTarget(val value: String) : CustomTtsIntent

    data object Save : CustomTtsIntent
}

sealed interface CustomTtsEffect {
    data class ShowToast(val message: String) : CustomTtsEffect
    data class PlayPreview(val path: String) : CustomTtsEffect
}
