package io.legado.app.service.readaloud

import com.opensecurity.customtts.model.CustomTtsVoiceParams
import com.opensecurity.customtts.model.HybridTtsConfig
import com.opensecurity.customtts.model.HybridTtsDecision
import com.opensecurity.customtts.hybrid.HybridTtsPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 混合模式“真实时序”仿真：
 * - 远端合成与网关一致：**单锁串行**，每段固定 5.5s（实测 5.3~6s）；
 * - 播放时长按字数估算（1x 下 4 字/秒，即每字 250ms）；
 * - 驱动顺序与 HttpReadAloudService.runHybridDriver 一致：
 *   每段开始预取后 2 段 → 判定远端是否就绪 → 播放本段。
 *
 * 目标是验证：冷启动后绝大多数分段真的用上远端，且不会因排队触发超时/熔断。
 */
class HybridRemotePipelineSimulationTest {

    private val remoteLatencyMs = 5_500L
    private val msPerChar = 250L

    /** 来自真实章节的近似长度分布：标题 + 首段很短 + 后续常规长段。 */
    private val texts = listOf(
        "第三百一十八章",
        "身上鲜血快速沸腾，几乎将女人身体包裹起来。",
        "却正好与赵毅目光交汇。她第一反应是赵少爷正无可奈何地看着自己，看着自己叼走了陷阱上的肉后还能从容离开。",
        "但下一刻，她心底猛地升腾起一道警兆，不对，这位赵少爷好像从很早之前，就一直保持着看向这里的发呆姿势。",
        "他不是在主动看向自己，而是自己站到了他一直所盯着的位置。这时，梁艳捂着胸口摇摇晃晃地站起身。",
        "女人意识到不对，是这宅子里的东西在提醒她，可她不敢回头。",
    )

    private fun TestScope.coordinator(
        latencyMs: (String) -> Long = { remoteLatencyMs },
        log: (String) -> Unit,
    ) = HybridSynthesisCoordinator(
        scope = this,
        policy = HybridTtsPolicy(HybridTtsConfig(firstSegmentFallback = true)),
        remoteTimeoutMs = 10_000,
        remoteParams = CustomTtsVoiceParams(),
        fallbackSpeed = 0.7f,
        remoteSynth = { text, params -> fakeGateway.request(text, params, latencyMs) },
        fallbackSynth = { _, _ -> null },
        fallbackEnabled = false,
        log = log,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    /** 网关真实行为：请求全局串行，每段合成固定耗时，随后返回音频文件。 */
    private inner class FakeGateway {
        private val lock = Mutex()
        suspend fun request(
            text: String,
            p: CustomTtsVoiceParams,
            latencyMs: (String) -> Long,
        ): File? = lock.withLock {
            delay(latencyMs(text))
            File.createTempFile("hybrid-remote", ".wav").also { it.deleteOnExit() }
        }
    }

    private val fakeGateway = FakeGateway()

    @Test
    fun `真实时序下冷启动后绝大多数分段使用远端且无超时熔断`() = runTest {
        val logs = mutableListOf<String>()
        val coordinator = coordinator { logs += it }
        coordinator.start(texts)

        val usedRemote = BooleanArray(texts.size)
        for (index in texts.indices) {
            // 与驱动一致：预取后 2 段，再决定本段；标题段不请求远端（独立坐标系）。
            coordinator.prefetch(index + 1)
            coordinator.prefetch(index + 2)
            val isTitle = index == 0
            val remote = if (isTitle) null else coordinator.remoteReady(index)
            val decision = coordinator.decide(index, remote != null)
            usedRemote[index] = decision == HybridTtsDecision.Remote
            // 播放本段（远端/兜底时长一致：按字数）
            delay(texts[index].length * msPerChar)
        }

        // 首段（标题）与首段正文可能还在等合成；从第 3 个单元（第 2 段正文）起应全部走远端
        assertTrue(
            "第 3 个单元起应使用远端，实际=${usedRemote.toList()}",
            usedRemote.drop(3).all { it },
        )
        assertTrue(
            "至少 3 个单元使用远端，实际=${usedRemote.toList()}",
            usedRemote.count { it } >= 3,
        )
        // 串行化后每段时长 5.5s < 10s 超时，不应出现生产日志里的连环超时/熔断
        assertTrue("不应出现远程超时：${logs.filter { it.contains("超时") }}", logs.none { it.contains("远程超时") })
        assertTrue("不应触发熔断：${logs.filter { it.contains("暂停远端请求") }}", logs.none { it.contains("暂停远端请求") })
    }

    @Test
    fun `短分段密集时远端吞吐不足只降级不熔断`() = runTest {
        // 每段都远短于合成耗时（1.5s 播放 vs 5.5s 合成）：吞吐必然不够，
        // 但这是“慢”不是“坏”——不能触发失败熔断，仍应逐段尝试。
        val shortTexts = List(6) { "短句$it，" + "字".repeat(12) }
        val logs = mutableListOf<String>()
        val coordinator = coordinator { logs += it }
        coordinator.start(shortTexts)
        for (index in shortTexts.indices) {
            coordinator.prefetch(index + 1)
            coordinator.prefetch(index + 2)
            coordinator.decide(index, coordinator.remoteReady(index) != null)
            delay(shortTexts[index].length * msPerChar)
        }
        assertTrue("不应出现远程超时", logs.none { it.contains("远程超时") })
        assertTrue("不应触发熔断", logs.none { it.contains("暂停远端请求") })
    }

    @Test
    fun `长段合成超过固定10s时按字数放宽超时仍可使用远端`() = runTest {
        // 合成耗时 = 4s + 80ms/字：88 字约 11.0s，超过旧的固定 10s 超时
        val longTexts = listOf(
            "第三百二十章",
            "在经过院子里的那口井时，" + "月光照在井沿上，水面纹丝不动，像一面被遗忘了很久的镜子，".repeat(3),
            "他顿了顿，抬手把井绳慢慢放下去，" + "绳子摩擦井沿的声音在夜里格外清楚，一下，又一下，".repeat(3),
            "井底没有回声，" + "他盯着那片黑，心里忽然升起一种说不清道不明的预感，像是有什么东西正在下面抬头看着他，".repeat(2),
        )
        val logs = mutableListOf<String>()
        val coordinator = coordinator(
            log = { logs += it },
            latencyMs = { text -> 4_000L + text.length * 80L },
        )
        coordinator.start(longTexts)
        val usedRemote = BooleanArray(longTexts.size)
        for (index in longTexts.indices) {
            coordinator.prefetch(index + 1)
            coordinator.prefetch(index + 2)
            val isTitle = index == 0
            val remote = if (isTitle) null else coordinator.remoteReady(index)
            usedRemote[index] = coordinator.decide(index, remote != null) == HybridTtsDecision.Remote
            delay(longTexts[index].length * msPerChar)
        }
        assertTrue("长段应至少从第 2 个正文单元起使用远端：${usedRemote.toList()}", usedRemote.drop(2).any { it })
        assertTrue("不应因固定 10s 超时熔断：${logs.filter { it.contains("暂停远端请求") }}", logs.none { it.contains("暂停远端请求") })
    }
}
