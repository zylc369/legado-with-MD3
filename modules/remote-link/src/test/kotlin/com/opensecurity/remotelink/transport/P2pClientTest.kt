package com.opensecurity.remotelink.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pClientTest {

    private val psk = ByteArray(32) { 7 }

    /** 脚本化 fake 会话（注入 SessionFactory 使用）。 */
    private class FakeSession(
        private val onSend: (Map<String, Any?>) -> Map<String, Any?>? = { null },
    ) : P2pClient.Session {
        @Volatile var closed = false
        val sent = mutableListOf<Map<String, Any?>>()
        private val queue = ArrayDeque<Map<String, Any?>>()

        override val alive: Boolean get() = !closed

        fun push(msg: Map<String, Any?>) {
            queue.addLast(msg)
        }

        override fun send(msg: Map<String, Any?>) {
            sent += msg
            onSend(msg)?.let { queue.addLast(it) }
        }

        override fun recvNext(timeoutMs: Int): Map<String, Any?>? {
            // 模拟阻塞（短睡眠防忙循环）
            Thread.sleep(minOf(timeoutMs.toLong(), 20L))
            return queue.removeFirstOrNull()
        }

        override fun close() {
            closed = true
        }
    }

    private fun client(factory: P2pClient.SessionFactory) =
        P2pClient("127.0.0.1", 1, psk, "node", sessionFactory = factory)

    private fun okResponse(msg: Map<String, Any?>): Map<String, Any?> = mapOf(
        "t" to "api.resp", "id" to msg["id"], "status" to 200,
        "mime" to "audio/wav", "body" to byteArrayOf(1, 2),
    )

    @Test
    fun `请求成功解析状态与正文`() {
        val session = FakeSession { okResponse(it) }
        val c = client { session }
        val (status, mime, body) = c.request("POST", "/api/tts", mapOf("text" to "x"))
        assertEquals(200, status)
        assertEquals("audio/wav", mime)
        assertEquals(2, (body as ByteArray).size)
        assertEquals("api.req", session.sent[0]["t"])
        assertEquals("/api/tts", session.sent[0]["path"])
        assertEquals(mapOf("text" to "x"), session.sent[0]["body"])
    }

    @Test
    fun `乱序无关消息被忽略`() {
        val session = FakeSession()
        // 预置：迟到的无关消息（id=42）在前，正确响应（id=1——request 首发 id）在后
        session.push(mapOf("t" to "stale", "id" to 42))
        session.push(mapOf("t" to "api.resp", "id" to 1, "status" to 201, "mime" to "m", "body" to null))
        val c = client { session }
        val (status, _, _) = c.request("GET", "/x", null)
        assertEquals(201, status)
    }

    @Test
    fun `会话复用不重复创建`() {
        var created = 0
        val session = FakeSession { okResponse(it) }
        val c = client { created++; session }
        c.request("GET", "/a", null)
        c.request("GET", "/b", null)
        assertEquals(1, created)
    }

    @Test
    fun `会话失效后重建`() {
        var created = 0
        val sessions = mutableListOf<FakeSession>()
        val c = client {
            created++
            FakeSession { okResponse(it) }.also { sessions += it }
        }
        c.request("GET", "/a", null)
        sessions[0].close() // 模拟会话失效
        c.request("GET", "/b", null)
        assertEquals(2, created)
    }

    @Test
    fun `请求超时抛出带原因异常`() {
        val session = FakeSession() // 永不响应
        val c = client { session }
        val e = assertThrows(P2pClient.P2pException::class.java) {
            c.request("GET", "/x", null, timeoutMs = 150)
        }
        assertTrue(e.message!!.contains("响应超时"))
    }

    @Test
    fun `close 后请求拒绝且会话关闭`() {
        val session = FakeSession { okResponse(it) }
        val c = client { session }
        c.request("GET", "/x", null) // 先建立会话
        c.close()
        assertTrue(c.isClosed)
        assertTrue(session.closed) // 活动会话被关闭（防泄漏）
        assertThrows(P2pClient.P2pException::class.java) { c.request("GET", "/x", null) }
    }

    @Test
    fun `ping 成功`() {
        val session = FakeSession { msg -> mapOf("t" to "pong", "id" to msg["id"]) }
        assertTrue(client { session }.ping())
    }

    @Test
    fun `ping 异常返回 false 且丢弃会话`() {
        val session = FakeSession { throw RuntimeException("conn reset") }
        val c = client { session }
        assertEquals(false, c.ping())
        assertTrue(session.closed)
    }

    @Test
    fun `请求阶段输出可落盘的诊断日志`() {
        val logs = mutableListOf<String>()
        val session = FakeSession { okResponse(it) }
        val c = P2pClient(
            "127.0.0.1", 1, psk, "node",
            sessionFactory = { session },
            log = { logs += it },
        )
        c.request("POST", "/api/tts", mapOf("text" to "x"))
        assertTrue(logs.any { it.contains("请求完成") && it.contains("status=200") })
    }
}
