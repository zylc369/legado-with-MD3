package com.opensecurity.customtts.segmenter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmenterTest {

    @Test
    fun `短文本合并为一段`() {
        val text = "第一句话。第二句话。第三句话。"
        val segs = Segmenter.segmentText(text)
        assertEquals(1, segs.size)
        assertEquals(text, segs[0])
    }

    @Test
    fun `换行丢弃并合并`() {
        val segs = Segmenter.segmentText("第一段内容。\n第二段内容。")
        assertEquals(1, segs.size)
        assertEquals("第一段内容。第二段内容。", segs[0])
    }

    /** 精确 20 字句（19 字 + 句号） */
    private val sentence20 = "一二三四五六七八九十一二三四五六七八九。"

    @Test
    fun `阶梯分段 20 40 60 80`() {
        // 10 句 × 20 字 = 200 字
        val text = sentence20.repeat(10)
        val segs = Segmenter.segmentText(text, target = 80)
        assertEquals(listOf(20, 40, 60, 80), segs.map { it.length })
        assertEquals(text, segs.joinToString(""))
    }

    @Test
    fun `长首句在逗号处提前断`() {
        // 首句 41 字（20 字 + 逗号 + 20 字 + 句号）> 20×1.5
        val text = "一".repeat(20) + "，" + "二".repeat(20) + "。"
        val segs = Segmenter.segmentText(text)
        assertEquals(2, segs.size)
        assertTrue(segs[0].endsWith("，"))
        assertEquals(text, segs.joinToString(""))
    }

    @Test
    fun `超长句硬切不超过硬限`() {
        val text = "一".repeat(299) + "。" // 300 字（无软标点）
        val segs = Segmenter.segmentText(text)
        assertTrue(segs.size > 1)
        // 硬限 = 2*首段 + 当段目标 ≤ 120
        assertTrue(segs.all { it.length <= 120 })
        assertEquals(text, segs.joinToString(""))
    }

    @Test
    fun `短尾段并回前段`() {
        // 首段 20（一句）+ 40（两句）+ 4 字尾巴（< 首段目标 20 → 并入前段）
        val text = sentence20.repeat(3) + "短尾巴。"
        val segs = Segmenter.segmentText(text)
        assertEquals(text, segs.joinToString(""))
        assertEquals(2, segs.size) // [20, 44]
    }

    @Test
    fun `空文本返回空列表`() {
        assertTrue(Segmenter.segmentText("").isEmpty())
        assertTrue(Segmenter.segmentText("   \n  ").isEmpty())
    }

    @Test
    fun `偏移映射连续且为原文子串`() {
        val text = "第一句话内容。第二句话内容。第三句话内容。第四句话内容。"
        val segs = Segmenter.segmentWithOffsets(text)
        var prevEnd = 0
        for (seg in segs) {
            assertEquals(text.substring(seg.startOffset, seg.endOffset), seg.text)
            assertTrue(seg.startOffset >= prevEnd)
            prevEnd = seg.endOffset
        }
    }

    @Test
    fun `自定义阶梯生效`() {
        val text = sentence20.repeat(6) // 120 字
        val segs = Segmenter.segmentText(text, target = 80, firstTargets = listOf(30, 60, 80))
        // 首段目标 30 → 2 句（40）；第二段目标 60 → 3 句（60）；剩余 20 → 尾段并回 → [40, 80]
        assertEquals(listOf(40, 80), segs.map { it.length })
        assertEquals(text, segs.joinToString(""))
    }
}
