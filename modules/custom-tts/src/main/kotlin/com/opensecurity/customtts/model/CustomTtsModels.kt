package com.opensecurity.customtts.model

/** 定制引擎的合成参数（对应服务端 /api/tts 请求体）。 */
data class CustomTtsVoiceParams(
    val voiceId: String = "",
    /** 展示名（服务端音色目录 name；空则用 voiceId）。 */
    val displayName: String = "",
    val speed: Float = 1.0f,
    val instruct: String = "",
    val seed: Int = -1,
    val mode: String = "",
)

/** 分段单元（偏移量用于进度/高亮映射——章节朗读文本流中的字符区间）。 */
data class CustomTtsSegment(
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
)

/** 混合朗读配置。 */
data class HybridTtsConfig(
    /**
     * 首段兜底开关（沿用既有 JSON 键，不迁移）：true = 起播段未就绪立即兜底；
     * false = 起播段阻塞等待远端（超时才落兜底）。策略层不再消费，仅驱动层读取。
     */
    val firstSegmentFallback: Boolean = true,
    /** 本地调速：开启时远端一律按 1.0x 合成，"定制速度"只调本地播放比值。 */
    val localSpeed: Boolean = true,
    val remoteTimeoutMs: Int = 10_000,
    val fallbackEngineType: String = "system",
    val fallbackEngineId: String = "",
    val fallbackSpeakerId: String = "",
    val fallbackSpeed: Float = 1.0f,
    val customSpeed: Float = 1.0f,
)

/** 段级决策结果。 */
enum class HybridTtsDecision { Remote, Fallback }

/** 引擎切换事件（供 UI Toast；防抖由策略内完成）。 */
enum class HybridTtsSwitch { ToFallback, ToRemote }

/** 服务端音色目录条目（/api/tts/voices 响应的解析模型）。 */
data class CustomTtsVoiceEntry(
    val id: String,
    val name: String,
    val gender: String = "",
    val description: String = "",
    val isDefault: Boolean = false,
    val available: Boolean = true,
)
