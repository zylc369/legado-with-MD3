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

    /** 对齐到用户请求的章内位置后的分段起点（首段文字已裁掉前缀）。 */
    data class StartPlan(
        val segments: List<CustomTtsSegment>,
        val chapterPositions: List<Int>,
        val startIndex: Int,
        val startChapterPosition: Int,
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

    /**
     * 把分段对齐到 [requestedStart]（章内字符位置）：
     * - 找到**包含**该位置的分段（不是下一个分段边界）；
     * - 裁掉首段中该位置之前的文字，并把首段的章节位置改成真实起点。
     *
     * 与普通朗读路径的 `readAloudNumber + paragraphStartPos`、首段
     * `text.substring(paragraphStartPos)` 语义一致，避免混合模式另起一套定位。
     */
    fun alignToStart(built: Built, requestedStart: Int): StartPlan {
        if (built.segments.isEmpty()) {
            return StartPlan(emptyList(), emptyList(), 0, 0)
        }
        val requested = requestedStart.coerceAtLeast(0)
        var index = built.chapterPositions.indexOfLast { it <= requested }
            .let { if (it < 0) 0 else it }
        var offset = (requested - built.chapterPositions[index]).coerceAtLeast(0)
        if (offset >= built.segments[index].text.length) {
            if (index < built.segments.lastIndex) {
                index++
                offset = 0
            } else {
                offset = (built.segments[index].text.length - 1).coerceAtLeast(0)
            }
        }
        val startPosition = built.chapterPositions[index] + offset
        val segments = built.segments.toMutableList().also { list ->
            val segment = list[index]
            list[index] = segment.copy(
                text = segment.text.substring(offset.coerceAtMost(segment.text.length)),
                startOffset = segment.startOffset + offset,
            )
        }
        val positions = built.chapterPositions.toMutableList().also {
            it[index] = startPosition
        }
        return StartPlan(segments, positions, index, startPosition)
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
