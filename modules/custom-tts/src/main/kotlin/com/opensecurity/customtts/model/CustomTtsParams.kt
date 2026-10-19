package com.opensecurity.customtts.model

/**
 * 定制 TTS 参数校验与请求体构造（纯函数，与远端 /api/tts 的 TtsRequest 对齐）。
 */
object CustomTtsParams {

    const val SPEED_MIN = 0.5f
    const val SPEED_MAX = 3.0f
    const val SEED_MIN = -1
    const val SEED_MAX = 99_999
    const val INSTRUCT_MAX = 200
    const val VOICE_ID_MAX = 64
    const val DISPLAY_NAME_MAX = 64
    const val MODE_DEFAULT = ""
    const val MODE_INSTRUCT = "instruct"

    /** 校验并返回规范化参数；非法返回 Result.failure（消息可直接提示用户）。 */
    fun validate(params: CustomTtsVoiceParams): Result<CustomTtsVoiceParams> = runCatching {
        require(params.voiceId.length <= VOICE_ID_MAX) { "音色 ID 超长（≤$VOICE_ID_MAX）" }
        require(params.displayName.length <= DISPLAY_NAME_MAX) {
            "音色名称超长（≤$DISPLAY_NAME_MAX）"
        }
        require(params.speed in SPEED_MIN..SPEED_MAX) { "速度需在 $SPEED_MIN..$SPEED_MAX" }
        require(params.seed in SEED_MIN..SEED_MAX) { "种子需在 $SEED_MIN..$SEED_MAX" }
        require(params.instruct.length <= INSTRUCT_MAX) { "朗读指令需 ≤$INSTRUCT_MAX 字" }
        require(params.mode == MODE_DEFAULT || params.mode == MODE_INSTRUCT) {
            "模式非法: ${params.mode}"
        }
        params
    }

    /** 构造远端 /api/tts 请求体（结构化 map——协议层 msgpack 编码）。 */
    fun toRequestMap(params: CustomTtsVoiceParams, text: String): Map<String, Any?> = mapOf(
        "text" to text,
        "voice" to params.voiceId,
        "speed" to params.speed,
        "instruct" to params.instruct,
        "seed" to params.seed,
        "mode" to params.mode,
    )
}
