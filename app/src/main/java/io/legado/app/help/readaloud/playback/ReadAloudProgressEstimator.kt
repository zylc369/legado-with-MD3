package io.legado.app.help.readaloud.playback

import kotlin.math.roundToLong

/**
 * 单元内进度估算：仅在 TTS 引擎不回调 onRangeStart 时使用。
 *
 * 与 ExoPlayer 的 upPlayPos 等价：按“4 字/秒 × 倍速”估算每个字符的时间，
 * 一旦引擎回调了真实 offset 就停止估算。
 */
object ReadAloudProgressEstimator {

    const val CHARS_PER_SECOND = 4f
    private const val MIN_STEP_MS = 20L

    fun stepMillis(speechRate: Float): Long {
        if (!speechRate.isFinite()) return MIN_STEP_MS
        return (1000.0 / (CHARS_PER_SECOND * speechRate.coerceAtLeast(0.1f)))
            .roundToLong()
            .coerceAtLeast(MIN_STEP_MS)
    }
}
