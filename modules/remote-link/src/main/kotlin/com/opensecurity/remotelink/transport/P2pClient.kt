package com.opensecurity.remotelink.transport

import com.opensecurity.remotelink.crypto.NoiseHandshake
import com.opensecurity.remotelink.crypto.SessionCrypto
import com.opensecurity.remotelink.frame.Fragmenter
import com.opensecurity.remotelink.frame.FrameCodec
import com.opensecurity.remotelink.frame.Msg
import com.opensecurity.remotelink.frame.TcpFrameReader
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import org.json.JSONObject

/**
 * P2P 客户端（wire protocol §4/§6/§7 客户端侧——Legado 内使用）。
 *
 * 路径状态机: 无会话 → 打洞直连（punchTimeout）→ 失败落中继 → 全失败抛异常。
 * 线程模型: 同步阻塞 socket（调用方在朗读服务的 IO 线程）; 会话复用直到失效。
 */
class P2pClient(
    private val relayHost: String,
    private val relayPort: Int,
    private val psk: ByteArray,
    private val nodeId: String,
    private val punchTimeoutMs: Int = 1500,
    private val apiTimeoutMs: Int = 30000
) {
    class P2pException(message: String, cause: Throwable? = null)
        : Exception(message, cause)

    private var udpSession: UdpSession? = null
    private var relaySession: RelaySession? = null
    private var nextMsgId = 1
    private val lock = Object()

    /** HTTP 语义 API 调用（api.req/api.resp——status/mime/body）。 */
    fun request(method: String, path: String, body: ByteArray?,
                token: String = ""): Triple<Int, String, Any?> = synchronized(lock) {
        val msgId = nextMsgId++
        val req = HashMap<String, Any?>()
        req["t"] = "api.req"; req["id"] = msgId
        req["method"] = method; req["path"] = path
        req["body"] = body?.let { Msg.decode(it) }
        if (token.isNotEmpty()) req["token"] = token
        val session = ensureSession()
        val resp = try {
            session.send(req)
            awaitResponse(session, msgId)
        } catch (e: Exception) {
            dropSession()
            throw P2pException("请求失败: ${e.message}", e)
        }
        val status = (resp["status"] as? Number)?.toInt() ?: 0
        return Triple(status, resp["mime"] as? String ?: "application/octet-stream",
            resp["body"])
    }

    /** 快速健康探测。 */
    fun ping(): Boolean = synchronized(lock) {
        try {
            val session = ensureSession()
            val msgId = nextMsgId++
            session.send(mapOf("t" to "ping", "id" to msgId))
            awaitResponse(session, msgId, timeoutMs = 5000)
            true
        } catch (_: Exception) {
            dropSession(); false
        }
    }

    // ── 会话建立（§7 状态机）──────────────────────────

    private fun ensureSession(): Session {
        udpSession?.let { if (it.alive) return it else udpSession = null }
        relaySession?.let { if (it.alive) return it else relaySession = null }
        // ① 打洞直连
        try {
            val s = punchAndConnect()
            udpSession = s
            return s
        } catch (_: Exception) {
            // 落中继
        }
        // ② 中继兜底
        val s = relayConnect()
        relaySession = s
        return s
    }

    private fun dropSession() {
        udpSession?.close(); udpSession = null
        relaySession?.close(); relaySession = null
    }

    /** lookup → punch 互射 → Noise 握手（§6; UDP 洞口先建——lookup 携带其本地地址，
     *  回环/锥形 NAT 下该地址即公网映射）。 */
    private fun punchAndConnect(): UdpSession {
        val udp = DatagramSocket()
        val sig = SignalConn(relayHost, relayPort, psk)
        try {
            val localPort = udp.localPort
            val resp = sig.call(mapOf("t" to "lookup", "node" to nodeId,
                "udp_ep" to listOf(udp.localAddress.hostAddress ?: "0.0.0.0", localPort)))
            if ((resp["ok"] as? Boolean) != true) {
                throw P2pException("节点不可达: ${resp["error"]}")
            }
            @Suppress("UNCHECKED_CAST")
            val gw = resp["gw"] as? List<*>
                ?: throw P2pException("endpoints 畸形")
            val sid = resp["sid"] as? String ?: throw P2pException("endpoints 畸形")
            try {
                val gwAddr = InetSocketAddress(gw[0] as? String ?: "",
                    (gw[1] as? Number)?.toInt() ?: 0)
                udp.soTimeout = 200
                val punch = (JSONObject().put("t", "punch").put("sid", sid)
                    .put("mac", NoiseHandshake.initiator(psk).punchMac(sid))
                    .toString() + "\n").toByteArray()
                val init = NoiseHandshake.initiator(psk)
                val msg1 = init.writeMessage()
                repeat(3) { udp.send(DatagramPacket(punch, punch.size, gwAddr)) }
                udp.send(DatagramPacket(msg1, msg1.size, gwAddr))
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + punchTimeoutMs
                var msg2: ByteArray? = null
                while (System.currentTimeMillis() < deadline && msg2 == null) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        udp.receive(pkt)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val data = buf.copyOf(pkt.length)
                    if (data.size == 48 && data[0] != 123.toByte()) {
                        msg2 = data
                    }
                }
                if (msg2 == null) throw P2pException("打洞超时")
                init.readMessage(msg2)
                val (send, recv) = init.split()
                return UdpSession(udp, gwAddr, SessionCrypto(send, recv))
            } catch (e: Exception) {
                udp.close(); throw e
            }
        } finally {
            sig.close()
        }
    }

    /** lookup + relay_open + attach + 端到端握手（§4.2）。 */
    private fun relayConnect(): RelaySession {
        val sig = SignalConn(relayHost, relayPort, psk)
        var data: SignalConn? = null
        try {
            val resp = sig.call(mapOf("t" to "lookup", "node" to nodeId))
            val sid = resp["sid"] as? String
                ?: throw P2pException("节点不可达: ${resp["error"]}")
            val ready = sig.call(mapOf("t" to "relay_open", "node" to nodeId, "sid" to sid))
            if (ready["t"] != "relay_ready") {
                throw P2pException("relay_open 失败: ${ready["code"] ?: ready["t"]}")
            }
            data = SignalConn(relayHost, relayPort, psk)
            val attachResp = data.call(mapOf("t" to "relay_attach", "sid" to sid))
            if (attachResp["t"] != "relay_start") {
                throw P2pException("relay_attach 失败: ${attachResp["t"]}")
            }
            // attach 应答 relay_start 后连接进入管道态——与 gateway 端到端握手
            val init = NoiseHandshake.initiator(psk)
            data.writeRaw(FrameCodec.packTcp(init.writeMessage()))
            val frames = data.readFrames()
            if (frames.isEmpty()) throw P2pException("端到端握手超时")
            init.readMessage(frames[0])
            val (send, recv) = init.split()
            return RelaySession(data, SessionCrypto(send, recv))
        } catch (e: Exception) {
            data?.close(); throw e
        } finally {
            sig.close()
        }
    }

    // ── 会话抽象 ─────────────────────────────────────

    interface Session {
        val alive: Boolean
        fun send(msg: Map<String, Any?>)
        fun recvNext(timeoutMs: Int): Map<String, Any?>?
        fun close()
    }

    /** 收响应直到 id 匹配（忽略乱序/无关消息）。 */
    private fun awaitResponse(session: Session, msgId: Int,
                              timeoutMs: Int = apiTimeoutMs): Map<String, Any?> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val msg = session.recvNext(timeoutMs = 500) ?: continue
            if ((msg["id"] as? Number)?.toInt() == msgId) return msg
        }
        throw P2pException("响应超时（id=$msgId）")
    }

    inner class UdpSession(private val udp: DatagramSocket,
                           private val peer: InetSocketAddress,
                           private val crypto: SessionCrypto) : Session {
        override val alive get() = !udp.isClosed
        private var reasm: com.opensecurity.remotelink.frame.Reassembler? = null
        private var fragId = 0

        override fun send(msg: Map<String, Any?>) {
            val plaintext = Msg.encode(msg)
            if (Fragmenter.needSplit(plaintext)) {
                fragId += 1
                for (frag in Fragmenter.split(fragId, plaintext)) sendPlain(Msg.encode(frag))
            } else {
                sendPlain(plaintext)
            }
        }

        private fun sendPlain(plaintext: ByteArray) {
            val (ct, ctr) = crypto.encrypt(plaintext)
            val wire = FrameCodec.packUdp(ctr, ct)
            synchronized(udp) { udp.send(DatagramPacket(wire, wire.size, peer)) }
        }

        override fun recvNext(timeoutMs: Int): Map<String, Any?>? {
            val buf = ByteArray(65536)
            val pkt = DatagramPacket(buf, buf.size)
            udp.soTimeout = timeoutMs
            return try {
                udp.receive(pkt)
                val (ctr, ct) = FrameCodec.unpackUdp(buf.copyOf(pkt.length))
                val msg = Msg.decode(crypto.decrypt(ct, ctr))
                if (msg["t"] == "_frag") {
                    val r = reasm ?: com.opensecurity.remotelink.frame.Reassembler(msg).also { reasm = it }
                    val assembled = r.feed(msg)
                    if (assembled != null) { reasm = null; Msg.decode(assembled) } else null
                } else msg
            } catch (_: SocketTimeoutException) {
                null
            }
        }

        override fun close() = udp.close()
    }

    class RelaySession(private val conn: SignalConn,
                       private val crypto: SessionCrypto) : Session {
        override val alive get() = conn.alive
        override fun send(msg: Map<String, Any?>) {
            conn.writeRaw(FrameCodec.packTcp(crypto.encryptSeq(Msg.encode(msg))))
        }

        override fun recvNext(timeoutMs: Int): Map<String, Any?>? {
            val frames = conn.readFrames(timeoutMs)
            return frames.firstOrNull()?.let { Msg.decode(crypto.decryptSeq(it)) }
        }

        override fun close() = conn.close()
    }

    /** 信令 TCP 连接（Noise 准入 + 单次 call/response; 复用于管道态需 readFrames）。 */
    class SignalConn(host: String, port: Int, psk: ByteArray) {
        private val socket = Socket().apply {
            tcpNoDelay = true
            soTimeout = 10000
            connect(InetSocketAddress(host, port), 5000)
        }
        private val input: InputStream = socket.getInputStream()
        private val output: OutputStream = socket.getOutputStream()
        private val reader = TcpFrameReader()
        private var pipelineSession: SessionCrypto? = null
        val localPort: Pair<String, Int>? = null  // UDP 洞口由调用方自建（P2pClient 场景）

        init {
            val init = NoiseHandshake.initiator(psk)
            writeRaw(FrameCodec.packTcp(init.writeMessage()))
            val frames = readFrames(5000)
            if (frames.isEmpty()) throw P2pException("准入握手失败")
            init.readMessage(frames[0])
            val (send, recv) = init.split()
            pipelineSession = SessionCrypto(send, recv)
        }

        val alive get() = !socket.isClosed

        fun call(msg: Map<String, Any?>): Map<String, Any?> {
            val s = pipelineSession ?: throw P2pException("无会话")
            writeRaw(FrameCodec.packTcp(s.encryptSeq(Msg.encode(msg))))
            val frames = readFrames(8000)
            if (frames.isEmpty()) throw P2pException("信令响应超时")
            return Msg.decode(s.decryptSeq(frames[0]))
        }

        fun writeRaw(data: ByteArray) {
            synchronized(output) {
                output.write(data); output.flush()
            }
        }

        fun readFrames(timeoutMs: Int = 8000): List<ByteArray> {
            socket.soTimeout = timeoutMs
            val buf = ByteArray(65536)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val frames = reader.feed(ByteArray(0))
                if (frames.isNotEmpty()) return frames
                val n = try {
                    input.read(buf)
                } catch (_: SocketTimeoutException) {
                    return emptyList()
                }
                if (n < 0) return emptyList()
                val got = reader.feed(buf.copyOf(n))
                if (got.isNotEmpty()) return got
            }
            return emptyList()
        }

        fun close() {
            runCatching { socket.close() }
        }
    }
}
