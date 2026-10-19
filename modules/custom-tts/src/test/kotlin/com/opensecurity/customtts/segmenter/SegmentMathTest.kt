package com.opensecurity.customtts.segmenter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentMathTest {

    @Test
    fun `ramp 默认序列`() {
        assertEquals(listOf(20, 40, 60, 80), SegmentMath.rampFor(20))
    }

    @Test
    fun `ramp 首段 30 与 60`() {
        assertEquals(listOf(30, 60, 80), SegmentMath.rampFor(30))
        assertEquals(listOf(60, 80), SegmentMath.rampFor(60))
    }

    @Test
    fun `ramp 非法首段回退为 1`() {
        assertEquals((1..80).toList(), SegmentMath.rampFor(0))
    }

    @Test
    fun `normalize 去重排序`() {
        assertEquals(listOf(20, 40, 60), SegmentMath.normalizeStairs(listOf(60, 20, 40, 60)))
        assertEquals(listOf(30, 60, 80), SegmentMath.normalizeStairs(listOf(30, 60, 80)))
    }

    @Test
    fun `normalize 非法输入返回 null`() {
        assertNull(SegmentMath.normalizeStairs(null))
        assertNull(SegmentMath.normalizeStairs(emptyList()))
        assertNull(SegmentMath.normalizeStairs(listOf(0)))
        assertNull(SegmentMath.normalizeStairs(listOf(-1)))
        assertNull(SegmentMath.normalizeStairs(listOf(1001)))
        assertNull(SegmentMath.normalizeStairs((1..9).toList())) // 9 档 > 8
    }

    @Test
    fun `splitOversize 软标点切分`() {
        val parts = SegmentMath.splitOversize("一二三四五，六七八九十", 6)
        assertEquals(listOf("一二三四五，", "六七八九十"), parts)
    }

    @Test
    fun `splitOversize 无软标点硬切`() {
        val parts = SegmentMath.splitOversize("1234567890", 4)
        assertEquals(listOf("1234", "5678", "90"), parts)
    }
}
