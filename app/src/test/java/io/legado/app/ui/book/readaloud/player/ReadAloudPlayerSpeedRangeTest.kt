package io.legado.app.ui.book.readaloud.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 语速调节范围相关纯逻辑测试。
 *
 * 语速统一为倍速：持久化使用十分之一倍速，范围 5..30（0.5x..3.0x）。
 * 听书播放器与经典朗读控制（ReadAloudScreen.kt）必须使用同一范围。
 */
class ReadAloudPlayerSpeedRangeTest {

    @Test
    fun `speed range matches classic read aloud control`() {
        assertEquals(5, READ_ALOUD_SPEED_MIN)
        assertEquals(30, READ_ALOUD_SPEED_MAX)
    }

    @Test
    fun `slider steps cover every integer in the range`() {
        // 5..30 每个整数一个刻度点：中间 24 个点，steps = 24
        assertEquals(24, READ_ALOUD_SPEED_MAX - READ_ALOUD_SPEED_MIN - 1)
    }

    @Test
    fun `coerce clamps below minimum`() {
        assertEquals(READ_ALOUD_SPEED_MIN, coerceReadAloudSpeed(0))
        assertEquals(READ_ALOUD_SPEED_MIN, coerceReadAloudSpeed(-5))
        assertEquals(READ_ALOUD_SPEED_MIN, coerceReadAloudSpeed(Int.MIN_VALUE))
    }

    @Test
    fun `coerce clamps above maximum`() {
        assertEquals(READ_ALOUD_SPEED_MAX, coerceReadAloudSpeed(31))
        assertEquals(READ_ALOUD_SPEED_MAX, coerceReadAloudSpeed(999))
        assertEquals(READ_ALOUD_SPEED_MAX, coerceReadAloudSpeed(Int.MAX_VALUE))
    }

    @Test
    fun `coerce keeps in-range values unchanged`() {
        assertEquals(5, coerceReadAloudSpeed(5))
        assertEquals(20, coerceReadAloudSpeed(20))
        assertEquals(25, coerceReadAloudSpeed(25))
        assertEquals(30, coerceReadAloudSpeed(30))
    }

    @Test
    fun `format label shows one decimal place`() {
        assertEquals("2.0", formatReadAloudSpeedLabel(20))
        assertEquals("3.0", formatReadAloudSpeedLabel(30))
        assertEquals("0.5", formatReadAloudSpeedLabel(5))
    }

    @Test
    fun `format label keeps fractional display`() {
        assertEquals("0.5", formatReadAloudSpeedLabel(5))
        assertEquals("1.5", formatReadAloudSpeedLabel(15))
        assertEquals("0.7", formatReadAloudSpeedLabel(7))
    }

    @Test
    fun `player slider supports the full unified range`() {
        assertEquals(25, coerceReadAloudSpeed(25))
        assertEquals("2.5", formatReadAloudSpeedLabel(25))
    }
}
