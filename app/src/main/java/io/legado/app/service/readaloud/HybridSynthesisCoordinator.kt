package io.legado.app.service.readaloud

import com.opensecurity.customtts.hybrid.HybridTtsPolicy
import com.opensecurity.customtts.model.CustomTtsVoiceParams
import com.opensecurity.customtts.model.HybridTtsDecision
import com.opensecurity.customtts.model.HybridTtsSwitch
import io.legado.app.help.readaloud.playback.ReadAloudRemoteTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HybridSynthesisCoordinator(
    private val scope: CoroutineScope,
    private val policy: HybridTtsPolicy,
    private val remoteTimeoutMs: Long,
    private val remoteParams: CustomTtsVoiceParams,
    private val fallbackSpeed: Float,
    private val remoteSynth: suspend (String, CustomTtsVoiceParams) -> File?,
    private val fallbackSynth: suspend (String, Float) -> File?,
    private val fallbackTimeoutMs: Long = 15_000,
    /** 远端连续失败多少次后进入冷却（期间不再发起远端请求，兜底继续）。 */
    private val remoteFailureThreshold: Int = 2,
    /** 冷却时长；到期后下一段自动重试远端。 */
    private val remoteCooldownMs: Long = 30_000,
    /** 远端合成并发上限；默认 1（与服务端 TTS 单 worker 对齐）。 */
    private val remoteConcurrency: Int = 1,
    /**
     * 远端缓存探测（快路径）：命中直接完成，**不占串行闸门**。
     * 缓存命中不碰服务端，若排在长合成后面会白白错过本段决策点
     * （实测：1ms 缓存命中排在 6.1s 合成后，导致该段误兜底）。
     */
    private val remoteCacheProbe: (suspend (String, CustomTtsVoiceParams) -> File?)? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 系统兜底改由 TTS 直接 speak 时关闭文件兜底，避免无意义的落盘合成。 */
    private val fallbackEnabled: Boolean = true,
    /** 诊断日志入口（测试默认丢弃；app 注入 AppLog）。 */
    private val log: (String) -> Unit = {},
    private val logError: (String, Throwable) -> Unit = { _, _ -> },
) {
    private var texts: List<String> = emptyList()
    private val remoteJobs = HashMap<Int, Deferred<File?>>()
    private val fallbackJobs = HashMap<Int, Deferred<File?>>()

    /** 远端健康状态（跨段共享；网络故障不是段级的，逐段重试只会堆叠超时）。 */
    private val remoteHealthLock = Any()
    private var consecutiveRemoteFailures = 0
    private var remoteCooldownUntilMs = 0L

    /**
     * 远端合成并发上限（线程池语义：超出排队，超时从拿到许可后起算）。
     *
     * 传输层（P2pClient）已支持按 id 多路复用；这里限 1 是**服务端策略**：
     * 服务端 TTS 推理为单 worker（LOCAL_WORKER_POOL_SIZE=1），并发请求只会在
     * 服务端排队、白烧各自的超时预算。服务端扩容后调大 [remoteConcurrency] 即可。
     */
    private val remoteGate = Semaphore(remoteConcurrency)

    fun start(textList: List<String>) {
        stop()
        texts = textList
        policy.reset()
    }

    /** 预取（只调度，不等待）。 */
    fun prefetch(index: Int) {
        schedule(index)
    }

    /** 非阻塞读取：该段远端已就绪则返回文件，否则 null。 */
    fun remoteReady(index: Int): File? {
        if (index !in texts.indices) return null
        schedule(index)
        val job = remoteJobs[index] ?: return null
        if (!job.isCompleted) return null
        return runCatching { job.getCompleted() }.getOrNull()
    }

    /** 等兜底文件（云/HTTP 兜底用；系统兜底走 TTS 直接 speak，不会调用）。 */
    suspend fun fallbackFile(index: Int): File? {
        if (!fallbackEnabled) return null
        schedule(index)
        val job = fallbackJobs[index] ?: return null
        return try {
            job.await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** 段级决策（复用同一策略，保证首段兜底与切换 Toast 只有一套逻辑）。 */
    fun decide(index: Int, remoteReady: Boolean): HybridTtsDecision =
        policy.decide(index, remoteReady)

    fun drainSwitches(): List<HybridTtsSwitch> =
        policy.drainSwitchEvents(System.currentTimeMillis())

    fun stop() {
        remoteJobs.values.forEach { it.cancel() }
        fallbackJobs.values.forEach { it.cancel() }
        remoteJobs.clear()
        fallbackJobs.clear()
        texts = emptyList()
        policy.reset()
    }

    private fun remoteCooldownActive(): Boolean {
        val until = synchronized(remoteHealthLock) { remoteCooldownUntilMs }
        if (until == 0L) return false
        if (nowMs() < until) return true
        synchronized(remoteHealthLock) {
            if (remoteCooldownUntilMs != 0L && nowMs() >= remoteCooldownUntilMs) {
                remoteCooldownUntilMs = 0L
                consecutiveRemoteFailures = 0
                log("混合TTS: 远端冷却结束，恢复远端请求")
            }
        }
        return false
    }

    private fun onRemoteSuccess() {
        synchronized(remoteHealthLock) {
            consecutiveRemoteFailures = 0
            remoteCooldownUntilMs = 0L
        }
    }

    private fun onRemoteFailure() {
        val enteredCooldown: Boolean
        val failures: Int
        synchronized(remoteHealthLock) {
            if (remoteCooldownUntilMs != 0L) return
            consecutiveRemoteFailures++
            failures = consecutiveRemoteFailures
            enteredCooldown = failures >= remoteFailureThreshold
            if (enteredCooldown) {
                remoteCooldownUntilMs = nowMs() + remoteCooldownMs
            }
        }
        if (enteredCooldown) {
            log(
                "混合TTS: 远端连续失败 ${failures} 次，暂停远端请求 ${remoteCooldownMs}ms" +
                    "（继续使用兜底）"
            )
        }
    }

    private fun schedule(index: Int) {
        if (index !in texts.indices) return
        val segment = texts[index]
        if (index !in remoteJobs && !remoteCooldownActive()) {
            log(
                "混合TTS: 预取段 $index remote（voice=${remoteParams.voiceId.ifEmpty { "<默认>" }}" +
                    ", timeout=${remoteTimeoutMs}ms, textLen=${segment.length}）"
            )
            remoteJobs[index] = scope.async(ioDispatcher) {
                val cached = remoteCacheProbe
                    ?.let { probe -> runCatching { probe(segment, remoteParams) }.getOrNull() }
                if (cached != null) {
                    onRemoteSuccess()
                    log("混合TTS: 段 $index 远程缓存命中（闸门外） ${cached.name}")
                    cached
                } else {
                    remoteGate.withPermit {
                        synthesizeRemote(index, segment)
                    }
                }
            }
        }
        if (fallbackEnabled && index !in fallbackJobs) {
            log(
                "混合TTS: 预取段 $index fallback（speed=$fallbackSpeed" +
                    ", textLen=${segment.length}）"
            )
            fallbackJobs[index] = scope.async(ioDispatcher) {
                try {
                    withTimeout(fallbackTimeoutMs) { fallbackSynth(segment, fallbackSpeed) }
                } catch (e: TimeoutCancellationException) {
                    log("混合TTS: 段 $index 兜底超时（${fallbackTimeoutMs}ms）")
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logError("混合TTS: 段 $index 兜底合成失败: ${e.localizedMessage}", e)
                    null
                }
            }
        }
    }

    private suspend fun synthesizeRemote(index: Int, segment: String): File? {
        val startedAt = System.currentTimeMillis()
        val effectiveTimeoutMs = ReadAloudRemoteTimeout.effectiveMs(remoteTimeoutMs, segment.length)
        val result = try {
            withTimeout(effectiveTimeoutMs) { remoteSynth(segment, remoteParams) }
        } catch (e: TimeoutCancellationException) {
            val elapsed = System.currentTimeMillis() - startedAt
            log("混合TTS: 段 $index 远程超时（${elapsed}ms/限制${effectiveTimeoutMs}ms）")
            null
        } catch (e: CancellationException) {
            // 章节切换/停止触发的取消：不是合成失败，向上传播。
            throw e
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startedAt
            // 堆栈由合成分派层（HttpReadAloudService）记录一次即可，避免重复落盘。
            log("混合TTS: 段 $index 远程合成失败（${elapsed}ms）: ${e.localizedMessage}")
            null
        }
        val elapsed = System.currentTimeMillis() - startedAt
        if (result != null) {
            onRemoteSuccess()
            log("混合TTS: 段 $index 远程就绪（${elapsed}ms）${result.name} (${result.length()}B)")
        } else {
            onRemoteFailure()
            log("混合TTS: 段 $index 远程无结果（${elapsed}ms）")
        }
        return result
    }
}
