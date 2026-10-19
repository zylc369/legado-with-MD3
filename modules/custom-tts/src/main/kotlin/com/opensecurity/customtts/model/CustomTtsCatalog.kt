package com.opensecurity.customtts.model

/** 定制引擎在 Legado 侧的固定标识（HttpTTS 记录 + 网关 URL 模板）。 */
object CustomTtsCatalog {

    /** HttpTTS 固定主键（避免与用户自建源冲突；云引擎运行时占用了 Long.MIN_VALUE）。 */
    const val ENGINE_ID: Long = Long.MIN_VALUE + 1

    const val ENGINE_NAME = "定制朗读引擎"

    /** Legado HttpTTS URL 模板（不含 {{speakSpeed}}——速度由 voice traits 以服务端语义注入）。 */
    const val URL_TEMPLATE = "http://127.0.0.1:%d/tts?text={{speakText}}"

    /** 宽松内容类型匹配（服务端返回 audio/wav）。 */
    const val CONTENT_TYPE = "audio/.*"

    fun url(port: Int): String = URL_TEMPLATE.format(port)
}
