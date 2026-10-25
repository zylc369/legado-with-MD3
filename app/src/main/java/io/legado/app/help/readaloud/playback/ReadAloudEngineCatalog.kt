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

    /**
     * 三源合并为引擎目录（系统 → HTTP → 云）。
     *
     * @param excludeHttpId 排除某个 HTTP 引擎（如定制引擎不作为兜底候选）
     * @param prefixedLabel label 是否带“系统:/HTTP:/云:”类型前缀；
     *   快捷切换面板自带分组标题，用不带前缀的纯名称
     */
    fun merge(
        system: List<TtsEngineDescriptor>,
        http: List<TtsEngineDescriptor>,
        cloud: List<CloudTtsEngine>,
        excludeHttpId: String? = null,
        prefixedLabel: Boolean = true,
    ): List<ReadAloudEngineChoice> = buildList {
        fun label(prefix: String, name: String) =
            if (prefixedLabel) "$prefix: $name" else name

        system.forEach { engine ->
            add(
                ReadAloudEngineChoice(
                    engineType = ReadAloudVoice.ENGINE_SYSTEM,
                    engineId = engine.sourceId,
                    label = label("系统", engine.displayName),
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
                        label = label("HTTP", engine.displayName),
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
                        label = label("云", engine.name),
                    )
                )
            }
    }
}
