package io.legado.app.help.readaloud.playback

import io.legado.app.domain.model.readaloud.SystemTtsVoiceConfig
import io.legado.app.utils.GSON
import kotlin.math.roundToInt

/**
 * 朗读语速的唯一换算入口。
 *
 * 核心单位是**倍速**（1.0 = 正常）。设置里存的是“十分之一倍速”的整数
 * （10 = 1.0x、15 = 1.5x、20 = 2.0x），仅作为持久化格式；对外一律用 Float 倍速。
 *
 * 普通系统朗读、HTTP/云、混合远端/兜底都必须从这里取倍速：
 * - 系统 TTS：把返回的倍速交给 setSpeechRate；
 * - ExoPlayer：交给 setPlaybackSpeed；
 * - 服务端：作为 /api/tts 的 speed；
 * - 只有 HTTP 书源源参数 {{speakSpeed}} 在适配层再做一次 0..80 刻度转换。
 */
object ReadAloudSpeed {

    /** 持久化格式：十分之一倍速。10 = 1.0x。 */
    const val DEFAULT_TENTHS = 10
    const val MIN_TENTHS = 5
    const val MAX_TENTHS = 30

    /** 十分之一倍速 -> 倍速。 */
    fun fromTenths(value: Int): Float = value / 10f

    /** 倍速 -> 十分之一倍速。 */
    fun toTenths(multiplier: Float): Int =
        (multiplier * 10f).roundToInt().coerceIn(MIN_TENTHS, MAX_TENTHS)

    /** 文件/流播放端倍速；跟随系统时固定 1.0。 */
    fun globalPlaybackSpeed(globalTenths: Int, followSystem: Boolean): Float =
        if (followSystem) 1f else fromTenths(globalTenths)

    /**
     * 系统 TTS 的实际 setSpeechRate 值。
     *
     * 优先级：显式 override（混合兜底速度） > 音色 traitsJson.speechRate > 全局倍速。
     */
    fun systemTtsRate(
        globalTenths: Int,
        traitsJson: String?,
        override: Float? = null,
        followSystem: Boolean = false,
    ): Float {
        if (override != null) return override.coerceIn(0.1f, 3.0f)
        val config = runCatching {
            GSON.fromJson(traitsJson, SystemTtsVoiceConfig::class.java)
        }.getOrNull()
        val rated = config?.speechRate ?: if (followSystem) 1f else fromTenths(globalTenths)
        return rated.coerceIn(0.1f, 3.0f)
    }

    /**
     * HTTP 书源源参数 {{speakSpeed}} 的 0..85 刻度。
     *
     * 普通路径的契约是 `speakSpeed = httpTts.speed + 5`，而 `httpTts.speed = 5` 表示 1.0x，
     * 因此 1.0x 对应的源参数是 10。混合兜底直接传请求级的值，必须用同一刻度。
     */
    fun httpSourceSpeed(multiplier: Float): Int =
        (multiplier * 10f).roundToInt().coerceIn(0, 85)
}
