package io.legado.app.help.readaloud.playback

import io.legado.app.domain.model.settings.ReadAloudContentSplitMode
import io.legado.app.feature.reader.core.readaloud.ReaderReadAloudChapter
import io.legado.app.feature.reader.core.readaloud.ReaderReadAloudParagraph
import com.opensecurity.customtts.model.CustomTtsSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridChapterBuilderTest {

    private fun chapter(paragraphs: List<ReaderReadAloudParagraph>): ReaderReadAloudChapter =
        ReaderReadAloudChapter(
            chapterIndex = 0,
            title = "第一章",
            pageStarts = listOf(0),
            paragraphs = paragraphs,
            pageParagraphs = paragraphs,
            chapterLength = paragraphs.lastOrNull()?.endPosition ?: 0,
            contentSplitMode = ReadAloudContentSplitMode.Paragraph,
        )

    @Test
    fun `分段覆盖全文且章节位置单调`() {
        val s20 = "一二三四五六七八九十一二三四五六七八九。"
        val paragraphs = listOf(
            ReaderReadAloudParagraph(s20, 0, true),
            ReaderReadAloudParagraph(s20, 20, true),
            ReaderReadAloudParagraph(s20, 40, true),
        )
        val built = HybridChapterBuilder.build(
            chapter(paragraphs), false, null,
            firstTargets = listOf(20, 40, 60, 80), target = 80,
        )
        assertEquals(s20.repeat(3), built.segments.joinToString("") { it.text })
        assertEquals(built.segments.size, built.chapterPositions.size)
        built.chapterPositions.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        assertEquals(0, built.chapterPositions.first())
    }

    @Test
    fun `跨段落首段位置取起点段落`() {
        val paragraphs = listOf(
            ReaderReadAloudParagraph("一二三四五。", 0, true),      // 6 字
            ReaderReadAloudParagraph("六七八九十。", 6, true),      // 6 字
        )
        val built = HybridChapterBuilder.build(
            chapter(paragraphs), false, null,
            firstTargets = listOf(20, 40, 80), target = 80,
        )
        assertEquals(1, built.segments.size) // 12 字合为一段
        assertEquals(0, built.chapterPositions[0])
        assertEquals("一二三四五。六七八九十。", built.segments[0].text)
    }

    @Test
    fun `空章节返回空`() {
        val built = HybridChapterBuilder.build(
            chapter(emptyList()), false, null, firstTargets = listOf(20, 40, 80), target = 80,
        )
        assertTrue(built.segments.isEmpty())
        assertTrue(built.chapterPositions.isEmpty())
    }

    @Test
    fun `段落不足时尾部段位置正确`() {
        val s20 = "一二三四五六七八九十一二三四五六七八九。"
        val paragraphs = listOf(
            ReaderReadAloudParagraph(s20, 0, true),
            ReaderReadAloudParagraph(s20, 20, true),
            ReaderReadAloudParagraph("尾巴。", 40, true),
        )
        val built = HybridChapterBuilder.build(
            chapter(paragraphs), false, null,
            firstTargets = listOf(20, 40, 60, 80), target = 80,
        )
        // 尾段并回 → 最后一段包含尾巴，位置从 20 开始
        assertEquals(built.segments.size, built.chapterPositions.size)
        assertEquals(0, built.chapterPositions.first())
    }

    private fun twoSegments() = HybridChapterBuilder.Built(
        segments = listOf(
            CustomTtsSegment("abcdefghij", 0, 10),
            CustomTtsSegment("klmnopqrst", 10, 20),
        ),
        chapterPositions = listOf(0, 10),
    )

    @Test
    fun `对齐到分段内部时裁剪首段文字`() {
        val plan = HybridChapterBuilder.alignToStart(twoSegments(), requestedStart = 13)
        assertEquals(1, plan.startIndex)
        assertEquals(13, plan.startChapterPosition)
        assertEquals("abcdefghij", plan.segments[0].text)
        assertEquals("nopqrst", plan.segments[1].text)
        assertEquals(13, plan.chapterPositions[1])
    }

    @Test
    fun `对齐到分段边界时不裁剪`() {
        val plan = HybridChapterBuilder.alignToStart(twoSegments(), requestedStart = 10)
        assertEquals(1, plan.startIndex)
        assertEquals(10, plan.startChapterPosition)
        assertEquals("klmnopqrst", plan.segments[1].text)
    }

    @Test
    fun `起点为 0 时保持首段完整`() {
        val plan = HybridChapterBuilder.alignToStart(twoSegments(), requestedStart = 0)
        assertEquals(0, plan.startIndex)
        assertEquals(0, plan.startChapterPosition)
        assertEquals("abcdefghij", plan.segments[0].text)
    }
}
