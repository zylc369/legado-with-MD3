package io.legado.app.service.readaloud

import com.opensecurity.customtts.hybrid.HybridTtsPolicy
import com.opensecurity.customtts.model.CustomTtsVoiceParams
import com.opensecurity.customtts.model.HybridTtsConfig
import com.opensecurity.customtts.model.HybridTtsDecision
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

    private fun policy(firstFallback: Boolean = false) =
        HybridTtsPolicy(HybridTtsConfig(firstSegmentFallback = firstFallback))

    private fun temp(prefix: String): File =
        File.createTempFile(prefix, ".wav").also { it.deleteOnExit() }

    /** 测试作用域内构建协调器：IO 合成调度器与测试调度器对齐（虚拟时间可控）。 */
    private fun TestScope.coordinator(
        policy: HybridTtsPolicy,
        remoteSynth: suspend (String, CustomTtsVoiceParams) -> File?,
        fallbackSynth: suspend (String, Float) -> File?,
        fallbackEnabled: Boolean = true,
    ) = HybridSynthesisCoordinator(
        scope = this,
        policy = policy,
        remoteTimeoutMs = 1_000,
        remoteParams = CustomTtsVoiceParams(),
        fallbackSpeed = 1f,
        remoteSynth = remoteSynth,
        fallbackSynth = fallbackSynth,
        fallbackEnabled = fallbackEnabled,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    @Test
    fun `remoteReady 非阻塞返回已完成的远端文件`() = runTest(UnconfinedTestDispatcher()) {
        val remote = temp("remote")
        val coordinator = coordinator(
            policy = policy(),
            remoteSynth = { _, _ -> remote },
            fallbackSynth = { _, _ -> null },
        )
        coordinator.start(listOf("a"))
        coordinator.prefetch(0)
        assertEquals(remote, coordinator.remoteReady(0))
    }

    @Test
    fun `remoteReady 在远端未完成时返回 null`() = runTest(UnconfinedTestDispatcher()) {
        val fallback = temp("fallback")
        val coordinator = coordinator(
            policy = policy(),
            remoteSynth = { _, _ ->
                delay(60_000)
                temp("late")
            },
            fallbackSynth = { _, _ -> fallback },
        )
        coordinator.start(listOf("a"))
        coordinator.prefetch(0)
        assertNull(coordinator.remoteReady(0))
    }

    @Test
    fun `远端失败时 remoteReady 返回 null 且兜底文件可用`() = runTest(UnconfinedTestDispatcher()) {
        val fallback = temp("fallback")
        val coordinator = coordinator(
            policy = policy(),
            remoteSynth = { _, _ -> null },
            fallbackSynth = { _, _ -> fallback },
        )
        coordinator.start(listOf("a"))
        coordinator.prefetch(0)
        assertNull(coordinator.remoteReady(0))
        assertEquals(fallback, coordinator.fallbackFile(0))
    }

    @Test
    fun `两者都失败时兜底文件返回 null`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator(
            policy = policy(),
            remoteSynth = { _, _ -> null },
            fallbackSynth = { _, _ -> null },
        )
        coordinator.start(listOf("a"))
        coordinator.prefetch(0)
        assertNull(coordinator.remoteReady(0))
        assertNull(coordinator.fallbackFile(0))
    }

    @Test
    fun `首段兜底开关开启时强制兜底`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator(
            policy = policy(firstFallback = true),
            remoteSynth = { _, _ -> temp("remote") },
            fallbackSynth = { _, _ -> temp("fallback") },
        )
        coordinator.start(listOf("a"))
        coordinator.prefetch(0)
        assertEquals(HybridTtsDecision.Fallback, coordinator.decide(0, remoteReady = true))
    }

    @Test
    fun `远端恢复产生切换事件`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator(
            policy = policy(),
            remoteSynth = { _, _ -> temp("remote") },
            fallbackSynth = { _, _ -> temp("fallback") },
        )
        coordinator.start(listOf("a", "b"))
        assertEquals(HybridTtsDecision.Fallback, coordinator.decide(0, remoteReady = false))
        assertEquals(HybridTtsDecision.Remote, coordinator.decide(1, remoteReady = true))
        assertEquals(listOf(HybridTtsSwitch.ToRemote), coordinator.drainSwitches())
    }

    @Test
    fun `系统兜底关闭兜底文件合成时不调度兜底文件`() = runTest(UnconfinedTestDispatcher()) {
        var fallbackCalled = false
        val coordinator = coordinator(
            policy = policy(),
            remoteSynth = { _, _ -> null },
            fallbackSynth = { _, _ ->
                fallbackCalled = true
                temp("fallback")
            },
            fallbackEnabled = false,
        )
        coordinator.start(listOf("a"))
        coordinator.prefetch(0)
        assertNull(coordinator.fallbackFile(0))
        assertTrue(!fallbackCalled)
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
        coordinator.start(listOf("a"))
        coordinator.prefetch(0)
        coordinator.stop()
        assertTrue(!completed)
    }
}
