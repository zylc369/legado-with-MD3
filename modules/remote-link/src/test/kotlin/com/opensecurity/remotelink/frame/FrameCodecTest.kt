package com.opensecurity.remotelink.frame

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCodecTest {

    @Test
    fun `TCP 帧往返`() {
        val payload = "hello".toByteArray()
        val framed = FrameCodec.packTcp(payload)
        assertEquals(4 + 5, framed.size)
        val out = TcpFrameReader().feed(framed)
        assertEquals(1, out.size)
        assertArrayEquals(payload, out[0])
    }

    @Test
    fun `TCP 空 payload 帧`() {
        val framed = FrameCodec.packTcp(ByteArray(0))
        assertEquals(4, framed.size)
        val out = TcpFrameReader().feed(framed)
        assertEquals(1, out.size)
        assertEquals(0, out[0].size)
    }

    @Test
    fun `长文本音频量级帧可正常解出`() {
        // 回归：99 字 WAV 约 1.02 MiB，曾被 1 MiB 上限拒收（对端按协议 8 MiB 合法发送）
        val wavLike = ByteArray((1.02 * 1024 * 1024).toInt()) { (it % 251).toByte() }
        val packed = FrameCodec.packTcp(wavLike)
        val reader = TcpFrameReader()
        val frames = reader.feed(packed)
        org.junit.Assert.assertEquals(1, frames.size)
        org.junit.Assert.assertArrayEquals(wavLike, frames[0])
    }

    @Test
    fun `TCP 最大帧可打包与超限拒绝`() {
        FrameCodec.packTcp(ByteArray(FrameCodec.TCP_FRAME_MAX))
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.packTcp(ByteArray(FrameCodec.TCP_FRAME_MAX + 1))
        }
    }

    @Test
    fun `TCP reader 半帧累积与一次多帧`() {
        val a = FrameCodec.packTcp("AAAA".toByteArray())
        val b = FrameCodec.packTcp("BB".toByteArray())
        val concat = a + b
        val reader = TcpFrameReader()
        assertTrue(reader.feed(concat.copyOfRange(0, 3)).isEmpty()) // 半帧
        val out = reader.feed(concat.copyOfRange(3, concat.size))
        assertEquals(2, out.size)
        assertEquals("AAAA", out[0].decodeToString())
        assertEquals("BB", out[1].decodeToString())
    }

    @Test
    fun `TCP reader 超长帧头拒绝`() {
        val bad = byteArrayOf(0x7F, -1, -1, -1) // 0x7FFFFFFF > TCP_FRAME_MAX
        assertThrows(IllegalArgumentException::class.java) { TcpFrameReader().feed(bad) }
    }

    @Test
    fun `UDP 帧往返`() {
        val payload = "data".toByteArray()
        val wire = FrameCodec.packUdp(42L, payload)
        val (ctr, body) = FrameCodec.unpackUdp(wire)
        assertEquals(42L, ctr)
        assertArrayEquals(payload, body)
    }

    @Test
    fun `UDP 帧过短拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.unpackUdp(ByteArray(7))
        }
    }
}
