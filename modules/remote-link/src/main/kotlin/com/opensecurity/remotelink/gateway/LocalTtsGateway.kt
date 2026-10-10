package com.opensecurity.remotelink.gateway

import com.opensecurity.remotelink.transport.P2pClient
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import android.content.Context
import android.content.SharedPreferences

/**
 * 本地 HTTP 网关: Legado HttpTTS 引擎 → 127.0.0.1:port/tts → P2P → MacMini。
 *
 * Legado 引擎配置（零侵入对接）:
 *   URL: http://127.0.0.1:<port>/tts?text={{speakText}}&speed={{speakSpeed}}
 *   contentType: audio/wav; concurrentTasks=1
 */
class LocalTtsGateway(port: Int, private val client: P2pClient) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                "/tts" -> handleTts(session)
                "/health" -> okJson(JSONObject().put("ok", client.ping()))
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain",
                "gateway error: ${e.message}")
        }
    }

    private fun handleTts(session: IHTTPSession): Response {
        val params = session.parameters
        val text = params["text"]?.firstOrNull() ?: return badRequest("缺 text")
        val speed = params["speed"]?.firstOrNull()?.toDoubleOrNull() ?: 1.0
        val voice = params["voice"]?.firstOrNull() ?: "zf_xiaobei"
        val payload = JSONObject().put("text", text).put("voice", voice).put("speed", speed)
        val (status, mime, body) = client.request(
            "POST", "/api/tts", payload.toString().toByteArray())
        if (status != 200) return newFixedLengthResponse(
            Response.Status.lookup(status) ?: Response.Status.INTERNAL_ERROR,
            "text/plain", "remote tts failed: $status ${String(body as? ByteArray ?: ByteArray(0))}")
        val wav = body as? ByteArray
            ?: return badRequest("远程响应非音频（mime=$mime）")
        return newFixedLengthResponse(Response.Status.OK, "audio/wav", java.io.ByteArrayInputStream(wav), wav.size.toLong())
    }

    private fun badRequest(msg: String) =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", msg)

    private fun okJson(json: JSONObject) =
        newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
}

/** 配置存储（SharedPreferences 收口）。 */
class ConfigStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("remote_link", Context.MODE_PRIVATE)

    var relayHost: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(v) = prefs.edit().putString(KEY_HOST, v).apply()
    var relayPort: Int
        get() = prefs.getInt(KEY_PORT, 41000)
        set(v) = prefs.edit().putInt(KEY_PORT, v).apply()
    var pskBase64: String
        get() = prefs.getString(KEY_PSK, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PSK, v).apply()
    var nodeId: String
        get() = prefs.getString(KEY_NODE, "macmini") ?: "macmini"
        set(v) = prefs.edit().putString(KEY_NODE, v).apply()
    var localPort: Int
        get() = prefs.getInt(KEY_LOCAL_PORT, 18888)
        set(v) = prefs.edit().putInt(KEY_LOCAL_PORT, v).apply()
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    fun configured(): Boolean = relayHost.isNotEmpty() &&
        android.util.Base64.decode(pskBase64, android.util.Base64.DEFAULT).size == 32

    companion object {
        private const val KEY_HOST = "relay_host"
        private const val KEY_PORT = "relay_port"
        private const val KEY_PSK = "psk_b64"
        private const val KEY_NODE = "node_id"
        private const val KEY_LOCAL_PORT = "local_port"
        private const val KEY_ENABLED = "enabled"
    }
}

/**
 * RemoteLink 生命周期管理（App 启动 init; 未配置=零开销不监听）。
 */
object RemoteLinkManager {
    @Volatile private var gateway: LocalTtsGateway? = null
    @Volatile private var clientRef: P2pClient? = null
    @Volatile var currentPort: Int = 0
        private set

    /** App 启动调用; enabled 且配置完整时启动本地网关（幂等）。 */
    fun init(context: Context) {
        val store = ConfigStore(context)
        if (!store.enabled || !store.configured()) return
        startInternal(store)
    }

    @Synchronized
    private fun startInternal(store: ConfigStore) {
        if (gateway != null) return
        val psk = android.util.Base64.decode(store.pskBase64, android.util.Base64.DEFAULT)
        val client = P2pClient(store.relayHost, store.relayPort, psk, store.nodeId)
        val gw = LocalTtsGateway(store.localPort, client)
        gw.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        gateway = gw
        clientRef = client
        currentPort = store.localPort
    }

    /** 配置变更后调用（重启网关）。 */
    @Synchronized
    fun restart(context: Context) {
        stop()
        init(context)
    }

    @Synchronized
    fun stop() {
        gateway?.stop(); gateway = null
        clientRef = null
        currentPort = 0
    }
}
