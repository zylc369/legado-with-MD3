package io.legado.app.help.readaloud.playback

import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechRoleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudAudioCacheKeyTest {

    private val voice = ReadAloudVoice(
        id = "voice-1",
        engineType = ReadAloudVoice.ENGINE_CLOUD,
        engineId = "cloud-engine",
        speakerId = "speaker-1",
        displayName = "测试",
        revision = 7L,
    )

    @Test
    fun `system key contains voice identity and revision`() {
        val key = ReadAloudAudioCacheKey.system(voice)
        assertEquals("system:voice-1:7:cloud-engine:speaker-1", key)
        assertNotEquals(key, ReadAloudAudioCacheKey.system(voice.copy(revision = 8L)))
    }

    @Test
    fun `cloud key contains every synthesis dimension`() {
        val performance = CharacterPerformanceProfile(
            characterId = "char-1",
            updatedAt = 123L,
        )
        val key = ReadAloudAudioCacheKey.cloud(
            voice = voice,
            emotion = "sad",
            performance = performance,
            roleType = SpeechRoleType.Thought,
        )
        assertTrue(key.startsWith("cloud:voice-1:7:"))
        assertTrue(key.contains(CloudTtsEmotionMapper.VERSION))
        assertTrue(key.contains("sad"))
        assertTrue(key.contains(CharacterPerformanceInstructionBuilder.VERSION))
        assertTrue(key.contains("char-1"))
        assertTrue(key.contains("123"))
        assertTrue(key.contains(CloudTtsRoleInstructionMapper.VERSION))
        assertTrue(key.endsWith(SpeechRoleType.Thought.storageValue))
    }

    @Test
    fun `cloud key without performance still uses zero id`() {
        val key = ReadAloudAudioCacheKey.cloud(
            voice = voice,
            emotion = "",
            performance = null,
            roleType = SpeechRoleType.Narrator,
        )
        assertTrue(key.contains("::0:"))
    }

    @Test
    fun `custom key includes params`() {
        val key = ReadAloudAudioCacheKey.custom("spk", "{\"a\":1}")
        assertEquals("custom:spk:{\"a\":1}", key)
    }

    @Test
    fun `fallback key includes speed`() {
        val key = ReadAloudAudioCacheKey.fallback("system", "pkg", "zh", 1.5f)
        assertEquals("fallback:v2:system:pkg:zh:1.5", key)
    }

    @Test
    fun `fileName is stable and sensitive to content source chapter and rate`() {
        val base = ReadAloudAudioCacheKey.fileName("正文", "第一章", "source", 10)
        assertEquals(base, ReadAloudAudioCacheKey.fileName("正文", "第一章", "source", 10))
        assertNotEquals(base, ReadAloudAudioCacheKey.fileName("正文2", "第一章", "source", 10))
        assertNotEquals(base, ReadAloudAudioCacheKey.fileName("正文", "第二章", "source", 10))
        assertNotEquals(base, ReadAloudAudioCacheKey.fileName("正文", "第一章", "other", 10))
        assertNotEquals(base, ReadAloudAudioCacheKey.fileName("正文", "第一章", "source", 12))
        assertEquals(33, base.length)
    }

    @Test
    fun `fileName 与历史算法逐字节兼容`() {
        assertEquals(
            "bd746b8a64870780_0c507aaad7ff6b57",
            ReadAloudAudioCacheKey.fileName("正文", "第一章", "source", 10),
        )
    }
}
