package io.legado.app.help.readaloud.playback

import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.TtsNativeVoice

/**
 * 原生音色与用户预设的统一合并规则。
 *
 * 顺序：原生音色在前，预设原位覆盖同名项，新预设追加；
 * 预设可覆盖原生音色的显示名，但不能改变其原始顺序。
 */
data class ReadAloudVoiceChoice(
    val speakerId: String,
    val label: String,
    val description: String,
    val presetId: String?,
    val managedBy: String,
) {
    val userManaged: Boolean
        get() = managedBy == ReadAloudVoice.MANAGED_BY_USER
}

object ReadAloudVoiceCatalog {

    fun merge(
        engineType: String,
        engineId: String,
        native: List<TtsNativeVoice>,
        presets: List<ReadAloudVoice>,
        defaultLabel: String = "",
    ): List<ReadAloudVoiceChoice> {
        val options = LinkedHashMap<String, ReadAloudVoiceChoice>()
        native.forEach { voice ->
            options[voice.id] = ReadAloudVoiceChoice(
                speakerId = voice.id,
                label = voice.displayName.ifBlank { voice.id },
                description = listOf(voice.locale, voice.gender)
                    .filter(String::isNotBlank)
                    .joinToString(" · "),
                presetId = null,
                managedBy = "",
            )
        }
        presets.asSequence()
            .filter { it.engineType == engineType && it.engineId == engineId }
            .forEach { preset ->
                val existing = options[preset.speakerId]
                options[preset.speakerId] = ReadAloudVoiceChoice(
                    speakerId = preset.speakerId,
                    label = preset.displayName.takeIf(String::isNotBlank)
                        ?: existing?.label
                        ?: defaultLabel.takeIf(String::isNotBlank)
                        ?: preset.speakerId,
                    description = existing?.description.orEmpty(),
                    presetId = preset.id,
                    managedBy = preset.managedBy,
                )
            }
        return options.values.toList()
    }
}
