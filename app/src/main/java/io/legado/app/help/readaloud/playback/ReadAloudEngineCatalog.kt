package io.legado.app.help.readaloud.playback

import io.legado.app.domain.model.readaloud.CloudTtsEngine
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.TtsEngineDescriptor

/**
 * 兜底引擎的统一合并顺序：系统 → HTTP → 云，定制引擎由调用方排除。
 */
data class ReadAloudEngineChoice(
    val engineType: String,
    val engineId: String,
    val label: String,
)

object ReadAloudEngineCatalog {

    fun merge(
        system: List<TtsEngineDescriptor>,
        http: List<TtsEngineDescriptor>,
        cloud: List<CloudTtsEngine>,
        excludeHttpId: String? = null,
    ): List<ReadAloudEngineChoice> = buildList {
        system.forEach { engine ->
            add(
                ReadAloudEngineChoice(
                    engineType = ReadAloudVoice.ENGINE_SYSTEM,
                    engineId = engine.sourceId,
                    label = "系统: ${engine.displayName}",
                )
            )
        }
        http.asSequence()
            .filter { it.sourceId != excludeHttpId }
            .forEach { engine ->
                add(
                    ReadAloudEngineChoice(
                        engineType = ReadAloudVoice.ENGINE_HTTP,
                        engineId = engine.sourceId,
                        label = "HTTP: ${engine.displayName}",
                    )
                )
            }
        cloud.asSequence()
            .filter { it.enabled }
            .forEach { engine ->
                add(
                    ReadAloudEngineChoice(
                        engineType = ReadAloudVoice.ENGINE_CLOUD,
                        engineId = engine.id,
                        label = "云: ${engine.name}",
                    )
                )
            }
    }
}
