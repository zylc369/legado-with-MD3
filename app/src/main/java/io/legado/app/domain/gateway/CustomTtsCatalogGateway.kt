package io.legado.app.domain.gateway

import com.opensecurity.customtts.model.CustomTtsVoiceEntry
import io.legado.app.domain.model.settings.CustomTtsSettings

/** 定制引擎/音色目录网关（UI 与服务经此访问 HttpTTS/ReadAloudVoice 数据与网关接口）。 */
interface CustomTtsCatalogGateway {

    /** 同步定制引擎记录（HttpTTS 固定 id）与音色预设（ReadAloudVoice per voice）。 */
    suspend fun sync(settings: CustomTtsSettings, port: Int): Result<Unit>

    /** 经本地网关拉取服务端音色目录。 */
    suspend fun fetchVoices(port: Int): Result<List<CustomTtsVoiceEntry>>

    /** 删除定制音色（被书绑定时禁用而非物理删除）。 */
    suspend fun removeVoice(voiceId: String): Result<Unit>

    /** 定制引擎记录是否存在。 */
    suspend fun engineExists(): Boolean
}
