package io.legado.app.help.readaloud.playback

import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.TtsNativeVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudVoiceCatalogTest {

    private fun native(id: String, name: String = id, locale: String = "zh-CN") =
        TtsNativeVoice(id = id, engineId = "pkg", displayName = name, locale = locale)

    private fun preset(
        speakerId: String,
        name: String = "",
        engineType: String = ReadAloudVoice.ENGINE_SYSTEM,
        engineId: String = "pkg",
    ) = ReadAloudVoice(
        id = "preset-$speakerId",
        engineType = engineType,
        engineId = engineId,
        speakerId = speakerId,
        displayName = name,
    )

    @Test
    fun `native voices keep order and fallback labels`() {
        val result = ReadAloudVoiceCatalog.merge(
            engineType = ReadAloudVoice.ENGINE_SYSTEM,
            engineId = "pkg",
            native = listOf(native("v1", "音色1"), native("v2", "")),
            presets = emptyList(),
        )
        assertEquals(listOf("v1", "v2"), result.map { it.speakerId })
        assertEquals("音色1", result[0].label)
        assertEquals("v2", result[1].label)
        assertEquals("zh-CN", result[0].description)
        assertNull(result[0].presetId)
        assertFalse(result[0].userManaged)
    }

    @Test
    fun `preset overrides native label but keeps position and description`() {
        val result = ReadAloudVoiceCatalog.merge(
            engineType = ReadAloudVoice.ENGINE_SYSTEM,
            engineId = "pkg",
            native = listOf(native("v1", "原生1"), native("v2", "原生2")),
            presets = listOf(preset("v1", "自定义1")),
        )
        assertEquals(listOf("v1", "v2"), result.map { it.speakerId })
        assertEquals("自定义1", result[0].label)
        assertEquals("zh-CN", result[0].description)
        assertEquals("preset-v1", result[0].presetId)
        assertTrue(result[0].userManaged)
        assertEquals("原生2", result[1].label)
    }

    @Test
    fun `new preset appends after native voices`() {
        val result = ReadAloudVoiceCatalog.merge(
            engineType = ReadAloudVoice.ENGINE_SYSTEM,
            engineId = "pkg",
            native = listOf(native("v1")),
            presets = listOf(preset("v9", "新增")),
        )
        assertEquals(listOf("v1", "v9"), result.map { it.speakerId })
        assertEquals("新增", result[1].label)
        assertEquals("", result[1].description)
    }

    @Test
    fun `preset from another engine is ignored`() {
        val result = ReadAloudVoiceCatalog.merge(
            engineType = ReadAloudVoice.ENGINE_SYSTEM,
            engineId = "pkg",
            native = emptyList(),
            presets = listOf(
                preset("v1", "本引擎"),
                preset("v2", "别的引擎", engineId = "other"),
            ),
        )
        assertEquals(listOf("v1"), result.map { it.speakerId })
    }

    @Test
    fun `blank preset label falls back to native then default then speaker id`() {
        val withNative = ReadAloudVoiceCatalog.merge(
            engineType = ReadAloudVoice.ENGINE_SYSTEM,
            engineId = "pkg",
            native = listOf(native("v1", "原生")),
            presets = listOf(preset("v1")),
        )
        assertEquals("原生", withNative.single().label)

        val withDefault = ReadAloudVoiceCatalog.merge(
            engineType = ReadAloudVoice.ENGINE_HTTP,
            engineId = "1",
            native = emptyList(),
            presets = listOf(preset("v1", engineType = ReadAloudVoice.ENGINE_HTTP, engineId = "1")),
            defaultLabel = "默认音色",
        )
        assertEquals("默认音色", withDefault.single().label)

        val fallbackId = ReadAloudVoiceCatalog.merge(
            engineType = ReadAloudVoice.ENGINE_HTTP,
            engineId = "1",
            native = emptyList(),
            presets = listOf(preset("v1", engineType = ReadAloudVoice.ENGINE_HTTP, engineId = "1")),
        )
        assertEquals("v1", fallbackId.single().label)
    }

    @Test
    fun `duplicate native ids are merged by id`() {
        val result = ReadAloudVoiceCatalog.merge(
            engineType = ReadAloudVoice.ENGINE_SYSTEM,
            engineId = "pkg",
            native = listOf(native("v1", "一"), native("v1", "二")),
            presets = emptyList(),
        )
        assertEquals(1, result.size)
        assertEquals("二", result.single().label)
    }

    @Test
    fun `配置同步的预设不算用户可编辑`() {
        val result = ReadAloudVoiceCatalog.merge(
            engineType = ReadAloudVoice.ENGINE_HTTP,
            engineId = "1",
            native = emptyList(),
            presets = listOf(
                preset(
                    "v1",
                    "配置音色",
                    engineType = ReadAloudVoice.ENGINE_HTTP,
                    engineId = "1",
                ).copy(managedBy = ReadAloudVoice.MANAGED_BY_CONFIGURED_TTS)
            ),
        )
        assertFalse(result.single().userManaged)
        assertEquals(ReadAloudVoice.MANAGED_BY_CONFIGURED_TTS, result.single().managedBy)
    }

    @Test
    fun `同一说话人的重复预设以后一个为准`() {
        val result = ReadAloudVoiceCatalog.merge(
            engineType = ReadAloudVoice.ENGINE_SYSTEM,
            engineId = "pkg",
            native = emptyList(),
            presets = listOf(preset("v1", "旧"), preset("v1", "新")),
        )
        assertEquals(1, result.size)
        assertEquals("新", result.single().label)
    }
}
