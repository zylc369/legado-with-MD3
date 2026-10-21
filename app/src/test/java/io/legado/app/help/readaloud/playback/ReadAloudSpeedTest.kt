package io.legado.app.help.readaloud.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudSpeedTest {

    @Test
    fun `十分之一倍速换算`() {
        assertEquals(0.2f, ReadAloudSpeed.fromTenths(2), 0.001f)
        assertEquals(1.5f, ReadAloudSpeed.fromTenths(15), 0.001f)
        assertEquals(2.0f, ReadAloudSpeed.fromTenths(20), 0.001f)
    }

    @Test
    fun `显式兜底倍速优先于全局和音色`() {
        assertEquals(
            1.5f,
            ReadAloudSpeed.systemTtsRate(globalTenths = 20, traitsJson = "[]", override = 1.5f),
            0.001f,
        )
    }

    @Test
    fun `全局倍速直接作为系统TTS速度`() {
        assertEquals(
            2.0f,
            ReadAloudSpeed.systemTtsRate(globalTenths = 20, traitsJson = "[]"),
            0.001f,
        )
    }

    @Test
    fun `跟随系统固定1x`() {
        assertEquals(
            1.0f,
            ReadAloudSpeed.systemTtsRate(
                globalTenths = 20,
                traitsJson = "[]",
                followSystem = true,
            ),
            0.001f,
        )
        assertEquals(1.0f, ReadAloudSpeed.globalPlaybackSpeed(20, followSystem = true), 0.001f)
    }

    @Test
    fun `音色级语速优先于全局`() {
        assertEquals(
            1.2f,
            ReadAloudSpeed.systemTtsRate(
                globalTenths = 20,
                traitsJson = """{"speechRate":1.2}""",
            ),
            0.001f,
        )
        assertEquals(
            1.2f,
            ReadAloudSpeed.systemTtsRate(
                globalTenths = 20,
                traitsJson = """{"speechRate":1.2}""",
                followSystem = true,
            ),
            0.001f,
        )
    }

    @Test
    fun `播放端倍速使用全局倍速`() {
        assertEquals(2.0f, ReadAloudSpeed.globalPlaybackSpeed(20, followSystem = false), 0.001f)
        assertEquals(0.7f, ReadAloudSpeed.globalPlaybackSpeed(7, followSystem = false), 0.001f)
    }

    @Test
    fun `十分之一倍速边界`() {
        assertEquals(0.5f, ReadAloudSpeed.fromTenths(5), 0.001f)
        assertEquals(3.0f, ReadAloudSpeed.fromTenths(30), 0.001f)
        assertEquals(20, ReadAloudSpeed.toTenths(2.0f))
        assertEquals(5, ReadAloudSpeed.toTenths(0.01f))
        assertEquals(30, ReadAloudSpeed.toTenths(99f))
    }

    @Test
    fun `HTTP书源源刻度转换`() {
        assertEquals(10, ReadAloudSpeed.httpSourceSpeed(1.0f))
        assertEquals(20, ReadAloudSpeed.httpSourceSpeed(2.0f))
        assertEquals(5, ReadAloudSpeed.httpSourceSpeed(0.5f))
        assertEquals(30, ReadAloudSpeed.httpSourceSpeed(3.0f))
    }
}
