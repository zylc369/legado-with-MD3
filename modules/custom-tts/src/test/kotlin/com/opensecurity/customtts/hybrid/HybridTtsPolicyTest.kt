package com.opensecurity.customtts.hybrid

import com.opensecurity.customtts.model.HybridTtsConfig
import com.opensecurity.customtts.model.HybridTtsDecision
import com.opensecurity.customtts.model.HybridTtsSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridTtsPolicyTest {

    private fun policy(firstFallback: Boolean = true) = HybridTtsPolicy(
        HybridTtsConfig(firstSegmentFallback = firstFallback),
    )

    @Test
    fun `首段兜底开关开启时强制兜底`() {
        val p = policy(firstFallback = true)
        assertEquals(HybridTtsDecision.Fallback, p.decide(0, remoteReady = true))
        assertTrue(p.drainSwitchEvents(0).isEmpty()) // 首段兜底是预期行为，不提示
    }

    @Test
    fun `首段开关关闭且远程就绪时用远程`() {
        val p = policy(firstFallback = false)
        assertEquals(HybridTtsDecision.Remote, p.decide(0, remoteReady = true))
    }

    @Test
    fun `远程未就绪用兜底`() {
        val p = policy(firstFallback = false)
        assertEquals(HybridTtsDecision.Fallback, p.decide(0, remoteReady = false))
    }

    @Test
    fun `远端恢复产生 ToRemote 事件`() {
        val p = policy(firstFallback = false)
        p.decide(0, remoteReady = false) // 兜底
        assertEquals(HybridTtsDecision.Remote, p.decide(1, remoteReady = true))
        assertEquals(listOf(HybridTtsSwitch.ToRemote), p.drainSwitchEvents(0))
    }

    @Test
    fun `远端断开产生 ToFallback 事件`() {
        val p = policy(firstFallback = false)
        p.decide(0, remoteReady = true) // 远程
        assertEquals(HybridTtsDecision.Fallback, p.decide(1, remoteReady = false))
        assertEquals(listOf(HybridTtsSwitch.ToFallback), p.drainSwitchEvents(0))
    }

    @Test
    fun `同方向事件防抖`() {
        val p = policy(firstFallback = false)
        p.decide(0, remoteReady = true)   // Remote
        p.decide(1, remoteReady = false)  // → ToFallback
        p.decide(2, remoteReady = true)   // → ToRemote
        p.decide(3, remoteReady = false)  // → ToFallback（5s 内重复 → 丢弃）
        val events = p.drainSwitchEvents(0)
        assertEquals(listOf(HybridTtsSwitch.ToFallback, HybridTtsSwitch.ToRemote), events)
    }

    @Test
    fun `防抖窗口过后可再次提示`() {
        val p = policy(firstFallback = false)
        p.decide(0, remoteReady = true)
        p.decide(1, remoteReady = false)
        assertEquals(1, p.drainSwitchEvents(0).size)
        p.decide(2, remoteReady = true)   // → ToRemote（首次）
        p.decide(3, remoteReady = false)  // → ToFallback（超过 5s 窗口 → 再次提示）
        assertEquals(2, p.drainSwitchEvents(6_000).size)
    }

    @Test
    fun `reset 清空状态`() {
        val p = policy(firstFallback = false)
        p.decide(0, remoteReady = true)
        p.decide(1, remoteReady = false)
        p.reset()
        assertEquals(HybridTtsDecision.Fallback, p.decide(0, remoteReady = false))
        assertTrue(p.drainSwitchEvents(0).isEmpty())
    }
}
