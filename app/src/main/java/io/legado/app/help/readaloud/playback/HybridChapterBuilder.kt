package io.legado.app.help.readaloud.playback

import com.opensecurity.customtts.model.CustomTtsSegment
import com.opensecurity.customtts.segmenter.Segmenter
import io.legado.app.domain.model.readaloud.ContentSplitPolicy
import io.legado.app.feature.reader.core.readaloud.ReaderReadAloudChapter

/**
 * 混合朗读的章节分段构建（纯函数——单测友好）。
 *
 * 把章节朗读文本流按 [Segmenter] 分段（复用手前端算法），并把分段偏移映射回
 * 章节绝对位置（用于进度/高亮）。文本流 = 段落文本依次拼接（无分隔符）。
 */
object HybridChapterBuilder {

    data class Built(
        val segments: List<CustomTtsSegment>,
        val chapterPositions: List<Int>,
    )

    fun build(
        chapter: ReaderReadAloudChapter,
        splitByPage: Boolean,
        policy: ContentSplitPolicy?,
        firstTargets: List<Int>,
        target: Int,
    ): Built {
        val paragraphs = chapter.paragraphs(splitByPage, policy)
        val builder = StringBuilder()
        val paragraphStreamStarts = IntArray(paragraphs.size)
        for ((index, paragraph) in paragraphs.withIndex()) {
            paragraphStreamStarts[index] = builder.length
            builder.append(paragraph.text)
        }
        val fullText = builder.toString()
        if (fullText.isEmpty()) return Built(emptyList(), emptyList())
        val segments = Segmenter.segmentWithOffsets(fullText, target, 0, firstTargets)
        val positions = segments.map { segment ->
            // 段起点所在段落（跨段时取起点所在段）
            val index = paragraphIndexAt(paragraphStreamStarts, segment.startOffset)
            val paragraph = paragraphs[index]
            paragraph.chapterPosition +
                (segment.startOffset - paragraphStreamStarts[index]).coerceAtLeast(0)
        }
        return Built(segments, positions)
    }

    private fun paragraphIndexAt(starts: IntArray, offset: Int): Int {
        if (starts.isEmpty()) return 0
        var low = 0
        var high = starts.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (starts[mid] <= offset) low = mid else high = mid - 1
        }
        return low
    }
}
