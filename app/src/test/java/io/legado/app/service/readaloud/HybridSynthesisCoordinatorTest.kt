package io.legado.app.service.readaloud

import com.opensecurity.customtts.hybrid.HybridTtsPolicy
import com.opensecurity.customtts.model.CustomTtsSegment
import com.opensecurity.customtts.model.CustomTtsVoiceParams
import com.opensecurity.customtts.model.HybridTtsConfig
import com.opensecurity.customtts.model.HybridTtsSwitch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HybridSynthesisCoordinatorTest {

    private fun seg(text: String) =
        CustomTtsSegment(text = text, startOffset = 0, endOffset = text.length)

    private fun policy(firstFallback: Boolean = false) =
        HybridTtsPolicy(HybridTtsConfig(firstSegmentFallback = firstFallback))

    private fun temp(prefix: String): File =
        File.createTempFile(prefix, ".wav").also { it.deleteOnExit() }

    /** 测试作用域内构建协调器：IO 合成调度器与测试调度器对齐（虚拟时间可控）。 */
    private fun TestScope.coordinator(
        policy: HybridTtsPolicy,
        remoteSynth: suspend (CustomTtsSegment, CustomTtsVoiceParams) -> File?,
        fallbackSynth: suspend (CustomTtsSegment, Float) -> File?,
        onSwitch: (HybridTtsSwitch) -> Unit = {},
    ) = HybridSynthesisCoordinator(
        scope = this,
        policy = policy,
        remoteTimeoutMs = 1_000,
        remoteParams = CustomTtsVoiceParams(),
        fallbackSpeed = 1f,
        remoteSynth = remoteSynth,
        fallbackSynth = fallbackSynth,
        onSwitch = onSwitch,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    @Test
    fun `远程就绪使用远程文件`() = runTest(UnconfinedTestDispatcher()) {
        val remote = temp("remote")
        val fallback = temp("fallback")
        val coordinator = coordinator(
            policy = policy(),
            remoteSynth = { _, _ -> remote },
            fallbackSynth = { _, _ -> fallback },
        )
        coordinator.start(listOf(seg("a"), seg("b")))
        assertEquals(remote, coordinator.audioFor(0))
    }

    @Test
    fun `远程失败使用兜底文件`() = runTest(UnconfinedTestDispatcher()) {
        val fallback = temp("fallback")
        val coordinator = coordinator(
            policy = policy(),
            remoteSynth = { _, _ -> null },
            fallbackSynth = { _, _ -> fallback },
        )
        coordinator.start(listOf(seg("a")))
        assertEquals(fallback, coordinator.audioFor(0))
    }

    @Test
    fun `远程未完成时立即用兜底不等待`() = runTest(UnconfinedTestDispatcher()) {
        val fallback = temp("fallback")
        val coordinator = coordinator(
            policy = policy(),
            remoteSynth = { _, _ ->
                delay(60_000)
                temp("late")
            },
            fallbackSynth = { _, _ -> fallback },
        )
        coordinator.start(listOf(seg("a")))
        assertEquals(fallback, coordinator.audioFor(0))
    }

    @Test
    fun `首段兜底开关开启时强制兜底`() = runTest(UnconfinedTestDispatcher()) {
        val remote = temp("remote")
        val fallback = temp("fallback")
        val coordinator = coordinator(
            policy = policy(firstFallback = true),
            remoteSynth = { _, _ -> remote },
            fallbackSynth = { _, _ -> fallback },
        )
        coordinator.start(listOf(seg("a")))
        assertEquals(fallback, coordinator.audioFor(0))
    }

    @Test
    fun `远端恢复产生切换事件`() = runTest(UnconfinedTestDispatcher()) {
        val events = mutableListOf<HybridTtsSwitch>()
        var call = 0
        val coordinator = coordinator(
            policy = policy(),
            remoteSynth = { _, _ ->
                call++
                if (call >= 2) temp("remote") else null
            },
            fallbackSynth = { _, _ -> temp("fallback") },
            onSwitch = { events.add(it) },
        )
        coordinator.start(listOf(seg("a"), seg("b")))
        coordinator.audioFor(0) // 段 0 远程失败 → 兜底
        coordinator.audioFor(1) // 段 1 远程成功 → 切回
        assertEquals(listOf(HybridTtsSwitch.ToRemote), events)
    }

    @Test
    fun `两者都失败返回 null`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator(
            policy = policy(),
            remoteSynth = { _, _ -> null },
            fallbackSynth = { _, _ -> null },
        )
        coordinator.start(listOf(seg("a")))
        assertNull(coordinator.audioFor(0))
    }

    @Test
    fun `stop 后取消在途任务`() = runTest(UnconfinedTestDispatcher()) {
        var completed = false
        val coordinator = coordinator(
            policy = policy(),
            remoteSynth = { _, _ ->
                delay(10_000)
                completed = true
                temp("late")
            },
            fallbackSynth = { _, _ -> null },
        )
        coordinator.start(listOf(seg("a")))
        coordinator.stop()
        assertTrue(!completed)
    }
}
