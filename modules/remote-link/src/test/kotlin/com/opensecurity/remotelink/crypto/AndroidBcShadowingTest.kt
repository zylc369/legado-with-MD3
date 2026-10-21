package com.opensecurity.remotelink.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.security.Provider
import java.security.Security

/**
 * 回归：Android 平台自带名为 "BC" 的裁剪版 provider，且 [Security.addProvider] 不会覆盖
 * 同名 provider（`Security.getProvider("BC")` 永不为空）。该裁剪版不支持
 * ChaCha20-Poly1305，因此握手/会话加密必须显式使用打包进来的 BouncyCastle 实例，
 * 不能按名字 "BC" 取 Cipher——否则设备上 Noise 握手第一步就抛 NoSuchAlgorithmException。
 *
 * 本用例在 JVM 上模拟 Android 的同名遮蔽条件。
 */
class AndroidBcShadowingTest {

    @Test
    fun `平台 BC 被同名裁剪版遮蔽时握手与会话加解密仍成功`() {
        val saved = Security.getProvider("BC")
        if (saved != null) Security.removeProvider("BC")
        val strippedBc = object : Provider("BC", 1.0, "android-like stripped bc") {}
        Security.addProvider(strippedBc)
        try {
            val psk = ByteArray(32) { it.toByte() }
            val initiator = NoiseHandshake.initiator(psk)
            val responder = NoiseHandshake.responder(psk)
            responder.readMessage(initiator.writeMessage())
            initiator.readMessage(responder.writeMessage())
            val (iSend, iRecv) = initiator.split()
            val (rSend, rRecv) = responder.split()
            assertArrayEquals(iSend, rRecv)
            assertArrayEquals(iRecv, rSend)

            val sessionA = SessionCrypto(iSend, iRecv)
            val sessionB = SessionCrypto(rSend, rRecv)
            val plaintext = "hello 加密".toByteArray()
            val ciphertext = sessionA.encryptSeq(plaintext)
            assertArrayEquals(plaintext, sessionB.decryptSeq(ciphertext))
        } finally {
            Security.removeProvider("BC")
            if (saved != null) Security.addProvider(saved)
        }
    }
}
