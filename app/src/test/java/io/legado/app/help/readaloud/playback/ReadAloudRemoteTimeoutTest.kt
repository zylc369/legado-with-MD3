package io.legado.app.help.readaloud.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudRemoteTimeoutTest {

    @Test
    fun `短文本尊重用户配置的更低超时`() {
        assertEquals(10_000L, ReadAloudRemoteTimeout.effectiveMs(10_000L, 21))
        assertEquals(10_000L, ReadAloudRemoteTimeout.effectiveMs(10_000L, 33))
    }

    @Test
    fun `长文本按字数放大超时避免必然超时`() {
        assertEquals(16_560L, ReadAloudRemoteTimeout.effectiveMs(10_000L, 88))
        assertEquals(18_000L, ReadAloudRemoteTimeout.effectiveMs(10_000L, 100))
    }

    @Test
    fun `预算覆盖实测冷启动上包络`() {
        // 实测冷启动（同中继链路、首次合成）：67字 13.6s / 89字 12.8s /
        // 116字 16.7s / 124字 15.0s——预算必须全部盖住，否则一次超时会计入熔断
        val samples = listOf(67 to 13_600L, 89 to 12_800L, 116 to 16_700L, 124 to 15_000L)
        samples.forEach { (len, observedMs) ->
            val budget = ReadAloudRemoteTimeout.effectiveMs(10_000L, len)
            assertTrue(
                "len=$len 预算 ${budget}ms 未覆盖实测 ${observedMs}ms",
                budget > observedMs,
            )
        }
    }

    @Test
    fun `用户配置更高时取配置值`() {
        assertEquals(20_000L, ReadAloudRemoteTimeout.effectiveMs(20_000L, 10))
    }

    @Test
    fun `上限收敛`() {
        assertEquals(60_000L, ReadAloudRemoteTimeout.effectiveMs(90_000L, 100))
        assertEquals(60_000L, ReadAloudRemoteTimeout.effectiveMs(10_000L, 10_000))
    }

    @Test
    fun `非法字数与过小配置抬到基准值`() {
        assertEquals(6_000L, ReadAloudRemoteTimeout.effectiveMs(1_000L, -5))
        assertEquals(6_000L, ReadAloudRemoteTimeout.effectiveMs(1_000L, 0))
    }
}
