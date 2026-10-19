package io.legado.app.data.repository

import com.google.gson.Gson
import com.opensecurity.customtts.model.CustomTtsCatalog
import com.opensecurity.customtts.model.CustomTtsVoiceEntry
import com.opensecurity.customtts.model.CustomTtsVoiceCatalogParser
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.domain.gateway.CustomTtsCatalogGateway
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.settings.CustomTtsSettings
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 定制引擎/音色目录实现:
 * - 引擎记录: HttpTTS 固定 id（[CustomTtsCatalog.ENGINE_ID]）
 * - 音色预设: engineType=HTTP + engineId=固定 id + speakerId=服务端 voice_id，
 *   traitsJson = CustomTtsVoiceParams（合成参数）
 * - 目录拉取: 本地网关 GET /voices（P2P 透传到服务端）
 */
class CustomTtsCatalogRepository(
    private val voiceGateway: ReadAloudVoiceGateway,
) : CustomTtsCatalogGateway {

    private val gson = Gson()

    override suspend fun sync(settings: CustomTtsSettings, port: Int): Result<Unit> = runCatching {
        require(port > 0) { "网关未运行" }
        val now = System.currentTimeMillis()
        val engineId = CustomTtsCatalog.ENGINE_ID.toString()

        // 1) 引擎记录（固定 id，幂等 upsert）
        val existingEngine = appDb.httpTTSDao.get(CustomTtsCatalog.ENGINE_ID)
        val engine = (existingEngine ?: HttpTTS(id = CustomTtsCatalog.ENGINE_ID)).copy(
            name = CustomTtsCatalog.ENGINE_NAME,
            url = CustomTtsCatalog.url(port),
            contentType = CustomTtsCatalog.CONTENT_TYPE,
            speed = DEFAULT_SOURCE_SPEED,
            lastUpdateTime = now,
        )
        appDb.httpTTSDao.insert(engine)

        // 2) 音色预设（voice 级 ReadAloudVoice）
        val existingVoices = voiceGateway.getVoices()
            .filter { it.engineType == ReadAloudVoice.ENGINE_HTTP && it.engineId == engineId }
        val incomingIds = settings.voices.keys.associateWith { voiceId ->
            SpeechIdentity.voiceId(ReadAloudVoice.ENGINE_HTTP, engineId, voiceId)
        }
        settings.voices.forEach { (voiceId, params) ->
            val id = incomingIds.getValue(voiceId)
            val old = existingVoices.firstOrNull { it.id == id }
            val traits = gson.toJson(params)
            voiceGateway.upsertVoice(
                ReadAloudVoice(
                    id = id,
                    engineType = ReadAloudVoice.ENGINE_HTTP,
                    engineId = engineId,
                    speakerId = voiceId,
                    displayName = params.displayName.ifEmpty { voiceId },
                    traitsJson = traits,
                    managedBy = ReadAloudVoice.MANAGED_BY_CONFIGURED_TTS,
                    enabled = old?.enabled ?: true,
                    available = true,
                    revision = if (old?.traitsJson == traits) old.revision else now,
                    createdAt = old?.createdAt ?: now,
                    updatedAt = now,
                )
            )
        }

        // 3) 移除已删除的音色（被书绑定则禁用）
        existingVoices.filter { it.id !in incomingIds.values }.forEach { removeVoiceRecord(it) }
    }

    override suspend fun removeVoice(voiceId: String): Result<Unit> = runCatching {
        val id = SpeechIdentity.voiceId(
            ReadAloudVoice.ENGINE_HTTP, CustomTtsCatalog.ENGINE_ID.toString(), voiceId)
        val voice = voiceGateway.getVoice(id) ?: return@runCatching
        removeVoiceRecord(voice)
    }

    override suspend fun engineExists(): Boolean =
        appDb.httpTTSDao.get(CustomTtsCatalog.ENGINE_ID) != null

    override suspend fun fetchVoices(port: Int): Result<List<CustomTtsVoiceEntry>> = runCatching {
        require(port > 0) { "网关未运行" }
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/voices")
            .get()
            .build()
        val json = withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string() ?: error("空响应")
            }
        }
        CustomTtsVoiceCatalogParser.parse(json)
    }

    private suspend fun removeVoiceRecord(voice: ReadAloudVoice) {
        val bound = appDb.readAloudVoiceDao.countBindingsForVoice(voice.id) > 0
        if (bound) {
            voiceGateway.upsertVoice(voice.copy(enabled = false))
        } else {
            voiceGateway.deleteVoice(voice)
        }
    }

    companion object {
        /** Legado 源级语速刻度默认值（1.0x——实际速度由 voice traits 注入）。 */
        private const val DEFAULT_SOURCE_SPEED = 5
    }
}
