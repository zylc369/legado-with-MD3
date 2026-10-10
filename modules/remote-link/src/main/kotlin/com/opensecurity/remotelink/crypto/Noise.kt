package com.opensecurity.remotelink.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Noise NNpsk2 握手（wire protocol §2/附录A——与 Python 端逐字节对齐）。
 *
 * 原语: X25519 + ChaCha20-Poly1305 + SHA-256; PSK 32B。
 * 消息: -> e / <- e, ee, psk（空 payload）。
 */
class NoiseHandshake private constructor(
    private val initiator: Boolean,
    private val psk: ByteArray,
    privateE: ByteArray?
) {
    companion object {
        init {
            // BC Provider 提供 ChaCha20-Poly1305 的 JCA 覆盖（Android 平台 API 不足）
            if (java.security.Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                runCatching { java.security.Security.addProvider(BouncyCastleProvider()) }
            }
        }

        private val PROTOCOL_NAME = "Noise_NNpsk2_25519_ChaChaPoly_SHA256".toByteArray()
        const val PSK_LEN = 32

        fun initiator(psk: ByteArray, ePriv: ByteArray? = null) =
            NoiseHandshake(true, psk, ePriv)

        fun responder(psk: ByteArray, ePriv: ByteArray? = null) =
            NoiseHandshake(false, psk, ePriv)

        private fun sha256(vararg parts: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(parts.reduce { a, b -> a + b })

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        /** Noise HKDF expand（temp=HMAC(ck,ikm); out_i=HMAC(temp, out_{i-1}||i)）。 */
        private fun hkdf(chainingKey: ByteArray, ikm: ByteArray, n: Int): List<ByteArray> {
            val temp = hmac(chainingKey, ikm)
            val out = ArrayList<ByteArray>()
            var prev = ByteArray(0)
            for (i in 1..n) {
                prev = hmac(temp, prev + byteArrayOf(i.toByte()))
                out.add(prev)
            }
            return out
        }
    }

    init {
        require(psk.size == PSK_LEN) { "PSK 必须 32 字节" }
    }

    private var h: ByteArray =
        if (PROTOCOL_NAME.size <= 32)
            PROTOCOL_NAME + ByteArray(32 - PROTOCOL_NAME.size)
        else sha256(PROTOCOL_NAME)
    private var ck: ByteArray = h.copyOf()
    private var k: ByteArray? = null
    private var nonce = 0L
    private val ePrivBytes: ByteArray =
        if (privateE != null) privateE.copyOf()
        else X25519KeyPairGenerator()
            .apply { init(X25519KeyGenerationParameters(SecureRandom())) }
            .generateKeyPair().let {
                (it.private as X25519PrivateKeyParameters).encoded
            }
    private var eRemotePub: X25519PublicKeyParameters? = null
    private var step = 0
    private var done = false
    private var expectWrite = initiator

    val handshakeHash: ByteArray get() = h.copyOf()

    private fun mixHash(data: ByteArray) { h = sha256(h, data) }

    private fun mixKey(ikm: ByteArray) {
        val o = hkdf(ck, ikm, 2)
        ck = o[0]; k = o[1]
    }

    private fun mixKeyAndHash(ikm: ByteArray) {
        val o = hkdf(ck, ikm, 3)
        ck = o[0]; k = o[2]
        mixHash(o[1])
    }

    private fun aeadEncrypt(key: ByteArray, n: Long, aad: ByteArray, pt: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305", "BC")
        val iv = ByteBufferLittle.longTo12(n)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "ChaCha20"), javax.crypto.spec.IvParameterSpec(iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(pt)
    }

    private fun aeadDecrypt(key: ByteArray, n: Long, aad: ByteArray, ct: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305", "BC")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "ChaCha20"), javax.crypto.spec.IvParameterSpec(ByteBufferLittle.longTo12(n)))
        cipher.updateAAD(aad)
        return cipher.doFinal(ct)
    }

    private fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val key = k
        val ct = if (key == null) plaintext
        else {
            val out = aeadEncrypt(key, nonce, h, plaintext)
            nonce += 1
            out
        }
        mixHash(ct)
        return ct
    }

    private fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val key = k
        val pt = if (key == null) ciphertext
        else {
            val out = aeadDecrypt(key, nonce, h, ciphertext)
            nonce += 1
            out
        }
        mixHash(ciphertext)
        return pt
    }

    private fun dh(): ByteArray =
        Xdh.agree(ePrivBytes, eRemotePub!!.encoded)

    /** 写握手消息（msg1/initiator 或 msg2/responder）。 */
    fun writeMessage(payload: ByteArray = ByteArray(0)): ByteArray {
        check(!done && expectWrite) { "握手已完成或操作次序错误" }
        val ep = Xdh.publicKey(ePrivBytes)
        return if (initiator) {
            mixHash(ep)
            val body = encryptAndHash(payload) // k=null → 明文附加
            advance()
            ep + body
        } else {
            mixHash(ep)
            mixKey(dh())
            mixKeyAndHash(psk)
            val body = encryptAndHash(payload)
            advance()
            ep + body
        }
    }

    /** 读对端握手消息，返回附带 payload。 */
    fun readMessage(message: ByteArray): ByteArray {
        check(!done && !expectWrite) { "握手已完成或操作次序错误" }
        return if (initiator) {
            require(message.size >= 32 + 16) { "msg2 长度不足" }
            eRemotePub = X25519PublicKeyParameters(message, 0)
            mixHash(message.copyOfRange(0, 32))
            mixKey(dh())
            mixKeyAndHash(psk)
            val pt = decryptAndHash(message.copyOfRange(32, message.size))
            advance()
            pt
        } else {
            require(message.size >= 32) { "msg1 长度不足" }
            eRemotePub = X25519PublicKeyParameters(message, 0)
            mixHash(message.copyOfRange(0, 32))
            val pt = decryptAndHash(message.copyOfRange(32, message.size))
            advance()
            pt
        }
    }

    private fun advance() {
        step += 1
        done = step >= 2
        expectWrite = !expectWrite
    }

    /** 派生 (sendKey, recvKey)——按角色定向。 */
    fun split(): Pair<ByteArray, ByteArray> {
        check(done) { "握手未完成" }
        val (k1, k2) = hkdf(ck, ByteArray(0), 2)
        return if (initiator) k1 to k2 else k2 to k1
    }

    /** 打洞包 MAC（§4.4）。 */
    fun punchMac(sid: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(psk, "HmacSHA256"))
        return mac.doFinal("punch:$sid".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
    }
}

/** 小端工具。 */
object ByteBufferLittle {
    fun longTo12(v: Long): ByteArray {
        val b = ByteArray(12)
        for (i in 0 until 8) b[i] = (v ushr (8 * i)).toByte()
        return b
    }
}

/**
 * 会话加解密（§3.3——TCP 隐式计数器严格递增 / UDP 显式计数器 + 防重放）。
 */
class SessionCrypto(sendKey: ByteArray, recvKey: ByteArray) {
    private val sendKey = sendKey.copyOf()
    private val recvKey = recvKey.copyOf()
    private var sendCtr = 0L
    private var recvCtr = 0L
    private val udpSeen = HashSet<Long>()
    private var udpMax = -1L

    class CryptoException(message: String) : Exception(message)

    private fun seal(key: ByteArray, n: Long, pt: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305", "BC")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "ChaCha20"),
            javax.crypto.spec.IvParameterSpec(ByteBufferLittle.longTo12(n)))
        return cipher.doFinal(pt)
    }

    private fun open(key: ByteArray, n: Long, ct: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305", "BC")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "ChaCha20"),
            javax.crypto.spec.IvParameterSpec(ByteBufferLittle.longTo12(n)))
        return cipher.doFinal(ct)
    }

    /** UDP 显式: 返回 (密文, 计数器)。 */
    fun encrypt(plaintext: ByteArray): Pair<ByteArray, Long> {
        val ctr = sendCtr++
        return seal(sendKey, ctr, plaintext) to ctr
    }

    /** UDP 显式解密（防重放）。 */
    fun decrypt(ciphertext: ByteArray, ctr: Long): ByteArray {
        if (ctr in udpSeen) throw CryptoException("UDP 计数器重复: $ctr")
        val pt = open(recvKey, ctr, ciphertext)
        udpSeen.add(ctr)
        if (ctr > udpMax) udpMax = ctr
        return pt
    }

    /** TCP 隐式加密。 */
    fun encryptSeq(plaintext: ByteArray): ByteArray {
        val ctr = sendCtr++
        return seal(sendKey, ctr, plaintext)
    }

    /** TCP 隐式解密（严格连续）。 */
    fun decryptSeq(ciphertext: ByteArray): ByteArray =
        open(recvKey, recvCtr++, ciphertext)
}
