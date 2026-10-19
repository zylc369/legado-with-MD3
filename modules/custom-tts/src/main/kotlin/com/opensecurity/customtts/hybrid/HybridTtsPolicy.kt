package com.opensecurity.customtts.hybrid

import com.opensecurity.customtts.model.HybridTtsConfig
import com.opensecurity.customtts.model.HybridTtsDecision
import com.opensecurity.customtts.model.HybridTtsSwitch

/**
 * 段级赛跑决策（纯逻辑，无协程/无 IO）。
 *
 * 语义:
 * - 每段播放前判定远程是否就绪；未就绪用兜底，远程请求继续在途
 * - 首段（[HybridTtsConfig.firstSegmentFallback] 开启）强制兜底
 * - 远端恢复后下一段自然切回；不锁定
 * - 切换事件防抖（默认 5s 内同方向只报一次）
 */
class HybridTtsPolicy(
    private val config: HybridTtsConfig,
    private val switchDebounceMs: Long = 5_000,
) {
    private val pending = ArrayList<HybridTtsSwitch>()
    private val lastEmitAt = HashMap<HybridTtsSwitch, Long>()
    private var lastDecision: HybridTtsDecision? = null

    /**
     * 段决策。
     * @param segmentIndex 段序号（0 基）
     * @param remoteReady 该段远程音频是否已就绪（Pending/Failed 均视为未就绪）
     */
    fun decide(segmentIndex: Int, remoteReady: Boolean): HybridTtsDecision {
        val decision = when {
            segmentIndex == 0 && config.firstSegmentFallback -> HybridTtsDecision.Fallback
            remoteReady -> HybridTtsDecision.Remote
            else -> HybridTtsDecision.Fallback
        }
        val prev = lastDecision
        if (prev != null && prev != decision) {
            pending.add(
                if (decision == HybridTtsDecision.Remote) HybridTtsSwitch.ToRemote
                else HybridTtsSwitch.ToFallback
            )
        }
        lastDecision = decision
        return decision
    }

    /** 记录远程请求结果（策略不做锁定——仅为外部状态/统计保留接口）。 */
    fun onRemoteResult(segmentIndex: Int, success: Boolean) {
        // 无状态副作用：决策完全由 decide(segmentIndex, remoteReady) 驱动
    }

    fun onSegmentPlayed(segmentIndex: Int, decision: HybridTtsDecision) {
        lastDecision = decision
    }

    /** 取走待发事件（应用同方向防抖）；事件按产生顺序返回。 */
    fun drainSwitchEvents(nowMs: Long): List<HybridTtsSwitch> {
        val out = ArrayList<HybridTtsSwitch>()
        val it = pending.iterator()
        while (it.hasNext()) {
            val ev = it.next()
            val last = lastEmitAt[ev]
            if (last != null && nowMs - last < switchDebounceMs) {
                it.remove()
                continue
            }
            lastEmitAt[ev] = nowMs
            out.add(ev)
            it.remove()
        }
        return out
    }

    fun reset() {
        pending.clear()
        lastEmitAt.clear()
        lastDecision = null
    }
}
