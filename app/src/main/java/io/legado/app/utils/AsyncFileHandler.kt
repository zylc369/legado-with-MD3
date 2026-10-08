package io.legado.app.utils

import io.legado.app.help.globalExecutor
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.logging.Handler
import java.util.logging.LogRecord

/**
 * 异步写文件的日志 Handler，带**滚动裁剪**：
 *
 * - 文件大小达到 [maxBytes] 时，丢弃**最旧**的 [trimBytes] 字节（保留最近内容），继续追加；
 *   因此磁盘占用有硬上限，且留下的总是最近的日志。
 * - 所有写/裁剪都在同一把锁下串行化，保证多线程安全（写入来自线程池）。
 * - 不再使用 `FileHandler`，因此不产生 `.lck` 锁文件，也就没有"启动清理删掉活动锁文件"的竞态。
 */
class AsyncFileHandler(
    private val file: File,
    private val maxBytes: Long,
    private val trimBytes: Long,
) : Handler() {

    private val lock = Any()
    private var output: BufferedOutputStream? = null
    private var length = 0L

    override fun publish(record: LogRecord?) {
        if (record == null || !isLoggable(record)) return
        val bytes = runCatching { formatter?.format(record)?.toByteArray() }.getOrNull() ?: return
        globalExecutor.execute {
            synchronized(lock) {
                runCatching {
                    ensureOpen()
                    output?.write(bytes)
                    output?.flush()
                    length += bytes.size
                    if (length >= maxBytes) {
                        trimOldest()
                    }
                }
            }
        }
    }

    private fun ensureOpen() {
        if (output != null) return
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        output = BufferedOutputStream(FileOutputStream(file, true))
        length = file.length()
    }

    /** 关闭当前流，保留文件最后 [maxBytes] - [trimBytes] 字节，再以追加方式重开。 */
    private fun trimOldest() {
        val out = output
        output = null
        runCatching {
            out?.flush()
            out?.close()
        }
        val keep = (maxBytes - trimBytes).coerceAtLeast(0)
        val size = file.length()
        if (size > keep) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            runCatching {
                file.inputStream().use { input ->
                    var toSkip = size - keep
                    while (toSkip > 0) {
                        val skipped = input.skip(toSkip)
                        if (skipped <= 0) break
                        toSkip -= skipped
                    }
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                }
            }.onFailure { tmp.delete() }
        }
        ensureOpen()
    }

    override fun flush() {
        synchronized(lock) {
            runCatching { output?.flush() }
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { output?.close() }
            output = null
        }
    }

}
