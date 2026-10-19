package com.opensecurity.remotelink.gateway

import com.opensecurity.remotelink.transport.P2pClient
import com.opensecurity.remotelink.transport.TtsClient
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
class LocalTtsGateway(port: Int, private val client: TtsClient) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                "/tts" -> handleTts(session)
                "/voices" -> handleVoices()
                "/health" -> okJson(JSONObject().put("ok", client.ping()))
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain",
                "gateway error: ${e.message}")
        }
    }

    private fun handleTts(session: IHTTPSession): Response {
        val params = GatewayParams.parse(session.parameters)
            .getOrElse { return badRequest(it.message ?: "参数非法") }
        val (status, mime, body) = client.request(
            "POST", "/api/tts", GatewayParams.toMap(params))
        if (status != 200) return newFixedLengthResponse(
            Response.Status.lookup(status) ?: Response.Status.INTERNAL_ERROR,
            "text/plain", "remote tts failed: $status ${String(body as? ByteArray ?: ByteArray(0))}")
        val wav = body as? ByteArray
            ?: return badRequest("远程响应非音频（mime=$mime）")
        return newFixedLengthResponse(Response.Status.OK, "audio/wav", java.io.ByteArrayInputStream(wav), wav.size.toLong())
    }

    /** 服务端音色目录透传（远端 GET /api/tts/voices → JSON）。
     *  节点侧 JSON body 以结构化形态（map）回传，音频/字节类以 ByteArray 回传——两态兼容。 */
    private fun handleVoices(): Response {
        val (status, _, body) = client.request("GET", "/api/tts/voices", null)
        if (status != 200) return newFixedLengthResponse(
            Response.Status.lookup(status) ?: Response.Status.INTERNAL_ERROR,
            "text/plain", "remote voices failed: $status")
        val json: ByteArray = when (body) {
            is ByteArray -> body
            is Map<*, *> -> JSONObject(body).toString().toByteArray()
            else -> return badRequest("远程响应非 JSON")
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json",
            java.io.ByteArrayInputStream(json), json.size.toLong())
    }

    private fun badRequest(msg: String) =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", msg)

    private fun okJson(json: JSONObject) =
        newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
}

/** 网关 /tts 参数（与远端 /api/tts 请求体一一对应）。 */
data class GatewayTtsParams(
    val text: String,
    val speed: Double,
    val voice: String,
    val instruct: String,
    val seed: Int,
    val mode: String,
)

/** 网关参数解析/序列化（纯函数——单测友好）。 */
object GatewayParams {

    /** 解析 Legado 引擎请求的 query 参数（speed 缺省 1.0、voice/instruct/mode 缺省空、seed 缺省 -1；text 必填）。 */
    fun parse(parameters: Map<String, List<String>>): Result<GatewayTtsParams> = runCatching {
        val text = parameters["text"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: error("缺 text")
        GatewayTtsParams(
            text = text,
            speed = parameters["speed"]?.firstOrNull()?.toDoubleOrNull() ?: 1.0,
            voice = parameters["voice"]?.firstOrNull().orEmpty(),
            instruct = parameters["instruct"]?.firstOrNull().orEmpty(),
            seed = parameters["seed"]?.firstOrNull()?.toIntOrNull() ?: -1,
            mode = parameters["mode"]?.firstOrNull().orEmpty(),
        )
    }

    /** 构造远端 /api/tts 请求体（结构化 map——协议层 msgpack 编码，节点侧还原 dict）。 */
    fun toMap(p: GatewayTtsParams): Map<String, Any?> = mapOf(
        "text" to p.text,
        "voice" to p.voice,
        "speed" to p.speed,
        "instruct" to p.instruct,
        "seed" to p.seed,
        "mode" to p.mode,
    )
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

    fun configured(): Boolean = relayHost.isNotEmpty() && runCatching {
        android.util.Base64.decode(pskBase64, android.util.Base64.DEFAULT).size == 32
    }.getOrDefault(false)

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

    /** 最近一次启动失败原因（null=无错误）——配置页展示用。 */
    @Volatile var lastError: String? = null
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
        // App 启动路径（App.onCreate）不得崩溃：启动失败保持停止态并记录原因
        try {
            val psk = android.util.Base64.decode(store.pskBase64, android.util.Base64.DEFAULT)
            val client = P2pClient(store.relayHost, store.relayPort, psk, store.nodeId)
            val gw = LocalTtsGateway(store.localPort, client)
            gw.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            gateway = gw
            clientRef = client
            currentPort = store.localPort
            lastError = null
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
        }
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
        clientRef?.close(); clientRef = null
        currentPort = 0
    }
}
