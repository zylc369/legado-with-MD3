package io.legado.app.domain.model.readaloud

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPositionMapTest {

    @Test
    fun `恒等映射与 start 加 offset 逐位一致`() {
        val map = PlaybackPositionMap.identity(startPosition = 100)
        for (offset in 0..50) {
            assertEquals(100 + offset, map.positionAt(offset))
        }
        for (position in 100..150) {
            assertEquals(position - 100, map.offsetAt(position))
        }
    }

    @Test
    fun `锚点之间恒等平移跨删除块精确`() {
        // 流: 原文 0..9(段A) 删掉1个分隔符 → 原文 11..20(段B)
        val map = PlaybackPositionMap(
            listOf(
                PlaybackPositionMap.Anchor(0, 0),
                PlaybackPositionMap.Anchor(10, 11),
            )
        )
        assertEquals(9, map.positionAt(9))
        assertEquals(11, map.positionAt(10))
        assertEquals(21, map.positionAt(20))
        assertEquals(9, map.offsetAt(9))
        assertEquals(10, map.offsetAt(10))
        assertEquals(10, map.offsetAt(11))
        assertEquals(20, map.offsetAt(21))
    }

    @Test
    fun `位置落在删除块内对齐到下一个实字`() {
        val map = PlaybackPositionMap(
            listOf(
                PlaybackPositionMap.Anchor(0, 0),
                PlaybackPositionMap.Anchor(10, 13),
            )
        )
        // 10..12 是被删的字符：全部对齐到偏移 10
        assertEquals(10, map.offsetAt(10))
        assertEquals(10, map.offsetAt(11))
        assertEquals(10, map.offsetAt(12))
        assertEquals(10, map.offsetAt(13))
    }

    @Test
    fun `早于单元起点对齐到首字`() {
        val map = PlaybackPositionMap(
            listOf(
                PlaybackPositionMap.Anchor(0, 50),
                PlaybackPositionMap.Anchor(10, 63),
            )
        )
        assertEquals(0, map.offsetAt(0))
        assertEquals(0, map.offsetAt(49))
        assertEquals(0, map.offsetAt(50))
    }

    @Test
    fun `多条锚点位置严格递增且往返一致`() {
        val map = PlaybackPositionMap(
            listOf(
                PlaybackPositionMap.Anchor(0, 100),
                PlaybackPositionMap.Anchor(10, 111),
                PlaybackPositionMap.Anchor(20, 223),
            )
        )
        var previous = -1
        for (offset in 0..29) {
            val position = map.positionAt(offset)
            assertEquals(true, position > previous)
            assertEquals(offset, map.offsetAt(position))
            previous = position
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `首锚点必须落在偏移0`() {
        PlaybackPositionMap(listOf(PlaybackPositionMap.Anchor(1, 10)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `锚点位置必须严格递增`() {
        PlaybackPositionMap(
            listOf(
                PlaybackPositionMap.Anchor(0, 0),
                PlaybackPositionMap.Anchor(10, 5),
            )
        )
    }
}
