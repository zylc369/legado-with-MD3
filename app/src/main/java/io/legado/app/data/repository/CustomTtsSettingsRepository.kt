package io.legado.app.data.repository

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opensecurity.customtts.model.CustomTtsVoiceParams
import com.opensecurity.customtts.model.HybridTtsConfig
import io.legado.app.domain.gateway.CustomTtsSettingsGateway
import io.legado.app.domain.model.settings.CustomTtsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * 定制 TTS 设置存储（独立 JSON 文件——不进入 DataStore/SharedPreferences，
 * 避免与旧偏好基线耦合；文件损坏/字段缺失时逐字段回退默认值）。
 */
class CustomTtsSettingsRepository : CustomTtsSettingsGateway {

    private val gson = Gson()
    private val file: File get() = File(appCtx.filesDir, FILE_NAME)
    private val lock = Any()
    private val _settings = MutableStateFlow(load())

    override val currentSettings: CustomTtsSettings get() = _settings.value
    override val settings: StateFlow<CustomTtsSettings> = _settings.asStateFlow()

    override suspend fun update(transform: (CustomTtsSettings) -> CustomTtsSettings) {
        val next = synchronized(lock) { transform(_settings.value) }
        // 磁盘写必须离开调用方线程（VM 侧为主线程）；失败时抛出由调用方提示，
        // 不更新内存状态（否则用户会误以为已保存）
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(next))
        }
        _settings.value = next
    }

    private fun load(): CustomTtsSettings {
        val f = file
        if (!f.exists()) return CustomTtsSettings()
        return runCatching { parse(f.readText()) }.getOrDefault(CustomTtsSettings())
    }

    private fun parse(json: String): CustomTtsSettings {
        val root = JsonParser.parseString(json).asJsonObject

        val voices = HashMap<String, CustomTtsVoiceParams>()
        root.getAsJsonObject("voices")?.entrySet()?.forEach { (key, value) ->
            val o = value.asJsonObject
            voices[key] = CustomTtsVoiceParams(
                voiceId = o.string("voiceId").ifEmpty { key },
                displayName = o.string("displayName"),
                speed = o.float("speed", 1.0f),
                instruct = o.string("instruct"),
                seed = o.int("seed", -1),
                mode = o.string("mode"),
            )
        }

        val h = root.getAsJsonObject("hybrid")
        val hybrid = HybridTtsConfig(
            firstSegmentFallback = h?.bool("firstSegmentFallback", true) ?: true,
            remoteTimeoutMs = h?.int("remoteTimeoutMs", 10_000) ?: 10_000,
            fallbackEngineType = h?.string("fallbackEngineType").orEmpty()
                .ifEmpty { "system" },
            fallbackEngineId = h?.string("fallbackEngineId").orEmpty(),
            fallbackSpeakerId = h?.string("fallbackSpeakerId").orEmpty(),
            fallbackSpeed = h?.float("fallbackSpeed", 1.0f) ?: 1.0f,
            customSpeed = h?.float("customSpeed", 1.0f) ?: 1.0f,
            localSpeed = h?.bool("localSpeed", true) ?: true,
        )

        val stairs = root.getAsJsonArray("segmentStairs")
            ?.mapNotNull { runCatching { it.asInt }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(20, 40, 60, 80)

        return CustomTtsSettings(
            defaultVoiceId = root.string("defaultVoiceId"),
            voices = voices,
            hybrid = hybrid,
            segmentTarget = root.int("segmentTarget", 80).coerceIn(1, 1000),
            segmentStairs = stairs,
        )
    }

    private fun JsonObject.string(key: String): String =
        runCatching { get(key)?.takeIf { !it.isJsonNull }?.asString }.getOrNull() ?: ""

    private fun JsonObject.int(key: String, def: Int): Int =
        runCatching { get(key)?.takeIf { !it.isJsonNull }?.asInt }.getOrNull() ?: def

    private fun JsonObject.float(key: String, def: Float): Float =
        runCatching { get(key)?.takeIf { !it.isJsonNull }?.asFloat }.getOrNull() ?: def

    private fun JsonObject.bool(key: String, def: Boolean): Boolean =
        runCatching { get(key)?.takeIf { !it.isJsonNull }?.asBoolean }.getOrNull() ?: def

    companion object {
        const val FILE_NAME = "custom_tts_settings.json"
    }
}
