package com.opensecurity.remotelink.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionCryptoTest {

    private val k1 = ByteArray(32) { it.toByte() }
    private val k2 = ByteArray(32) { (255 - it).toByte() }

    /** a.send→b.recv / b.send→a.recv */
    private fun pair() = SessionCrypto(k1, k2) to SessionCrypto(k2, k1)

    @Test
    fun `UDP 加解密往返`() {
        val (a, b) = pair()
        val (ct, ctr) = a.encrypt("hello 你好".toByteArray())
        assertEquals("hello 你好", b.decrypt(ct, ctr).decodeToString())
    }

    @Test
    fun `UDP 重复计数器拒绝`() {
        val (a, b) = pair()
        val (ct, ctr) = a.encrypt("x".toByteArray())
        b.decrypt(ct, ctr)
        assertThrows(SessionCrypto.CryptoException::class.java) { b.decrypt(ct, ctr) }
    }

    @Test
    fun `UDP 错误密钥解密失败`() {
        val a = SessionCrypto(k1, k1)
        val b = SessionCrypto(k2, k2)
        val (ct, ctr) = a.encrypt("x".toByteArray())
        assertThrows(Exception::class.java) { b.decrypt(ct, ctr) }
    }

    @Test
    fun `UDP 乱序计数器可解密`() {
        val (a, b) = pair()
        val (ct0, c0) = a.encrypt("m0".toByteArray())
        val (ct1, c1) = a.encrypt("m1".toByteArray())
        assertEquals("m1", b.decrypt(ct1, c1).decodeToString())
        assertEquals("m0", b.decrypt(ct0, c0).decodeToString())
    }

    @Test
    fun `TCP 序列号连续解密`() {
        val (a, b) = pair()
        assertEquals("m1", b.decryptSeq(a.encryptSeq("m1".toByteArray())).decodeToString())
        assertEquals("m2", b.decryptSeq(a.encryptSeq("m2".toByteArray())).decodeToString())
    }

    @Test
    fun `TCP 跳号解密失败`() {
        val (a, b) = pair()
        a.encryptSeq("lost".toByteArray()) // 丢弃（模拟丢帧）
        val out = a.encryptSeq("m2".toByteArray()) // 计数器=1
        assertThrows(Exception::class.java) { b.decryptSeq(out) } // b 期望 0
    }
}
