package io.legado.app.help.readaloud.playback

import io.legado.app.domain.model.readaloud.CloudTtsEngine
import io.legado.app.domain.model.readaloud.CloudTtsProviderType
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.TtsEngineDescriptor
import io.legado.app.domain.model.readaloud.TtsEngineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudEngineCatalogTest {

    private fun descriptor(id: String, name: String, kind: TtsEngineKind) = TtsEngineDescriptor(
        id = id,
        kind = kind,
        sourceId = id,
        displayName = name,
    )

    private fun cloud(id: String, name: String, enabled: Boolean = true) = CloudTtsEngine(
        id = id,
        name = name,
        provider = CloudTtsProviderType.OpenAiSpeech,
        enabled = enabled,
    )

    @Test
    fun `order is system http cloud with stable labels`() {
        val result = ReadAloudEngineCatalog.merge(
            system = listOf(descriptor("pkg", "引擎", TtsEngineKind.System)),
            http = listOf(descriptor("1", "接口", TtsEngineKind.Http)),
            cloud = listOf(cloud("c1", "云端")),
        )
        assertEquals(
            listOf(
                ReadAloudVoice.ENGINE_SYSTEM,
                ReadAloudVoice.ENGINE_HTTP,
                ReadAloudVoice.ENGINE_CLOUD,
            ),
            result.map { it.engineType },
        )
        assertEquals(listOf("系统: 引擎", "HTTP: 接口", "云: 云端"), result.map { it.label })
    }

    @Test
    fun `custom http engine and disabled cloud are excluded`() {
        val result = ReadAloudEngineCatalog.merge(
            system = emptyList(),
            http = listOf(
                descriptor("1", "接口", TtsEngineKind.Http),
                descriptor("99", "定制", TtsEngineKind.Http),
            ),
            cloud = listOf(cloud("c1", "禁用", enabled = false)),
            excludeHttpId = "99",
        )
        assertEquals(listOf("1"), result.map { it.engineId })
    }

    @Test
    fun `empty inputs produce empty choices`() {
        assertTrue(
            ReadAloudEngineCatalog.merge(emptyList(), emptyList(), emptyList()).isEmpty()
        )
    }
}
