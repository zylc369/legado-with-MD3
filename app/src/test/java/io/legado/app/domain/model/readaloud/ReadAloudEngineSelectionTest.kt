package io.legado.app.domain.model.readaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadAloudEngineSelectionTest {

    @Test
    fun `canonical selection round-trips engine speaker and display name`() {
        val selection = ReadAloudEngineSelection(
            engineType = ReadAloudVoice.ENGINE_SYSTEM,
            engineId = "com.example.tts",
            speakerId = "com.example.tts:voice-1",
            displayName = "Example Engine",
        )

        val parsed = ReadAloudEngineSelection.parse(
            ReadAloudEngineSelection.serialize(selection)
        )

        assertEquals(selection, parsed)
    }

    @Test
    fun `legacy system SelectItem is read as a system engine without speaker`() {
        val parsed = ReadAloudEngineSelection.parse(
            """{"title":"荣耀 AI 语音引擎","value":"com.hihonor.tts"}"""
        )

        assertEquals(ReadAloudVoice.ENGINE_SYSTEM, parsed?.engineType)
        assertEquals("com.hihonor.tts", parsed?.engineId)
        assertEquals("", parsed?.speakerId)
    }

    @Test
    fun `legacy numeric http id is read as an http engine`() {
        val parsed = ReadAloudEngineSelection.parse("42")

        assertEquals(ReadAloudVoice.ENGINE_HTTP, parsed?.engineType)
        assertEquals("42", parsed?.engineId)
        assertEquals("", parsed?.speakerId)
    }

    @Test
    fun `blank or null selection means default system engine`() {
        assertNull(ReadAloudEngineSelection.parse(null))
        assertNull(ReadAloudEngineSelection.parse(""))
        assertNull(ReadAloudEngineSelection.parse("   "))
    }
}
