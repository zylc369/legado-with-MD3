package com.opensecurity.customtts.segmenter

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨语言一致性：vectors-segmenter.json 由 PC 前端 TS 版 segmentText 生成，
 * Kotlin 版必须逐段一致（接口协议的一部分——段序列直接决定请求节奏）。
 */
class SegmenterVectorTest {

    private data class VectorCase(
        val name: String,
        val text: String,
        val target: Int,
        val firstTargets: List<Int>?,
        val expected: List<String>,
    )

    private fun vectors(): List<VectorCase> {
        val json = javaClass.classLoader!!.getResourceAsStream("vectors-segmenter.json")!!
            .readBytes().decodeToString()
        val type = object : TypeToken<List<VectorCase>>() {}.type
        return Gson().fromJson(json, type)
    }

    @Test
    fun `与 PC 前端分段器逐段一致`() {
        val cases = vectors()
        assertEquals(12, cases.size)
        for (case in cases) {
            val firstTargets = case.firstTargets
                ?: SegmentMath.rampFor(SegmentMath.DEFAULT_FIRST_LEN, case.target)
            val actual = Segmenter.segmentText(case.text, case.target, 0, firstTargets)
            assertEquals("case=${case.name}", case.expected, actual)
        }
    }

    @Test
    fun `偏移版本段文本与向量一致`() {
        for (case in vectors()) {
            val firstTargets = case.firstTargets
                ?: SegmentMath.rampFor(SegmentMath.DEFAULT_FIRST_LEN, case.target)
            val actual = Segmenter.segmentWithOffsets(case.text, case.target, 0, firstTargets)
            assertEquals("case=${case.name}", case.expected, actual.map { it.text })
            for (seg in actual) {
                assertTrue(seg.startOffset in 0..case.text.length)
                assertTrue(seg.endOffset in seg.startOffset..case.text.length)
            }
        }
    }
}
