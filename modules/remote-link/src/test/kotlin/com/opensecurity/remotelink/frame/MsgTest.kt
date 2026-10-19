package com.opensecurity.remotelink.frame

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.msgpack.core.MessagePack

class MsgTest {

    @Test
    fun `基本类型往返`() {
        val msg = mapOf<String, Any?>(
            "s" to "文本",
            "b" to true,
            "i" to 42,
            "l" to 1234567890123L,
            "f" to 1.5f,
            "d" to 2.75,
            "bin" to byteArrayOf(1, 2, 3),
            "n" to null,
        )
        val decoded = Msg.decode(Msg.encode(msg))
        assertEquals("文本", decoded["s"])
        assertEquals(true, decoded["b"])
        assertEquals(42L, decoded["i"]) // msgpack 整数统一解为 Long
        assertEquals(1234567890123L, decoded["l"])
        assertEquals(1.5, (decoded["f"] as Number).toDouble(), 1e-9)
        assertEquals(2.75, (decoded["d"] as Number).toDouble(), 1e-9)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded["bin"] as ByteArray)
        assertNull(decoded["n"])
    }

    @Test
    fun `嵌套 Map 与 List 往返`() {
        val msg = mapOf<String, Any?>(
            "outer" to mapOf("inner" to listOf(1, "两", mapOf("deep" to true))),
        )
        @Suppress("UNCHECKED_CAST")
        val outer = Msg.decode(Msg.encode(msg))["outer"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val list = outer["inner"] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val deep = list[2] as Map<String, Any?>
        assertEquals(1L, list[0])
        assertEquals("两", list[1])
        assertEquals(true, deep["deep"])
    }

    @Test
    fun `非 map 输入拒绝`() {
        val packed = MessagePack.newDefaultBufferPacker().use {
            it.packInt(1)
            it.toByteArray()
        }
        assertThrows(IllegalArgumentException::class.java) { Msg.decode(packed) }
    }

    @Test
    fun `空消息往返`() {
        val decoded = Msg.decode(Msg.encode(emptyMap()))
        assertTrue(decoded.isEmpty())
    }
}
