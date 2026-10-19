package com.opensecurity.remotelink.frame

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FragmenterReassemblerTest {

    private fun data(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun seqOf(frag: Map<String, Any?>) = (frag["seq"] as Number).toInt()

    private fun totalOf(frag: Map<String, Any?>) = (frag["total"] as Number).toInt()

    @Test
    fun `needSplit 边界`() {
        assertFalse(Fragmenter.needSplit(ByteArray(Fragmenter.FRAG_LIMIT)))
        assertTrue(Fragmenter.needSplit(ByteArray(Fragmenter.FRAG_LIMIT + 1)))
    }

    @Test
    fun `split 切片数与元数据`() {
        val payload = data(2000) // 2000 / 900 → 3 片
        val frags = Fragmenter.split(7, payload)
        assertEquals(3, frags.size)
        frags.forEachIndexed { i, f ->
            assertEquals("_frag", f["t"])
            assertEquals(7, (f["id"] as Number).toInt())
            assertEquals(3, totalOf(f))
            assertEquals(i, seqOf(f))
        }
    }

    @Test
    fun `超 256 片拒绝`() {
        val tooBig = data(Fragmenter.FRAG_LIMIT * (Fragmenter.FRAG_TOTAL_MAX + 1))
        assertThrows(IllegalArgumentException::class.java) { Fragmenter.split(1, tooBig) }
    }

    @Test
    fun `乱序重组还原`() {
        val payload = data(2000)
        val frags = Fragmenter.split(7, payload)
        val reassembler = Reassembler(frags[2]) // 构造即喂第 3 片
        assertNull(reassembler.feed(frags[0]))
        val out = reassembler.feed(frags[1])
        assertArrayEquals(payload, out)
    }

    @Test
    fun `缺片 missing 与重复片幂等`() {
        val payload = data(2000)
        val frags = Fragmenter.split(1, payload)
        val reassembler = Reassembler(frags[0])
        reassembler.feed(frags[2])
        assertEquals(listOf(1), reassembler.missing())
        assertNull(reassembler.feed(frags[2])) // 重复片幂等
        val out = reassembler.feed(frags[1])
        assertArrayEquals(payload, out)
    }

    @Test
    fun `total 非法拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            Reassembler(mapOf("t" to "_frag", "id" to 1, "seq" to 0, "total" to 0, "data" to ByteArray(1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Reassembler(mapOf("t" to "_frag", "id" to 1, "seq" to 0, "total" to 257, "data" to ByteArray(1)))
        }
    }

    @Test
    fun `片 id 不匹配拒绝`() {
        val frags = Fragmenter.split(7, data(2000))
        val reassembler = Reassembler(frags[0])
        assertThrows(IllegalArgumentException::class.java) {
            reassembler.feed(mapOf("t" to "_frag", "id" to 8, "seq" to 1, "total" to 3, "data" to ByteArray(1)))
        }
    }
}
