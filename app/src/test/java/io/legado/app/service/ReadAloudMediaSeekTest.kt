package io.legado.app.service

import io.legado.app.domain.model.readaloud.PlaybackPositionMap
import io.legado.app.domain.model.readaloud.PlaybackUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadAloudMediaSeekTest {

    private val units = listOf(
        PlaybackUnit.chapterTitle("章节标题"),
        PlaybackUnit("abcdef", PlaybackPositionMap.identity(0)),
        PlaybackUnit("ghij", PlaybackPositionMap.identity(7)),
    )

    @Test
    fun seekUsesTheSameSpeechRateAsTheEstimatedTimeline() {
        assertEquals(ReadAloudMediaSeekPosition(2, 1, 8), seek(2_000, 4f))
        assertEquals(ReadAloudMediaSeekPosition(2, 1, 8), seek(1_000, 8f))
        assertEquals(ReadAloudMediaSeekPosition(1, 2, 2), seek(500, 4f))
    }

    @Test
    fun bodyTimelineDoesNotSeekIntoTheChapterTitle() {
        assertEquals(ReadAloudMediaSeekPosition(1, 0, 0), seek(0))
        assertEquals(ReadAloudMediaSeekPosition(1, 0, 0), seek(Long.MIN_VALUE))
    }

    @Test
    fun newlineGapResolvesToTheNextParagraph() {
        assertEquals(ReadAloudMediaSeekPosition(2, 0, 7), seek(1_500))
    }

    @Test
    fun seekAtOrAfterChapterEndKeepsAPlayableCharacterInTheCurrentChapter() {
        assertEquals(ReadAloudMediaSeekPosition(2, 3, 10), seek(2_750))
        assertEquals(ReadAloudMediaSeekPosition(2, 3, 10), seek(Long.MAX_VALUE))
    }

    @Test
    fun adjacentPageOrVoiceCuesDoNotInventANewlineOffset() {
        assertEquals(
            ReadAloudMediaSeekPosition(1, 0, 4),
            resolveReadAloudMediaSeek(
                1_000, 4f, 8,
                listOf(
                    PlaybackUnit("abcd", PlaybackPositionMap.identity(0)),
                    PlaybackUnit("efgh", PlaybackPositionMap.identity(4)),
                ),
            ),
        )
    }

    @Test
    fun seekPreservesSurrogatePairs() {
        assertEquals(
            ReadAloudMediaSeekPosition(0, 0, 0),
            resolveReadAloudMediaSeek(
                250, 4f, 3,
                listOf(PlaybackUnit("\uD83D\uDE00a", PlaybackPositionMap.identity(0))),
            ),
        )
    }

    @Test
    fun unavailableContentOrTimelineDoesNotProduceASeek() {
        assertNull(resolveReadAloudMediaSeek(0, 4f, 0, emptyList()))
        assertNull(
            resolveReadAloudMediaSeek(
                0, 4f, 4, listOf(PlaybackUnit.chapterTitle("标题"))
            )
        )
        assertNull(seek(1_000, 0f))
        assertNull(seek(1_000, Float.NaN))
    }

    @Test
    fun seekingWhilePausedReplacesTheFrozenPositionAndResumeKeepsIt() {
        val newPosition = nextMediaSessionPositionMs(2, 2, 2_000, -1, 50_000, 40_000)
        assertEquals(2_000L, newPosition)
        assertEquals(2_000L, nextMediaSessionPositionMs(2, 2, 2_000, newPosition, 60_000, 50_000))
        assertEquals(2_000L, nextMediaSessionPositionMs(3, 2, 2_000, newPosition, 70_000, 50_000))
    }

    @Test
    fun ttsRangeStartsAtTheSeekOffsetWithinTheParagraph() {
        val map = PlaybackPositionMap.identity(200).advancedBy(40)
        assertEquals(240, map.positionAt(0))
        assertEquals(245, map.positionAt(5))
    }

    @Test
    fun httpAudioTimeMapsOnlyToTheTextSynthesizedAfterSeek() {
        assertEquals(40, httpReadAloudParagraphOffset(100, 40, 0, 6_000))
        assertEquals(60, httpReadAloudParagraphOffset(100, 40, 2_000, 6_000))
        assertEquals(100, httpReadAloudParagraphOffset(100, 40, 6_000, 6_000))
        assertEquals(40, httpReadAloudParagraphOffset(100, 40, 2_000, 0))
    }

    private fun seek(positionMs: Long, charsPerSecond: Float = 4f) =
        resolveReadAloudMediaSeek(positionMs, charsPerSecond, 11, units)
}
