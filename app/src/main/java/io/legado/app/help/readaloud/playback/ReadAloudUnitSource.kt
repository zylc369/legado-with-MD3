package io.legado.app.help.readaloud.playback

import com.opensecurity.customtts.model.CustomTtsSegment
import com.opensecurity.customtts.segmenter.Segmenter
import io.legado.app.domain.model.readaloud.PlaybackPositionMap
import io.legado.app.domain.model.readaloud.PlaybackUnit
import io.legado.app.domain.model.readaloud.ReadAloudPlaybackCue
import io.legado.app.feature.reader.core.readaloud.ReaderReadAloudParagraph

/**
 * 播放单元的唯一构建入口。
 *
 * 两种来源、同一产物：
 * - [fromParagraphs]/[fromCues]：原文连续切片，映射恒等（普通系统/HTTP/云路径）；
 * - [fromChapterCleaned]：清洗流分段（可跨段），逐字记锚点（混合路径）。
 *
 * 位置算术只存在于这里（构建时）；所有消费方一律查表。
 */
object ReadAloudUnitSource {

    /** 朗读禁读字符→空格（长度不变，位置恒等不受影响；普通/混合路径统一净化）。 */
    private val unreadableChars = Regex("[袮祢꧁\uFFFC]")

    private fun String.sanitizedForSpeech(): String = replace(unreadableChars, " ")

    /** 恒等单元：一个自然段一个单元，text 为原文（含缩进、净化替换），位置精确。 */
    fun fromParagraphs(paragraphs: List<ReaderReadAloudParagraph>): List<PlaybackUnit> =
        paragraphs.map { paragraph ->
            PlaybackUnit(
                text = paragraph.text.sanitizedForSpeech(),
                map = PlaybackPositionMap.identity(paragraph.chapterPosition),
            )
        }

    /** 恒等单元：多角色队列 cue（标题 cue → 标题单元，标题坐标系独立）。 */
    fun fromCues(cues: List<ReadAloudPlaybackCue>): List<PlaybackUnit> = cues.map { cue ->
        if (cue.isChapterTitle) {
            PlaybackUnit.chapterTitle(cue.text)
        } else {
            PlaybackUnit(
                text = cue.text,
                map = PlaybackPositionMap.identity(cue.chapterStart),
            )
        }
    }

    /**
     * 清洗单元：混合引擎专用。
     *
     * 清洗机制保留——段落拼接为无分隔符的流（空白对分段与合成无意义，且与
     * PC 前端分段行为逐段一致），阶梯（20→40→60→80）在整条流上全局推进、
     * 分段可跨自然段。与旧实现的差别只有一个：拼接时**记账**——每个分段携带
 * 锚点表，删除块边界各记一条，单元内每个字的章节位置可精确查表还原。
     */
    fun fromChapterCleaned(
        paragraphs: List<ReaderReadAloudParagraph>,
        firstTargets: List<Int>,
        target: Int,
    ): List<PlaybackUnit> {
        val builder = StringBuilder()
        val streamStarts = IntArray(paragraphs.size)
        val chapterStarts = IntArray(paragraphs.size)
        paragraphs.forEachIndexed { index, paragraph ->
            streamStarts[index] = builder.length
            chapterStarts[index] = paragraph.chapterPosition
            builder.append(paragraph.text)
        }
        val stream = builder.toString()
        if (stream.isEmpty()) return emptyList()

        fun streamPositionAt(streamOffset: Int): Int {
            var owner = 0
            for (index in paragraphs.indices) {
                if (streamStarts[index] <= streamOffset) owner = index else break
            }
            return chapterStarts[owner] + (streamOffset - streamStarts[owner])
        }

        return Segmenter.segmentWithOffsets(stream, target, 0, firstTargets)
            .map { segment -> unitFromStreamSegment(stream, segment, ::streamPositionAt) }
    }

    /**
     * 唯一起点定位：章节位置 →（单元下标, 单元内偏移）。
     *
     * 位置落在删除块内时向前对齐到下一个实字；早于首个正文单元时对齐到首字；
     * 章节标题单元不参与正文定位。
     */
    fun cursorAt(units: List<PlaybackUnit>, position: Int): Pair<Int, Int>? {
        val bodyStart = units.indexOfFirst { !it.isChapterTitle }
        if (bodyStart < 0) return null
        var index = bodyStart
        for (i in bodyStart until units.size) {
            if (!units[i].isChapterTitle && units[i].startPosition <= position) index = i
        }
        var offset = units[index].map?.offsetAt(position) ?: return null
        // 位置落在单元末尾的删除块/分隔符上：对齐到下一个正文单元首字；
        // 已是最后一个单元则收敛到最后一个字符（与旧 seek 行为一致）。
        while (offset >= units[index].text.length && !units[index].isChapterTitle) {
            val next = (index + 1 until units.size).firstOrNull { !units[it].isChapterTitle }
                ?: break
            index = next
            offset = 0
        }
        if (offset >= units[index].text.length) {
            offset = (units[index].text.length - 1).coerceAtLeast(0)
        }
        return index to offset.coerceAtLeast(0)
    }

    /**
     * 分段文本（清洗子序列）→ 带锚点单元：沿流逐字对齐，每跳过一段被删除的
     * 字符就记一条锚点；对齐失败的字符按恒等推进兜底，构建永不抛出。
     */
    private fun unitFromStreamSegment(
        stream: String,
        segment: CustomTtsSegment,
        streamPositionAt: (Int) -> Int,
    ): PlaybackUnit {
        val anchors = ArrayList<PlaybackPositionMap.Anchor>()
        anchors += PlaybackPositionMap.Anchor(0, streamPositionAt(segment.startOffset))
        var textIndex = 0
        var streamIndex = segment.startOffset
        val text = segment.text
        while (textIndex < text.length && streamIndex < stream.length) {
            if (stream[streamIndex] == text[textIndex]) {
                textIndex++
                streamIndex++
            } else if (stream[streamIndex].isWhitespace()) {
                // 连续空白（换行+缩进）整块跳过；锚点记在**下一个实字**上，
                // 而不是空白中间——否则该偏移会映射到不可见字符。
                do {
                    streamIndex++
                } while (streamIndex < stream.length && stream[streamIndex].isWhitespace())
                if (anchors.last().offset != textIndex) {
                    anchors += PlaybackPositionMap.Anchor(
                        offset = textIndex,
                        position = streamPositionAt(streamIndex),
                    )
                }
            } else {
                textIndex++
                streamIndex++
            }
        }
        return PlaybackUnit(
            text = text,
            map = PlaybackPositionMap(anchors),
        )
    }
}
