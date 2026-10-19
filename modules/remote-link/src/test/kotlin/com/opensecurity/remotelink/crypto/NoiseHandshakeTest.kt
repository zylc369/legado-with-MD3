package com.opensecurity.remotelink.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

class NoiseHandshakeTest {

    private fun psk() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test
    fun `正常握手分钥一致`() {
        val p = psk()
        val i = NoiseHandshake.initiator(p)
        val r = NoiseHandshake.responder(p)
        r.readMessage(i.writeMessage())
        i.readMessage(r.writeMessage())
        val (iSend, iRecv) = i.split()
        val (rSend, rRecv) = r.split()
        assertArrayEquals(iSend, rRecv)
        assertArrayEquals(iRecv, rSend)
    }

    @Test
    fun `错误 PSK 解密 msg2 失败`() {
        val i = NoiseHandshake.initiator(psk())
        val r = NoiseHandshake.responder(psk())
        r.readMessage(i.writeMessage())
        val msg2 = r.writeMessage()
        assertThrows(Exception::class.java) { i.readMessage(msg2) }
    }

    @Test
    fun `msg1 截断拒绝`() {
        val r = NoiseHandshake.responder(psk())
        assertThrows(IllegalArgumentException::class.java) { r.readMessage(ByteArray(31)) }
    }

    @Test
    fun `msg2 截断拒绝`() {
        val p = psk()
        val i = NoiseHandshake.initiator(p)
        val r = NoiseHandshake.responder(p)
        r.readMessage(i.writeMessage())
        val msg2 = r.writeMessage()
        assertThrows(IllegalArgumentException::class.java) {
            i.readMessage(msg2.copyOf(msg2.size - 1))
        }
    }

    @Test
    fun `乱序调用拒绝`() {
        val i = NoiseHandshake.initiator(psk())
        i.writeMessage()
        assertThrows(IllegalStateException::class.java) { i.writeMessage() }
    }

    @Test
    fun `重复读取拒绝`() {
        val p = psk()
        val i = NoiseHandshake.initiator(p)
        val r = NoiseHandshake.responder(p)
        r.readMessage(i.writeMessage())
        val msg2 = r.writeMessage()
        i.readMessage(msg2)
        assertThrows(IllegalStateException::class.java) { i.readMessage(msg2) }
    }

    @Test
    fun `握手未完成 split 拒绝`() {
        val i = NoiseHandshake.initiator(psk())
        assertThrows(IllegalStateException::class.java) { i.split() }
    }

    @Test
    fun `PSK 长度非法拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            NoiseHandshake.initiator(ByteArray(16))
        }
    }
}
