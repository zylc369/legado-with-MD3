package io.legado.app.ui.book.readaloud.engines

import io.legado.app.domain.gateway.CloudTtsEngineGateway
import io.legado.app.domain.gateway.HttpTtsEngineGateway
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.model.readaloud.BookVoiceBinding
import io.legado.app.domain.model.readaloud.CloudTtsEngine
import io.legado.app.domain.model.readaloud.ReadAloudEngineSelection
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.TtsEngineDescriptor
import io.legado.app.domain.model.readaloud.TtsEngineKind
import io.legado.app.domain.model.settings.ReadAloudSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReadAloudEnginesViewModelTest {

    private class FakeSettingsGateway : ReadAloudSettingsGateway {
        private val state = MutableStateFlow(ReadAloudSettings())
        override val currentSettings: ReadAloudSettings get() = state.value
        override val settings: StateFlow<ReadAloudSettings> = state.asStateFlow()
        override suspend fun update(transform: (ReadAloudSettings) -> ReadAloudSettings) {
            state.value = transform(state.value)
        }
    }

    private class FakeVoiceGateway : ReadAloudVoiceGateway {
        val voices = mutableListOf<ReadAloudVoice>()
        val bookSelections = mutableMapOf<String, String?>()

        override fun observeVoices(): Flow<List<ReadAloudVoice>> = flowOf(voices)
        override suspend fun getVoices(): List<ReadAloudVoice> = voices
        override suspend fun getEnabledVoices(): List<ReadAloudVoice> = voices
        override suspend fun getVoice(id: String): ReadAloudVoice? = voices.firstOrNull { it.id == id }
        override suspend fun upsertVoice(voice: ReadAloudVoice) = Unit
        override suspend fun deleteVoice(voice: ReadAloudVoice) = Unit

        override fun observeBindings(bookUrl: String): Flow<List<BookVoiceBinding>> = flowOf(emptyList())
        override suspend fun getBindings(bookUrl: String): List<BookVoiceBinding> = emptyList()
        override suspend fun getBinding(
            bookUrl: String,
            subjectType: String,
            subjectId: String,
        ): BookVoiceBinding? = null

        override suspend fun upsertBinding(binding: BookVoiceBinding) = Unit
        override suspend fun deleteBinding(binding: BookVoiceBinding) = Unit

        override suspend fun getBookTtsSelection(bookUrl: String): String? =
            bookSelections[bookUrl]

        override suspend fun setBookTtsSelection(bookUrl: String, value: String?) {
            bookSelections[bookUrl] = value
        }
    }

    private class FakeHttpTtsGateway(
        private val engines: List<TtsEngineDescriptor>,
    ) : HttpTtsEngineGateway {
        override fun observeAll(): Flow<List<TtsEngineDescriptor>> = flowOf(engines)
    }

    private class FakeCloudTtsGateway(
        private val engines: List<CloudTtsEngine>,
    ) : CloudTtsEngineGateway {
        override fun observeAll(): Flow<List<CloudTtsEngine>> = flowOf(engines)
        override suspend fun getAll(): List<CloudTtsEngine> = engines
        override suspend fun get(id: String): CloudTtsEngine? = engines.firstOrNull { it.id == id }
        override suspend fun upsert(engine: CloudTtsEngine) = Unit
        override suspend fun delete(engine: CloudTtsEngine) = Unit
    }

    private class RecordingRestarter : ReadAloudEngineRestarter {
        var calls = 0
        override suspend fun restartAtCurrentPosition() {
            calls++
        }
    }

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var settings: FakeSettingsGateway
    private lateinit var voices: FakeVoiceGateway
    private lateinit var restarter: RecordingRestarter

    private val systemEngine = TtsEngineDescriptor(
        id = "system:honor",
        kind = TtsEngineKind.System,
        sourceId = "honor",
        displayName = "荣耀 AI",
    )
    private val httpEngine = TtsEngineDescriptor(
        id = "http:1",
        kind = TtsEngineKind.Http,
        sourceId = "1",
        displayName = "定制朗读引擎",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        settings = FakeSettingsGateway()
        voices = FakeVoiceGateway()
        restarter = RecordingRestarter()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ReadAloudEnginesViewModel =
        ReadAloudEnginesViewModel(
            settingsGateway = settings,
            voiceGateway = voices,
            httpTtsGateway = FakeHttpTtsGateway(listOf(httpEngine)),
            cloudTtsGateway = FakeCloudTtsGateway(emptyList()),
            restarter = restarter,
            systemEngines = { listOf(systemEngine) },
            ioDispatcher = dispatcher,
        )

    private fun selection(engineType: String, engineId: String, speakerId: String = "") =
        ReadAloudEngineSelection.serialize(
            ReadAloudEngineSelection(
                engineType = engineType,
                engineId = engineId,
                speakerId = speakerId,
                displayName = "",
            )
        )

    @Test
    fun `本书未设置时入口显示继承的全局引擎`() = runTest(dispatcher) {
        settings.update { it.copy(ttsEngine = selection(ReadAloudVoice.ENGINE_SYSTEM, "honor", "zh")) }
        val vm = createViewModel()
        vm.bindBook("book1")
        advanceUntilIdle()
        assertEquals("荣耀 AI", vm.uiState.value.currentName)
        assertEquals("zh", vm.uiState.value.currentVoiceName)
        assertFalse(vm.uiState.value.bookOverride)
    }

    @Test
    fun `本书作用域切换只写本书并重启朗读`() = runTest(dispatcher) {
        val globalBefore = selection(ReadAloudVoice.ENGINE_SYSTEM, "honor", "zh")
        settings.update { it.copy(ttsEngine = globalBefore) }
        val vm = createViewModel()
        vm.bindBook("book1")
        advanceUntilIdle()

        vm.select(ReadAloudVoice.ENGINE_HTTP, "1")
        advanceUntilIdle()

        val written = ReadAloudEngineSelection.parse(voices.bookSelections["book1"])
        assertEquals(ReadAloudVoice.ENGINE_HTTP, written?.engineType)
        assertEquals("1", written?.engineId)
        assertEquals("定制朗读引擎", written?.displayName)
        // 全局默认不被改动
        assertEquals(globalBefore, settings.currentSettings.ttsEngine)
        assertEquals(1, restarter.calls)
        assertTrue(vm.uiState.value.bookOverride)
    }

    @Test
    fun `全局作用域切换写全局不清除本书选择且本书生效未变时不重启`() = runTest(dispatcher) {
        voices.bookSelections["book1"] = selection(ReadAloudVoice.ENGINE_HTTP, "1", "v1")
        val vm = createViewModel()
        vm.bindBook("book1")
        advanceUntilIdle()

        vm.setScope(io.legado.app.help.readaloud.playback.ReadAloudEngineScope.Global)
        vm.select(ReadAloudVoice.ENGINE_SYSTEM, "honor")
        advanceUntilIdle()

        val writtenGlobal = ReadAloudEngineSelection.parse(settings.currentSettings.ttsEngine)
        assertEquals(ReadAloudVoice.ENGINE_SYSTEM, writtenGlobal?.engineType)
        // 本书覆盖保持不变
        assertEquals(
            selection(ReadAloudVoice.ENGINE_HTTP, "1", "v1"),
            voices.bookSelections["book1"],
        )
        // 本书生效选择没变（仍是覆盖的定制引擎）：不需要重启朗读
        assertEquals(0, restarter.calls)
        // 入口行按本书生效选择展示，不随面板全局作用域谎报
        assertEquals("定制朗读引擎", vm.uiState.value.currentName)
    }

    @Test
    fun `本书无覆盖时全局切换改变生效选择才重启`() = runTest(dispatcher) {
        settings.update { it.copy(ttsEngine = selection(ReadAloudVoice.ENGINE_SYSTEM, "honor")) }
        val vm = createViewModel()
        vm.bindBook("book1")
        advanceUntilIdle()

        vm.setScope(io.legado.app.help.readaloud.playback.ReadAloudEngineScope.Global)
        vm.select(ReadAloudVoice.ENGINE_HTTP, "1")
        advanceUntilIdle()

        assertEquals(1, restarter.calls)
    }

    @Test
    fun `选择写入会取消在途目录加载避免旧快照覆盖`() = runTest {
        val main = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val io = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            settings.update { it.copy(ttsEngine = selection(ReadAloudVoice.ENGINE_SYSTEM, "honor")) }
            val vm = ReadAloudEnginesViewModel(
                settingsGateway = settings,
                voiceGateway = voices,
                httpTtsGateway = FakeHttpTtsGateway(listOf(httpEngine)),
                cloudTtsGateway = FakeCloudTtsGateway(emptyList()),
                restarter = restarter,
                systemEngines = { listOf(systemEngine) },
                ioDispatcher = io,
            )
            // 首次加载完成（sources 就绪、分组可点）
            vm.bindBook("book1")
            advanceUntilIdle()
            assertEquals("荣耀 AI", vm.uiState.value.currentName)

            // 第二次刷新推进到 IO 中途（读到的还是切换前的旧快照），
            // 此时用户点选引擎：旧实现里在途刷新会用旧值覆盖刚写入的选择
            vm.refresh()
            testScheduler.runCurrent()
            vm.select(ReadAloudVoice.ENGINE_HTTP, "1")
            advanceUntilIdle()

            // 刚写入的定制引擎选择不被旧快照（book1 无选择）覆盖
            assertEquals(
                ReadAloudVoice.ENGINE_HTTP,
                ReadAloudEngineSelection.parse(voices.bookSelections["book1"])?.engineType,
            )
            assertEquals("定制朗读引擎", vm.uiState.value.currentName)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `来回切换沿用各引擎上次的音色`() = runTest(dispatcher) {
        voices.voices += ReadAloudVoice(
            id = "http:1:v1",
            engineType = ReadAloudVoice.ENGINE_HTTP,
            engineId = "1",
            speakerId = "v1",
            displayName = "官方女声",
        )
        voices.bookSelections["book1"] = selection(ReadAloudVoice.ENGINE_HTTP, "1", "v1")
        val vm = createViewModel()
        vm.bindBook("book1")
        advanceUntilIdle()
        assertEquals("官方女声", vm.uiState.value.currentVoiceName)

        // 切到系统引擎
        vm.select(ReadAloudVoice.ENGINE_SYSTEM, "honor")
        advanceUntilIdle()
        // 切回定制引擎：应沿用 v1，而不是掉回引擎默认
        vm.select(ReadAloudVoice.ENGINE_HTTP, "1")
        advanceUntilIdle()

        val back = ReadAloudEngineSelection.parse(voices.bookSelections["book1"])
        assertEquals("v1", back?.speakerId)
    }
}
