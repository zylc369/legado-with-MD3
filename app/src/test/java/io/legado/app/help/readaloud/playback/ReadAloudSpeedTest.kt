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

    @Test
    fun `显式兜底倍速被限制在0_1到3倍`() {
        assertEquals(0.1f, ReadAloudSpeed.systemTtsRate(10, "[]", override = 0f), 0.001f)
        assertEquals(3.0f, ReadAloudSpeed.systemTtsRate(10, "[]", override = 99f), 0.001f)
    }

    @Test
    fun `非法 traits 回退全局倍速`() {
        assertEquals(2.0f, ReadAloudSpeed.systemTtsRate(20, "not-json"), 0.001f)
        assertEquals(2.0f, ReadAloudSpeed.systemTtsRate(20, null), 0.001f)
    }

    @Test
    fun `音色级非法语速被限制在0_1到3倍`() {
        assertEquals(
            3.0f,
            ReadAloudSpeed.systemTtsRate(20, """{"speechRate":99}"""),
            0.001f,
        )
    }

    @Test
    fun `NaN倍速写入取最小值`() {
        assertEquals(5, ReadAloudSpeed.toTenths(Float.NaN))
    }

    @Test
    fun `倍速收敛到0_1网格消除浮点误差`() {
        assertEquals(0.7f, ReadAloudSpeed.quantize(0.6999999f), 0.0001f)
        assertEquals(0.7f, ReadAloudSpeed.quantize(0.70000005f), 0.0001f)
        assertEquals(0.8f, ReadAloudSpeed.quantize(0.7999999f), 0.0001f)
        assertEquals(0.5f, ReadAloudSpeed.quantize(0f), 0.0001f)
        assertEquals(3.0f, ReadAloudSpeed.quantize(99f), 0.0001f)
        assertEquals(1.0f, ReadAloudSpeed.quantize(Float.NaN), 0.0001f)
    }

    @Test
    fun `混合比值新段同速为恒等一`() {
        // 保存速度后新合成的段 baked == target：必须精确 1.0（不引入量化漂移）
        assertEquals(1f, ReadAloudSpeed.hybridRatio(target = 1.2f, baked = 1.2f))
        assertEquals(1f, ReadAloudSpeed.hybridRatio(target = 0.7f, baked = 0.7f))
    }

    @Test
    fun `混合比值旧段按目标除以烘焙`() {
        // 旧段烘 0.8x，保存新目标 1.2x → 播放 1.5x → 感知 1.2x
        assertEquals(1.5f, ReadAloudSpeed.hybridRatio(target = 1.2f, baked = 0.8f))
        assertEquals(0.5f, ReadAloudSpeed.hybridRatio(target = 0.6f, baked = 1.2f))
    }

    @Test
    fun `混合比值夹紧与非法输入兜底`() {
        // 极端比值夹到 0.1..3
        assertEquals(3f, ReadAloudSpeed.hybridRatio(target = 3f, baked = 0.1f))
        assertEquals(0.1f, ReadAloudSpeed.hybridRatio(target = 0.1f, baked = 3f))
        // 烘焙速度过小按 0.1 兜底，非法目标回 1
        assertEquals(1f, ReadAloudSpeed.hybridRatio(target = 0.1f, baked = 0.05f))
        assertEquals(1f, ReadAloudSpeed.hybridRatio(target = Float.NaN, baked = 1f))
    }
}
