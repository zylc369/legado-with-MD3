package io.legado.app.domain.model.settings

import com.opensecurity.customtts.model.CustomTtsVoiceParams
import com.opensecurity.customtts.model.HybridTtsConfig

/**
 * 定制 TTS 配置（业务设置——与传输层 ConfigStore 独立）。
 *
 * - 首段字数唯一来源 = [segmentStairs][0]（默认 20）
 * - 速度: 混合模式用 [hybrid] 的 fallbackSpeed/customSpeed；非混合模式用
 *   voices[voiceId].speed。两处不叠加。
 */
data class CustomTtsSettings(
    val defaultVoiceId: String = "",
    val voices: Map<String, CustomTtsVoiceParams> = emptyMap(),
    val hybrid: HybridTtsConfig = HybridTtsConfig(),
    val segmentTarget: Int = 80,
    val segmentStairs: List<Int> = listOf(20, 40, 60, 80),
)
