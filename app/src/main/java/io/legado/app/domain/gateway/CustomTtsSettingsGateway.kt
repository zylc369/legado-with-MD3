package io.legado.app.domain.gateway

import io.legado.app.domain.model.settings.CustomTtsSettings
import kotlinx.coroutines.flow.StateFlow

/** 定制 TTS 设置网关（UI/服务经此读写，避免直连存储）。 */
interface CustomTtsSettingsGateway {
    val currentSettings: CustomTtsSettings
    val settings: StateFlow<CustomTtsSettings>

    /** 原子更新：`update { current -> current.copy(...) }`。 */
    suspend fun update(transform: (CustomTtsSettings) -> CustomTtsSettings)
}
