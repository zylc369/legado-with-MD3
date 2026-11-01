package io.legado.app.service.readaloud

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.legado.app.constant.AppLog

/**
 * 朗读保活音频：持续写入静音帧，让本应用 UID 在系统侧保持“正在播放音频”。
 *
 * 为什么需要：系统 TTS 段的声音由语音引擎进程输出，本应用 UID 在后台没有活跃
 * 音频时可能被厂商省电策略整进程冻结（日志里存在“系统醒着但进程不运行”的窗口）。
 * PARTIAL_WAKE_LOCK 只能防 CPU 深睡，防不住进程冻结；AudioPlaybackConfiguration
 * 处于 active 是系统豁免冻结的判据之一。
 *
 * 线程模型（评审修正点）：
 * - `stop()` 与工作线程的“构建中/写入中”竞争：构建完成后若已停止，立即释放新轨；
 * - 跨线程 release 与阻塞 `write()` 竞争：先 `running=false`，停轨让 write 返回，
 *   再 `join` 工作线程，最后兜底释放——避免在另一线程写入时释放 AudioTrack；
 * - 写失败（音频服务重启等）时主动释放并置 `running=false`，允许下次 start 重建，
 *   不再静默失效。
 */
open class ReadAloudKeepAlive(
    private val sinkFactory: KeepAliveSinkFactory = AndroidKeepAliveSinkFactory,
    private val log: (String) -> Unit = { AppLog.putDebug(it) },
) {

    /** 可注入的音频输出抽象：真实实现是 AudioTrack，测试用同步假实现。 */
    interface KeepAliveSink {
        fun play()
        fun write(bytes: ByteArray): Int
        fun stop()
        fun release()
    }

    fun interface KeepAliveSinkFactory {
        fun create(sampleRate: Int, bufferSize: Int): KeepAliveSink?
    }

    private val lock = Any()
    @Volatile
    var isRunning: Boolean = false
        private set

    private var sink: KeepAliveSink? = null
    private var thread: Thread? = null
    private val silence = ByteArray(BUFFER_SIZE)

    open fun start() {
        synchronized(lock) {
            if (isRunning) return
            isRunning = true
            log("朗读保活音频: 启动")
            thread = Thread({ loop() }, "ReadAloudKeepAlive").apply {
                isDaemon = true
                start()
            }
        }
    }

    open fun stop() {
        val current: Thread?
        synchronized(lock) {
            if (!isRunning) return
            isRunning = false
            // 先停轨：让阻塞中的 write 立即返回，避免跨线程 release
            sink?.let { runCatching { it.stop() } }
            current = thread
            thread = null
        }
        runCatching { current?.join(JOIN_TIMEOUT_MS) }
        releaseSink()
        log("朗读保活音频: 停止")
    }

    private fun loop() {
        val local = sinkFactory.create(SAMPLE_RATE, BUFFER_SIZE)
        if (local == null) {
            log("朗读保活音频: AudioTrack 创建失败")
            finishLoop()
            return
        }
        synchronized(lock) {
            if (!isRunning) {
                // stop() 发生在构建期间：不得泄漏这条轨
                runCatching { local.release() }
                return
            }
            sink = local
        }
        runCatching { local.play() }
        while (isRunning) {
            val written = runCatching { local.write(silence) }.getOrDefault(-1)
            if (written < 0) {
                log("朗读保活音频: 写入失败($written)，结束本轮保活")
                break
            }
        }
        releaseSink()
        finishLoop()
    }

    private fun releaseSink() {
        val local = synchronized(lock) {
            sink.also { sink = null }
        }
        runCatching { local?.release() }
    }

    private fun finishLoop() {
        synchronized(lock) {
            isRunning = false
            thread = null
        }
    }

    private companion object {
        const val SAMPLE_RATE = 8_000
        const val BUFFER_SIZE = 16_384
        const val JOIN_TIMEOUT_MS = 500L
    }
}

/** 真实实现：AudioTrack 静音轨（8kHz 单声道 PCM16，全零帧）。 */
object AndroidKeepAliveSinkFactory : ReadAloudKeepAlive.KeepAliveSinkFactory {
    override fun create(sampleRate: Int, bufferSize: Int): ReadAloudKeepAlive.KeepAliveSink? =
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            object : ReadAloudKeepAlive.KeepAliveSink {
                override fun play() {
                    track.play()
                }

                override fun write(bytes: ByteArray): Int =
                    track.write(bytes, 0, bytes.size)

                override fun stop() {
                    runCatching { track.stop() }
                }

                override fun release() {
                    runCatching { track.release() }
                }
            }
        }.getOrNull()
}
