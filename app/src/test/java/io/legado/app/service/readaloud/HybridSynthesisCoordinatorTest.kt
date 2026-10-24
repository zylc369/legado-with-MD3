package io.legado.app.service.readaloud

import com.opensecurity.customtts.hybrid.HybridTtsPolicy
import com.opensecurity.customtts.model.CustomTtsVoiceParams
import com.opensecurity.customtts.model.HybridTtsConfig
import com.opensecurity.customtts.model.HybridTtsDecision
import com.opensecurity.customtts.model.HybridTtsSwitch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
        nowMs: () -> Long = System::currentTimeMillis,
        remoteParams: () -> CustomTtsVoiceParams = { CustomTtsVoiceParams() },
        fallbackSpeed: () -> Float = { 1f },
    ) = HybridSynthesisCoordinator(
        scope = this,
        policy = policy,
        remoteTimeoutMs = { 1_000 },
        remoteParams = remoteParams,
        fallbackSpeed = fallbackSpeed,
        remoteSynth = remoteSynth,
        fallbackSynth = fallbackSynth,
        fallbackEnabled = fallbackEnabled,
        nowMs = nowMs,
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
        assertEquals(remote, coordinator.remoteReady(0)?.file)
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
        assertEquals(fallback, coordinator.fallbackFile(0)?.file)
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

    @Test
    fun `远端连续失败进入冷却后停止请求，冷却结束后重试`() = runTest(UnconfinedTestDispatcher()) {
        var now = 1_000L
        var remoteCalls = 0
        val coordinator = coordinator(
            policy = policy(),
            remoteSynth = { _, _ ->
                remoteCalls++
                null
            },
            fallbackSynth = { _, _ -> null },
            nowMs = { now },
        )
        coordinator.start(listOf("a", "b", "c"))

        coordinator.prefetch(0)
        assertEquals(1, remoteCalls)
        coordinator.prefetch(1)
        assertEquals(2, remoteCalls)
        // 达到阈值进入冷却：冷却期内不再发起远端请求，兜底照常
        coordinator.prefetch(2)
        assertEquals(2, remoteCalls)

        now += 30_000
        coordinator.prefetch(2)
        assertEquals(3, remoteCalls)
    }

    @Test
    fun `远端合成串行执行不并发排队`() = runTest(UnconfinedTestDispatcher()) {
        var inFlight = 0
        var maxInFlight = 0
        val coordinator = coordinator(
            policy = policy(),
            remoteSynth = { _, _ ->
                inFlight++
                maxInFlight = maxOf(maxInFlight, inFlight)
                delay(100)
                inFlight--
                null
            },
            fallbackSynth = { _, _ -> null },
        )
        coordinator.start(listOf("a", "b", "c"))
        coordinator.prefetch(0)
        coordinator.prefetch(1)
        coordinator.prefetch(2)
        advanceUntilIdle()
        // 网关 P2P 请求本身单锁串行：并发预取只会排队并吃掉超时预算
        assertEquals(1, maxInFlight)
    }

    @Test
    fun `缓存命中走闸门外快路径不发起合成`() = runTest(UnconfinedTestDispatcher()) {
        val cached = temp("cached")
        var remoteSynthCalls = 0
        // 直接构造带 probe 的协调器（helper 不透传 probe）
        val probed = HybridSynthesisCoordinator(
            scope = this,
            policy = policy(),
            remoteTimeoutMs = { 1_000 },
            remoteParams = { com.opensecurity.customtts.model.CustomTtsVoiceParams() },
            fallbackSpeed = { 1f },
            remoteSynth = { _, _ ->
                remoteSynthCalls++
                null
            },
            fallbackSynth = { _, _ -> null },
            remoteCacheProbe = { text, _ -> if (text == "a") cached else null },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        probed.start(listOf("a", "b"))
        probed.prefetch(0)
        assertEquals(cached, probed.remoteReady(0)?.file)
        assertEquals(0, remoteSynthCalls)
        // 未命中缓存的下一段正常走合成
        probed.prefetch(1)
        assertEquals(1, remoteSynthCalls)
    }

    @Test
    fun `速度变更后已调度段保持旧烘焙速度新段用新速度`() = runTest(UnconfinedTestDispatcher()) {
        var speed = 0.8f
        val oldFile = temp("old-speed")
        val coordinator = coordinator(
            policy = policy(),
            remoteParams = { CustomTtsVoiceParams(speed = speed) },
            remoteSynth = { _, params -> if (params.speed == 0.8f) oldFile else temp("new-speed") },
            fallbackSynth = { _, _ -> null },
        )
        coordinator.start(listOf("a", "b"))
        coordinator.prefetch(0)
        speed = 1.2f
        coordinator.prefetch(1)
        // 已调度段不被作废：旧速度文件仍可用（播放端按新目标/旧烘焙比值调速）
        val first = coordinator.remoteReady(0)
        assertEquals(oldFile, first?.file)
        assertEquals(0.8f, first?.bakedSpeed)
        // 新调度的段按新速度合成
        assertEquals(1.2f, coordinator.remoteReady(1)?.bakedSpeed)
    }

    @Test
    fun `兜底文件携带烘焙速度`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator(
            policy = policy(),
            remoteSynth = { _, _ -> null },
            fallbackSpeed = { 0.7f },
            fallbackSynth = { _, _ -> temp("fbSpeed") },
        )
        coordinator.start(listOf("a"))
        coordinator.prefetch(0)
        assertEquals(0.7f, coordinator.fallbackFile(0)?.bakedSpeed)
    }
}
