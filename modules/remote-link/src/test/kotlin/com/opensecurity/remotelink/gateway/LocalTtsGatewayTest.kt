package com.opensecurity.remotelink.gateway

import com.opensecurity.remotelink.transport.TtsClient
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class LocalTtsGatewayTest {

    private class FakeTtsClient : TtsClient {
        var response: Triple<Int, String, Any?> = Triple(200, "audio/wav", ByteArray(0))
        var pingResult = true
        var closed = false
        var lastMethod = ""
        var lastPath = ""
        var lastBody: Map<String, Any?>? = null

        override fun request(
            method: String, path: String, body: Map<String, Any?>?, token: String, timeoutMs: Int,
        ): Triple<Int, String, Any?> {
            lastMethod = method
            lastPath = path
            lastBody = body
            return response
        }

        override fun ping(): Boolean = pingResult

        override fun close() {
            closed = true
        }
    }

    private lateinit var client: FakeTtsClient
    private lateinit var gateway: LocalTtsGateway
    private var port = 0

    @Before
    fun setUp() {
        client = FakeTtsClient()
        gateway = LocalTtsGateway(0, client)
        gateway.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        port = gateway.listeningPort
    }

    @After
    fun tearDown() {
        gateway.stop()
    }

    private fun get(path: String): Triple<Int, String, String> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        return try {
            val code = conn.responseCode
            val bytes = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.readBytes() ?: ByteArray(0)
            Triple(code, conn.contentType ?: "", bytes.decodeToString())
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    @Test
    fun `参数五字段透传`() {
        client.response = Triple(200, "audio/wav", byteArrayOf(1, 2, 3))
        val (code, ct, _) = get(
            "/tts?text=${enc("你好")}&speed=1.3&voice=spk1&instruct=${enc("轻声细语")}&seed=42&mode=instruct"
        )
        assertEquals(200, code)
        assertTrue(ct.startsWith("audio/wav"))
        assertEquals("POST", client.lastMethod)
        assertEquals("/api/tts", client.lastPath)
        val body = client.lastBody!!
        assertEquals("你好", body["text"])
        assertEquals(1.3, (body["speed"] as Number).toDouble(), 1e-9)
        assertEquals("spk1", body["voice"])
        assertEquals("轻声细语", body["instruct"])
        assertEquals(42, body["seed"])
        assertEquals("instruct", body["mode"])
    }

    @Test
    fun `缺省参数使用默认值`() {
        client.response = Triple(200, "audio/wav", byteArrayOf(9))
        val (code, _, _) = get("/tts?text=abc")
        assertEquals(200, code)
        val body = client.lastBody!!
        assertEquals(1.0, (body["speed"] as Number).toDouble(), 1e-9)
        assertEquals("", body["voice"])
        assertEquals("", body["instruct"])
        assertEquals(-1, body["seed"])
        assertEquals("", body["mode"])
    }

    @Test
    fun `缺 text 返回 400`() {
        val (code, _, body) = get("/tts?speed=1.0")
        assertEquals(400, code)
        assertTrue(body.contains("text"))
    }

    @Test
    fun `远端错误码透传`() {
        client.response = Triple(503, "text/plain", "busy".toByteArray())
        val (code, _, body) = get("/tts?text=abc")
        assertEquals(503, code)
        assertTrue(body.contains("remote tts failed"))
    }

    @Test
    fun `非音频响应返回 400`() {
        client.response = Triple(200, "application/json", null)
        val (code, _, body) = get("/tts?text=abc")
        assertEquals(400, code)
        assertTrue(body.contains("非音频"))
    }

    @Test
    fun `voices 字节形态透传`() {
        client.response = Triple(200, "application/json", "{\"voices\":[]}".toByteArray())
        val (code, ct, body) = get("/voices")
        assertEquals(200, code)
        assertTrue(ct.startsWith("application/json"))
        assertEquals("{\"voices\":[]}", body)
        assertEquals("GET", client.lastMethod)
        assertEquals("/api/tts/voices", client.lastPath)
    }

    @Test
    fun `voices map 形态转 JSON`() {
        // 节点侧 JSON body 以结构化 map 回传
        client.response = Triple(200, "application/json", mapOf("voices" to emptyList<Any>()))
        val (code, _, body) = get("/voices")
        assertEquals(200, code)
        assertEquals("{\"voices\":[]}", body)
    }

    @Test
    fun `health 依据 ping 结果`() {
        client.pingResult = true
        assertEquals(200, get("/health").first)
        assertTrue(get("/health").third.contains("\"ok\":true"))
        client.pingResult = false
        assertTrue(get("/health").third.contains("\"ok\":false"))
    }

    @Test
    fun `未知路径 404`() {
        assertEquals(404, get("/nope").first)
    }
}
