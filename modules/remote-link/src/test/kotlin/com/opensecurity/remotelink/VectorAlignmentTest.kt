package com.opensecurity.remotelink

import com.opensecurity.remotelink.crypto.NoiseHandshake
import com.opensecurity.remotelink.crypto.SessionCrypto
import com.opensecurity.remotelink.frame.Fragmenter
import com.opensecurity.remotelink.frame.FrameCodec
import com.opensecurity.remotelink.frame.Msg
import com.opensecurity.remotelink.frame.Reassembler
import com.opensecurity.remotelink.frame.TcpFrameReader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 向量对齐测试（resources/vectors.json 与 Python 参考实现同一文件——三端一致性判据）。 */
class VectorAlignmentTest {

    private fun vectors(): JSONObject {
        val text = javaClass.classLoader!!.getResourceAsStream("vectors.json")!!
            .readBytes().decodeToString()
        return JSONObject(text)
    }

    private fun String.unhex(): ByteArray {
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            out[i] = substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun ByteArray.hex(): String =
        joinToString("") { "%02x".format(it) }

    @Test
    fun noiseDeterministicVector() {
        val v = vectors().getJSONArray("noise_nnposk2").getJSONObject(0)
        val psk = v.getString("psk_hex").unhex()
        val init = NoiseHandshake.initiator(psk, v.getString("init_e_priv_hex").unhex())
        val resp = NoiseHandshake.responder(psk, v.getString("resp_e_priv_hex").unhex())
        val msg1 = init.writeMessage()
        resp.readMessage(msg1)
        val msg2 = resp.writeMessage()
        init.readMessage(msg2)
        assert(msg1.hex() == v.getString("msg1_hex")) { "msg1 不对齐" }
        assert(msg2.hex() == v.getString("msg2_hex")) { "msg2 不对齐" }
        assert(init.handshakeHash.hex() == v.getString("handshake_hash_hex"))
        val (iSend, iRecv) = init.split()
        val (rSend, rRecv) = resp.split()
        assert(iSend.hex() == v.getString("init_send_key_hex"))
        assert(iRecv.hex() == v.getString("init_recv_key_hex"))
        assert(rSend.hex() == v.getString("resp_send_key_hex"))
        assert(rRecv.hex() == v.getString("resp_recv_key_hex"))
    }

    @Test
    fun sessionRoundtripAndUdpFrame() {
        val v = vectors().getJSONArray("session_roundtrip").getJSONObject(0)
        val key = v.getString("key_hex").unhex()
        val a = SessionCrypto(key, v.getString("key_hex").unhex()) // 自反（向量只证加密确定性）
        val (ct, ctr) = a.encrypt(v.getString("plaintext_hex").unhex())
        assert(ct.hex() == v.getString("ciphertext_hex")) { "会话密文不对齐" }
        assert(ctr == v.getLong("counter"))
        val u = vectors().getJSONArray("udp_frame").getJSONObject(0)
        val wire = u.getString("wire_hex").unhex()
        val (ctr2, payload) = FrameCodec.unpackUdp(wire)
        assert(ctr2 == u.getLong("counter")) { "UDP 帧计数器不对齐" }
        val repacked = FrameCodec.packUdp(ctr2, payload)
        assert(repacked.hex() == u.getString("wire_hex")) { "UDP 帧封装不对齐" }
    }

    @Test
    fun tcpFrameAndStreamParsing() {
        val frames = vectors().getJSONArray("tcp_frame")
        for (i in 0 until frames.length()) {
            val v = frames.getJSONObject(i)
            val payload = v.getString("payload_hex").unhex()
            assert(FrameCodec.packTcp(payload).hex() == v.getString("wire_hex"))
        }
        // 流式分片解析（半帧累积）
        val v0 = frames.getJSONObject(0)
        val wire = v0.getString("wire_hex").unhex()
        val reader = TcpFrameReader()
        assert(reader.feed(wire.copyOfRange(0, 3)).isEmpty())
        val out = reader.feed(wire.copyOfRange(3, wire.size))
        assert(out.size == 1 && out[0].hex() == v0.getString("payload_hex"))
    }

    @Test
    fun fragmentSplitReassemble() {
        val f = vectors().getJSONArray("frag").getJSONObject(0)
        val plaintext = f.getString("plaintext_hex").unhex()
        val frags = Fragmenter.split(f.getInt("id"), plaintext)
        val jsonFrags: JSONArray = f.getJSONArray("frags")
        assert(frags.size == jsonFrags.length())
        for (i in 0 until jsonFrags.length()) {
            val jf = jsonFrags.getJSONObject(i)
            assert(jf.getInt("seq") == i)
            val got = Msg.encode(frags[i]).hex()
            val want = jf.getString("msg_hex")
            if (got != want) {
                println("frag$i got : $got")
                println("frag$i want: $want")
            }
            assert(got == want) { "分片 $i msgpack 不对齐" }
        }
        // 乱序重组（末片先到 → 构造; 0/1 补齐 → 集齐）
        val reasm = Reassembler(frags.last())
        for (i in 0 until frags.size - 1) {
            val assembled = reasm.feed(frags[i])
            if (assembled != null) {
                assert(assembled.hex() == f.getString("plaintext_hex"))
            }
        }
    }

    @Test
    fun punchMacVector() {
        val v = vectors().getJSONArray("punch_mac").getJSONObject(0)
        val hs = NoiseHandshake.initiator(v.getString("psk_hex").unhex())
        assert(hs.punchMac(v.getString("sid")) == v.getString("mac_hex"))
    }

    @Test
    fun msgRoundtrip() {
        val msg = mapOf(
            "t" to "api.req", "id" to 7, "speed" to 1.5,
            "body" to mapOf("k" to listOf(1, 2.5, "s")),
            "data" to byteArrayOf(1, 2, 3)
        )
        val decoded = Msg.decode(Msg.encode(msg))
        assert(decoded["t"] == "api.req")
        assert((decoded["id"] as Number).toLong() == 7L)
        @Suppress("UNCHECKED_CAST")
        val body = decoded["body"] as Map<String, Any?>
        val list = body["k"] as List<*>
        assert((list[0] as Number).toLong() == 1L && list[2] == "s")
        assert((decoded["data"] as ByteArray).contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun littleEndianSanity() {
        // Python int.to_bytes(8,'little') 对齐验证
        val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0x0102030405L).array()
        assert(b[0] == 0x05.toByte() && b[4] == 0x01.toByte())
    }
}
