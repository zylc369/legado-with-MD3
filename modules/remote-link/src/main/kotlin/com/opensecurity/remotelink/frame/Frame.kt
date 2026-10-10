package com.opensecurity.remotelink.frame

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 统一帧编解码（wire protocol §3——与 Python 端逐字节对齐）。
 *
 * TCP 帧: [4B big-endian 长度 N][payload]
 * UDP 帧: [8B little-endian 计数器][payload（密文）]
 */
object FrameCodec {
    const val TCP_FRAME_MAX = 1 shl 20

    /** TCP 帧封装。 */
    fun packTcp(payload: ByteArray): ByteArray {
        require(payload.size <= TCP_FRAME_MAX) { "TCP 帧超限: ${payload.size}" }
        val out = ByteArray(4 + payload.size)
        out[0] = (payload.size ushr 24).toByte()
        out[1] = (payload.size ushr 16).toByte()
        out[2] = (payload.size ushr 8).toByte()
        out[3] = payload.size.toByte()
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }

    /** UDP 帧封装。 */
    fun packUdp(counter: Long, payload: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(counter).array()
        return nonce + payload
    }

    /** UDP 帧解包 → (counter, payload)。 */
    fun unpackUdp(datagram: ByteArray): Pair<Long, ByteArray> {
        require(datagram.size >= 8) { "UDP 帧过短" }
        val counter = ByteBuffer.wrap(datagram, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        return counter to datagram.copyOfRange(8, datagram.size)
    }
}

/** TCP 流式帧解析器（缓冲 + 定界; 与 Python TcpFrameReader 行为一致）。 */
class TcpFrameReader {
    private var buf = ByteArray(0)

    /** 追加字节流，弹出已完整帧的 payload 列表。 */
    fun feed(data: ByteArray): List<ByteArray> {
        buf += data
        val out = ArrayList<ByteArray>()
        while (buf.size >= 4) {
            val n = ((buf[0].toInt() and 0xFF) shl 24) or ((buf[1].toInt() and 0xFF) shl 16) or
                ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            require(n <= FrameCodec.TCP_FRAME_MAX) { "TCP 帧长度超限: $n" }
            if (buf.size < 4 + n) break
            out.add(buf.copyOfRange(4, 4 + n))
            buf = buf.copyOfRange(4 + n, buf.size)
        }
        return out
    }
}

/** UDP 分片（wire protocol §5——每片独立加密为一个 UDP 帧）。 */
object Fragmenter {
    const val FRAG_LIMIT = 900
    const val FRAG_TOTAL_MAX = 256

    fun needSplit(plaintext: ByteArray): Boolean = plaintext.size > FRAG_LIMIT

    /** 返回分片消息列表（msgpack Map 字节——每片已是可独立编码的消息）。 */
    fun split(msgId: Int, plaintext: ByteArray): List<Map<String, Any?>> {
        val total = (plaintext.size + FRAG_LIMIT - 1) / FRAG_LIMIT
        require(total <= FRAG_TOTAL_MAX) { "消息过大: $total 片" }
        return (0 until total).map { i ->
            mapOf(
                "t" to "_frag", "id" to msgId, "seq" to i, "total" to total,
                "data" to plaintext.copyOfRange(i * FRAG_LIMIT,
                    minOf((i + 1) * FRAG_LIMIT, plaintext.size))
            )
        }
    }
}

/** 分片重组（100ms 窗口的 NACK 由 transport 层驱动; 本类纯状态）。 */
class Reassembler(firstFrag: Map<String, Any?>) {
    private val msgId: Int = (firstFrag["id"] as Number).toInt()
    private val total: Int = (firstFrag["total"] as Number).toInt()
    private val parts = HashMap<Int, ByteArray>()
    var dead = false
        private set

    init {
        require(total in 1..Fragmenter.FRAG_TOTAL_MAX) { "分片 total 非法: $total" }
        feed(firstFrag)  // 构造即喂首片（与 Python 参考实现行为一致）
    }

    /** 喂入分片（NACK 补片重发同 id 幂等）; 集齐返回完整明文。 */
    fun feed(frag: Map<String, Any?>): ByteArray? {
        if (dead) return null
        require((frag["id"] as Number).toInt() == msgId) { "分片 id 不匹配" }
        require((frag["total"] as Number).toInt() == total) { "分片 total 不匹配" }
        val seq = (frag["seq"] as Number).toInt()
        val data = frag["data"] as? ByteArray ?: return null
        parts[seq] = data
        if (parts.size == total) {
            val out = ByteArrayOutputStream()
            for (i in 0 until total) out.write(parts[i])
            return out.toByteArray()
        }
        return null
    }

    /** 缺失序号（NACK 生成）。 */
    fun missing(): List<Int> = (0 until total).filter { it !in parts }
}
