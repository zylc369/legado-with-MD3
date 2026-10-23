package io.legado.app.help.readaloud.playback

import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.utils.MD5Utils

/**
 * 朗读音频缓存键与文件名的唯一入口。
 *
 * sourceKey 必须覆盖所有会影响合成结果的参数（引擎/音色/情绪/表演/语速），
 * 否则换参数后会命中旧音频。
 */
object ReadAloudAudioCacheKey {

    fun system(voice: ReadAloudVoice): String =
        "system:${voice.id}:${voice.revision}:${voice.engineId}:${voice.speakerId}"

    fun cloud(
        voice: ReadAloudVoice,
        emotion: String,
        performance: CharacterPerformanceProfile?,
        roleType: SpeechRoleType,
    ): String =
        "cloud:${voice.id}:${voice.revision}:" +
                "${CloudTtsEmotionMapper.VERSION}:$emotion:" +
                "${CharacterPerformanceInstructionBuilder.VERSION}:" +
                "${performance?.characterId.orEmpty()}:" +
                "${performance?.updatedAt ?: 0L}:" +
                "${CloudTtsRoleInstructionMapper.VERSION}:" +
                roleType.storageValue

    fun custom(speakerId: String, traitsJson: String): String =
        "custom:$speakerId:$traitsJson"

    fun fallback(
        engineType: String,
        engineId: String,
        speakerId: String,
        speedOverride: Float,
    ): String = "fallback:v2:$engineType:$engineId:$speakerId:$speedOverride"

    fun fileName(
        content: String,
        chapterTitle: String,
        sourceKey: String,
        speechRate: Int,
    ): String =
        MD5Utils.md5Encode16(chapterTitle) + "_" +
                MD5Utils.md5Encode16("$sourceKey-|-$speechRate-|-$content")
}
