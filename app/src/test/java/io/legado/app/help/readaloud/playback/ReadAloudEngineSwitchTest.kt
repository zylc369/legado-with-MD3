package io.legado.app.help.readaloud.playback

import io.legado.app.domain.model.readaloud.ReadAloudEngineSelection
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadAloudEngineSwitchTest {

    private val system = ReadAloudEngineSource(ReadAloudVoice.ENGINE_SYSTEM, "honor", "荣耀 AI")
    private val http = ReadAloudEngineSource(ReadAloudVoice.ENGINE_HTTP, "1", "定制朗读引擎")
    private val cloud = ReadAloudEngineSource(ReadAloudVoice.ENGINE_CLOUD, "mimo", "MiMo")
    private val sources = listOf(system, http, cloud)

    private fun selection(
        source: ReadAloudEngineSource,
        speakerId: String = "",
    ) = ReadAloudEngineSelection(
        engineType = source.engineType,
        engineId = source.engineId,
        speakerId = speakerId,
        displayName = source.name,
    )

    @Test
    fun `分组按系统HTTP云排列且空组不出现`() {
        val groups = ReadAloudEngineSwitch.buildGroups(
            sources = sources,
            bookSelection = null,
            globalSelection = null,
            scope = ReadAloudEngineScope.Book,
            voiceMemory = emptyMap(),
            voiceNames = emptyMap(),
        )
        assertEquals(
            listOf(
                ReadAloudEngineGroupType.System,
                ReadAloudEngineGroupType.Http,
                ReadAloudEngineGroupType.Cloud,
            ),
            groups.map { it.type },
        )

        val onlySystem = ReadAloudEngineSwitch.buildGroups(
            sources = listOf(system),
            bookSelection = null,
            globalSelection = null,
            scope = ReadAloudEngineScope.Book,
            voiceMemory = emptyMap(),
            voiceNames = emptyMap(),
        )
        assertEquals(listOf(ReadAloudEngineGroupType.System), onlySystem.map { it.type })
    }

    @Test
    fun `本书作用域下本书选择生效并标记为本书当前`() {
        val groups = ReadAloudEngineSwitch.buildGroups(
            sources = sources,
            bookSelection = selection(http, speakerId = "v1"),
            globalSelection = selection(system, speakerId = "zh"),
            scope = ReadAloudEngineScope.Book,
            voiceMemory = emptyMap(),
            voiceNames = emptyMap(),
        )
        val options = groups.flatMap { it.options }
        val httpOption = options.first { it.engineId == http.engineId }
        val systemOption = options.first { it.engineId == system.engineId }
        assertEquals(true, httpOption.selected)
        assertEquals(ReadAloudEngineTag.BookCurrent, httpOption.tag)
        assertEquals(false, systemOption.selected)
        // 全局默认指向的系统引擎仍可识别（不是当前生效）
        assertEquals(ReadAloudEngineTag.GlobalCurrent, systemOption.tag)
    }

    @Test
    fun `本书未设置时本书作用域继承全局默认`() {
        val groups = ReadAloudEngineSwitch.buildGroups(
            sources = sources,
            bookSelection = null,
            globalSelection = selection(system, speakerId = "zh"),
            scope = ReadAloudEngineScope.Book,
            voiceMemory = emptyMap(),
            voiceNames = emptyMap(),
        )
        val option = groups.flatMap { it.options }.first { it.engineId == system.engineId }
        assertEquals(true, option.selected)
        assertEquals(ReadAloudEngineTag.GlobalCurrent, option.tag)
    }

    @Test
    fun `全局作用域下本书覆盖的引擎仍标记本书当前`() {
        val groups = ReadAloudEngineSwitch.buildGroups(
            sources = sources,
            bookSelection = selection(http),
            globalSelection = selection(system),
            scope = ReadAloudEngineScope.Global,
            voiceMemory = emptyMap(),
            voiceNames = emptyMap(),
        )
        val options = groups.flatMap { it.options }
        assertEquals(
            ReadAloudEngineTag.GlobalCurrent,
            options.first { it.engineId == system.engineId }.tag,
        )
        assertEquals(
            ReadAloudEngineTag.BookCurrent,
            options.first { it.engineId == http.engineId }.tag,
        )
        // 全局作用域下选中标记跟全局走
        assertEquals(true, options.first { it.engineId == system.engineId }.selected)
        assertEquals(false, options.first { it.engineId == http.engineId }.selected)
    }

    @Test
    fun `音色沿用优先级为记忆大于选择大于默认`() {
        val voiceNames = mapOf(
            ReadAloudEngineSwitch.voiceKey(http.engineType, http.engineId, "v1") to "官方女声",
            ReadAloudEngineSwitch.voiceKey(system.engineType, system.engineId, "zh") to "zh-CN",
        )
        // 1) 无记忆、无选择 → 引擎默认（空）
        assertNull(
            ReadAloudEngineSwitch.voiceFor(
                http, null, null, emptyMap(), voiceNames,
            ).speakerId.takeIf { it.isNotBlank() },
        )
        // 2) 选择里有该引擎 → 用选择里的音色，并用音色名解析显示名
        val fromBook = ReadAloudEngineSwitch.voiceFor(
            http, selection(http, "v1"), null, emptyMap(), voiceNames,
        )
        assertEquals("v1", fromBook.speakerId)
        assertEquals("官方女声", fromBook.displayName)
        // 3) 记忆优先于选择
        val remembered = ReadAloudEngineVoiceRef("v2", "记忆女声")
        val fromMemory = ReadAloudEngineSwitch.voiceFor(
            http,
            selection(http, "v1"),
            null,
            mapOf(ReadAloudEngineSwitch.key(http.engineType, http.engineId) to remembered),
            voiceNames,
        )
        assertEquals(remembered, fromMemory)
        // 4) 无音色名映射时回退 speakerId
        assertEquals(
            "unknown",
            ReadAloudEngineSwitch.voiceFor(
                system, selection(system, "unknown"), null, emptyMap(), voiceNames,
            ).displayName,
        )
    }

    @Test
    fun `构建选择时保留引擎名作为显示名`() {
        val built = ReadAloudEngineSwitch.buildSelection(
            http, ReadAloudEngineVoiceRef("v1", "官方女声"),
        )
        assertEquals(http.engineType, built.engineType)
        assertEquals(http.engineId, built.engineId)
        assertEquals("v1", built.speakerId)
        assertEquals("定制朗读引擎", built.displayName)
    }

    @Test
    fun `当前生效选择在本书未设置时回落到全局`() {
        val global = selection(system)
        assertEquals(
            global,
            ReadAloudEngineSwitch.effectiveSelection(
                bookSelection = null,
                globalSelection = global,
                scope = ReadAloudEngineScope.Book,
            ),
        )
        assertEquals(
            global,
            ReadAloudEngineSwitch.effectiveSelection(
                bookSelection = selection(http),
                globalSelection = global,
                scope = ReadAloudEngineScope.Global,
            ),
        )
    }
}
