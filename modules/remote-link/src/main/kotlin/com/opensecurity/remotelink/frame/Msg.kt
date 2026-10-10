package com.opensecurity.remotelink.frame

import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import java.io.ByteArrayOutputStream

/**
 * 消息 msgpack 编解码（wire protocol §4——dict ↔ bytes; 与 Python 端互操作）。
 */
object Msg {

    fun encode(msg: Map<String, Any?>): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        try {
            packer.packMapHeader(msg.size)
            for ((k, v) in msg) {
                packer.packString(k)
                packValue(packer, v)
            }
            return packer.toByteArray()
        } finally {
            packer.close()
        }
    }

    private fun packValue(packer: org.msgpack.core.MessagePacker, v: Any?) {
        when (v) {
            null -> packer.packNil()
            is String -> packer.packString(v)
            is Boolean -> packer.packBoolean(v)
            is Int -> packer.packInt(v)
            is Long -> packer.packLong(v)
            is Float -> packer.packFloat(v)
            is Double -> packer.packDouble(v)
            is ByteArray -> packer.packBinaryHeader(v.size).writePayload(v)
            is Map<*, *> -> {
                packer.packMapHeader(v.size)
                for ((k2, v2) in v) {
                    packValue(packer, k2 as? String ?: k2.toString())
                    packValue(packer, v2)
                }
            }
            is List<*> -> {
                packer.packArrayHeader(v.size)
                for (item in v) packValue(packer, item)
            }
            else -> packer.packString(v.toString())
        }
    }

    fun decode(data: ByteArray): Map<String, Any?> {
        val unpacker = MessagePack.newDefaultUnpacker(data)
        try {
            val map = HashMap<String, Any?>()
            if (!unpacker.hasNext() ||
                unpacker.nextFormat.valueType != org.msgpack.value.ValueType.MAP) {
                throw IllegalArgumentException("消息必须为 map")
            }
            val n = unpacker.unpackMapHeader()
            for (i in 0 until n) {
                val key = unpacker.unpackString()
                map[key] = unpackValue(unpacker)
            }
            return map
        } finally {
            unpacker.close()
        }
    }

    private fun unpackValue(unpacker: MessageUnpacker): Any? {
        return when (unpacker.nextFormat.valueType) {
            org.msgpack.value.ValueType.NIL -> { unpacker.unpackNil(); null }
            org.msgpack.value.ValueType.BOOLEAN -> unpacker.unpackBoolean()
            org.msgpack.value.ValueType.INTEGER -> unpacker.unpackLong()
            org.msgpack.value.ValueType.FLOAT -> unpacker.unpackDouble()
            org.msgpack.value.ValueType.STRING -> unpacker.unpackString()
            org.msgpack.value.ValueType.BINARY -> {
                val len = unpacker.unpackBinaryHeader()
                val buf = ByteArray(len)
                unpacker.readPayload(buf)
                buf
            }
            org.msgpack.value.ValueType.ARRAY -> {
                val n = unpacker.unpackArrayHeader()
                (0 until n).map { unpackValue(unpacker) }.toMutableList()
            }
            org.msgpack.value.ValueType.MAP -> {
                val n = unpacker.unpackMapHeader()
                val m = HashMap<String, Any?>()
                for (i in 0 until n) {
                    val k = unpacker.unpackString()
                    m[k] = unpackValue(unpacker)
                }
                m
            }
            else -> { unpacker.skipValue(); null }
        }
    }
}
