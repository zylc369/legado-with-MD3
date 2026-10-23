package io.legado.app.help.readaloud.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudProgressEstimatorTest {

    @Test
    fun `1倍速每字250毫秒`() {
        assertEquals(250L, ReadAloudProgressEstimator.stepMillis(1f))
    }

    @Test
    fun `0_5倍速每字500毫秒`() {
        assertEquals(500L, ReadAloudProgressEstimator.stepMillis(0.5f))
    }

    @Test
    fun `2倍速每字125毫秒`() {
        assertEquals(125L, ReadAloudProgressEstimator.stepMillis(2f))
    }

    @Test
    fun `非法倍速按最小0_1倍速兜底`() {
        assertEquals(2500L, ReadAloudProgressEstimator.stepMillis(0f))
    }

    @Test
    fun `3倍速每字约83毫秒`() {
        assertEquals(83L, ReadAloudProgressEstimator.stepMillis(3f))
    }

    @Test
    fun `负倍速按最小0_1倍速兜底`() {
        assertEquals(2500L, ReadAloudProgressEstimator.stepMillis(-1f))
    }

    @Test
    fun `NaN与无穷大不会产生非法步长`() {
        assertEquals(20L, ReadAloudProgressEstimator.stepMillis(Float.NaN))
        assertEquals(20L, ReadAloudProgressEstimator.stepMillis(Float.POSITIVE_INFINITY))
    }
}
