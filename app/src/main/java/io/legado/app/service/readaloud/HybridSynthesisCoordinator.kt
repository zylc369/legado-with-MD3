package io.legado.app.service.readaloud

import com.opensecurity.customtts.hybrid.HybridTtsPolicy
import com.opensecurity.customtts.model.CustomTtsSegment
import com.opensecurity.customtts.model.CustomTtsVoiceParams
import com.opensecurity.customtts.model.HybridTtsDecision
import com.opensecurity.customtts.model.HybridTtsSwitch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * 混合朗读段级调度（段级赛跑）：
 * - 每段播放前判定远程音频是否就绪：就绪用远程，未就绪立即用兜底
 * - 远程/兜底均预取（返回驱动 + 前瞻窗口 2）
 * - 远程请求超时即放弃该段（晚到结果由合成器自行缓存，不阻塞播放）
 * - 合成一律在 [ioDispatcher] 执行（阻塞 IO 不得落在主线程——否则弱网会 ANR）
 * - 切换事件经 [HybridTtsPolicy] 防抖后回调
 */
class HybridSynthesisCoordinator(
    private val scope: CoroutineScope,
    private val policy: HybridTtsPolicy,
    private val remoteTimeoutMs: Long,
    private val remoteParams: CustomTtsVoiceParams,
    private val fallbackSpeed: Float,
    private val remoteSynth: suspend (CustomTtsSegment, CustomTtsVoiceParams) -> File?,
    private val fallbackSynth: suspend (CustomTtsSegment, Float) -> File?,
    private val onSwitch: (HybridTtsSwitch) -> Unit,
    private val fallbackTimeoutMs: Long = 15_000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var segments: List<CustomTtsSegment> = emptyList()
    private val remoteJobs = HashMap<Int, Deferred<File?>>()
    private val fallbackJobs = HashMap<Int, Deferred<File?>>()

    fun start(segmentList: List<CustomTtsSegment>) {
        stop()
        segments = segmentList
        policy.reset()
        schedule(0)
    }

    /** 播放第 [index] 段前调用：返回该段可播放文件（远程优先，否则兜底；均失败 = null）。 */
    suspend fun audioFor(index: Int): File? {
        if (index !in segments.indices) return null
        schedule(index)

        val remoteJob = remoteJobs[index]
        val remoteReady = remoteJob?.isCompleted == true &&
            runCatching { remoteJob.getCompleted() }.getOrNull() != null
        val decision = policy.decide(index, remoteReady)

        // 返回驱动 + 前瞻窗口
        schedule(index + 1)
        schedule(index + 2)

        val remoteFile = if (decision == HybridTtsDecision.Remote) {
            remoteJob?.let { runCatching { it.await() }.getOrNull() }
        } else {
            null
        }
        drainSwitches()
        if (remoteFile != null) return remoteFile
        return fallbackJobs[index]?.let { runCatching { it.await() }.getOrNull() }
    }

    fun stop() {
        remoteJobs.values.forEach { it.cancel() }
        fallbackJobs.values.forEach { it.cancel() }
        remoteJobs.clear()
        fallbackJobs.clear()
        segments = emptyList()
        policy.reset()
    }

    private fun schedule(index: Int) {
        if (index !in segments.indices) return
        val segment = segments[index]
        if (index !in remoteJobs) {
            remoteJobs[index] = scope.async(ioDispatcher) {
                runCatching {
                    withTimeout(remoteTimeoutMs) { remoteSynth(segment, remoteParams) }
                }.getOrNull()
            }
        }
        if (index !in fallbackJobs) {
            fallbackJobs[index] = scope.async(ioDispatcher) {
                runCatching {
                    withTimeout(fallbackTimeoutMs) { fallbackSynth(segment, fallbackSpeed) }
                }.getOrNull()
            }
        }
    }

    private fun drainSwitches() {
        policy.drainSwitchEvents(System.currentTimeMillis()).forEach(onSwitch)
    }
}
