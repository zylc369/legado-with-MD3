package io.legado.app.service.readaloud

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保活音频的真实实现（线程/释放语义）：评审指出的三类缺陷回归。
 * sink 可注入，因此不需要 Robolectric，也能确定性地复现竞争。
 */
class ReadAloudKeepAliveTest {

    private class FakeSink(
        private val writeResult: () -> Int = { 1 },
    ) : ReadAloudKeepAlive.KeepAliveSink {
        val played = CountDownLatch(1)
        val writeCalled = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val releasedLatch = CountDownLatch(1)
        val blockWrite = CountDownLatch(1)
        val released = AtomicBoolean(false)
        val releaseCount = AtomicInteger(0)

        override fun play() {
            played.countDown()
        }

        override fun write(bytes: ByteArray): Int {
            writeCalled.countDown()
            val result = writeResult()
            if (result < 0) return result
            // 模拟阻塞写；stop()/release() 会放行
            blockWrite.await(2, TimeUnit.SECONDS)
            Thread.sleep(1)
            return result
        }

        override fun stop() {
            stopped.countDown()
            blockWrite.countDown()
        }

        override fun release() {
            released.set(true)
            releaseCount.incrementAndGet()
            releasedLatch.countDown()
            blockWrite.countDown()
        }
    }

    @Test
    fun `启动后写入，停止时释放音轨且不再运行`() {
        val sink = FakeSink()
        val keepAlive = ReadAloudKeepAlive(sinkFactory = { _, _ -> sink }, log = {})
        keepAlive.start()
        assertTrue(sink.played.await(1, TimeUnit.SECONDS))
        keepAlive.stop()
        assertTrue(sink.released.get())
        assertFalse(keepAlive.isRunning)
        assertEquals(1, sink.releaseCount.get())
    }

    @Test
    fun `构建期间停止不泄漏音轨`() {
        val created = AtomicReference<FakeSink?>()
        val allowCreate = CountDownLatch(1)
        val keepAlive = ReadAloudKeepAlive(
            sinkFactory = { _, _ ->
                val sink = FakeSink().also { created.set(it) }
                allowCreate.await(2, TimeUnit.SECONDS)
                sink
            },
            log = {},
        )
        keepAlive.start()
        // 等工厂进入阻塞，再停止（此时音轨还没交给 loop）
        Thread.sleep(50)
        keepAlive.stop()
        allowCreate.countDown()
        val sink = created.get()
        assertNotNull(sink)
        assertTrue("构建期间被停止的音轨必须释放", sink!!.releasedLatch.await(2, TimeUnit.SECONDS))
        assertFalse(keepAlive.isRunning)
    }

    @Test
    fun `写入失败时释放并允许重新启动`() {
        val first = FakeSink(writeResult = { -1 })
        val second = FakeSink()
        var created = 0
        val keepAlive = ReadAloudKeepAlive(
            sinkFactory = { _, _ -> if (created++ == 0) first else second },
            log = {},
        )
        keepAlive.start()
        assertTrue(first.releasedLatch.await(2, TimeUnit.SECONDS))
        assertFalse(keepAlive.isRunning)

        keepAlive.start()
        assertTrue(second.played.await(1, TimeUnit.SECONDS))
        keepAlive.stop()
        assertTrue(second.released.get())
    }

    @Test
    fun `写入阻塞时停止不卡死`() {
        val sink = FakeSink()
        val keepAlive = ReadAloudKeepAlive(sinkFactory = { _, _ -> sink }, log = {})
        keepAlive.start()
        assertTrue(sink.writeCalled.await(1, TimeUnit.SECONDS))
        val startedAt = System.currentTimeMillis()
        keepAlive.stop()
        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue("stop 不应被阻塞写拖住", elapsed < 1_500)
        assertTrue(sink.released.get())
        assertFalse(keepAlive.isRunning)
    }

    @Test
    fun `重复启动只创建一条音轨`() {
        val count = AtomicInteger(0)
        val sink = FakeSink()
        val keepAlive = ReadAloudKeepAlive(
            sinkFactory = { _, _ ->
                count.incrementAndGet()
                sink
            },
            log = {},
        )
        keepAlive.start()
        keepAlive.start()
        assertTrue(sink.played.await(1, TimeUnit.SECONDS))
        assertEquals(1, count.get())
        keepAlive.stop()
    }
}
