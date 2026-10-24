package io.legado.app.help.readaloud.playback

/**
 * 混合朗读远端单段超时——**按实测冷启动上包络标定，不是按均值**。
 *
 * 冷路径实测（经同一中继链路、全部首次合成的文本，2026-10-24）：
 * 17字 4.3s / 32字 7.7s / 56字 8.6s / 67字 13.6s / 89字 12.8s /
 * 116字 16.7s / 124字 15.0s——线性趋势约 `2.6s + 100ms/字`，噪声 ±2~2.5s。
 * 超时预算必须覆盖上包络（约 `5s + 100ms/字` + 2.5s 噪声），因为超一单不只丢该段，
 * 还计入熔断（连续 2 次 → 30s 全兜底）。
 *
 * 时序预算不变量（全链路唯一权威定义）：
 * - App 协调器预算 = effectiveMs（本函数，用户配置与公式取大）；
 * - 网关预算 = effectiveMs − 200ms（LocalTtsGateway 余量，只够覆盖调度开销，
 *   禁止加大——曾用 2s 误杀 `[T-2s, T]` 内完成的请求）；
 * - 熔断只应由真实超出本预算的失败触发。
 */
object ReadAloudRemoteTimeout {

    const val BASE_MS = 6_000L
    const val PER_CHAR_MS = 120L
    const val MAX_MS = 60_000L

    fun effectiveMs(configuredMs: Long, textLength: Int): Long {
        val lengthBasedMs = BASE_MS + textLength.coerceAtLeast(0) * PER_CHAR_MS
        return maxOf(configuredMs, lengthBasedMs).coerceIn(1_000L, MAX_MS)
    }
}
