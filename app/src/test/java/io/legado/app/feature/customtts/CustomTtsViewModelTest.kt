package io.legado.app.feature.customtts

import android.os.Looper
import io.legado.app.domain.gateway.CloudTtsEngineGateway
import io.legado.app.domain.gateway.CustomTtsCatalogGateway
import io.legado.app.domain.gateway.CustomTtsSettingsGateway
import io.legado.app.domain.gateway.HttpTtsEngineGateway
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.model.readaloud.BookVoiceBinding
import io.legado.app.domain.model.readaloud.CloudTtsEngine
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.TtsEngineDescriptor
import io.legado.app.domain.model.settings.CustomTtsSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class CustomTtsViewModelTest {

    private val app = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }
    private val collectScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private class FakeSettingsGateway : CustomTtsSettingsGateway {
        private val state = MutableStateFlow(CustomTtsSettings())
        override val currentSettings: CustomTtsSettings get() = state.value
        override val settings: StateFlow<CustomTtsSettings> = state.asStateFlow()
        override suspend fun update(transform: (CustomTtsSettings) -> CustomTtsSettings) {
            state.value = transform(state.value)
        }

        fun emit(value: CustomTtsSettings) {
            state.value = value
        }
    }

    private class FakeCatalogGateway : CustomTtsCatalogGateway {
        override suspend fun sync(settings: CustomTtsSettings, port: Int): Result<Unit> =
            Result.success(Unit)

        override suspend fun fetchVoices(port: Int): Result<List<com.opensecurity.customtts.model.CustomTtsVoiceEntry>> =
            Result.success(emptyList())

        override suspend fun removeVoice(voiceId: String): Result<Unit> = Result.success(Unit)
        override suspend fun engineExists(): Boolean = true
    }

    private class FakeHttpTtsGateway : HttpTtsEngineGateway {
        override fun observeAll(): Flow<List<TtsEngineDescriptor>> = flowOf(emptyList())
    }

    private class FakeCloudTtsGateway : CloudTtsEngineGateway {
        override fun observeAll(): Flow<List<CloudTtsEngine>> = flowOf(emptyList())
        override suspend fun getAll(): List<CloudTtsEngine> = emptyList()
        override suspend fun get(id: String): CloudTtsEngine? = null
        override suspend fun upsert(engine: CloudTtsEngine) = Unit
        override suspend fun delete(engine: CloudTtsEngine) = Unit
    }

    private class FakeVoiceGateway : ReadAloudVoiceGateway {
        override fun observeVoices(): Flow<List<ReadAloudVoice>> = flowOf(emptyList())
        override suspend fun getVoices(): List<ReadAloudVoice> = emptyList()
        override suspend fun getEnabledVoices(): List<ReadAloudVoice> = emptyList()
        override suspend fun getVoice(id: String): ReadAloudVoice? = null
        override suspend fun upsertVoice(voice: ReadAloudVoice) = Unit
        override suspend fun deleteVoice(voice: ReadAloudVoice) = Unit
        override fun observeBindings(bookUrl: String): Flow<List<BookVoiceBinding>> =
            flowOf(emptyList())
        override suspend fun getBindings(bookUrl: String): List<BookVoiceBinding> = emptyList()
        override suspend fun getBinding(
            bookUrl: String, subjectType: String, subjectId: String,
        ): BookVoiceBinding? = null
        override suspend fun upsertBinding(binding: BookVoiceBinding) = Unit
        override suspend fun deleteBinding(binding: BookVoiceBinding) = Unit
        override suspend fun getBookTtsSelection(bookUrl: String): String? = null
        override suspend fun setBookTtsSelection(bookUrl: String, value: String?) = Unit
    }

    private lateinit var settings: FakeSettingsGateway
    private lateinit var catalog: FakeCatalogGateway

    @Before
    fun setUp() {
        settings = FakeSettingsGateway()
        catalog = FakeCatalogGateway()
    }

    @After
    fun tearDown() {
        collectScope.cancel()
    }

    private fun createViewModel(): CustomTtsViewModel {
        val vm = CustomTtsViewModel(
            settings, catalog, FakeHttpTtsGateway(), FakeCloudTtsGateway(), FakeVoiceGateway(),
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return vm
    }

    private fun collectEffects(vm: CustomTtsViewModel): MutableList<CustomTtsEffect> {
        val effects = mutableListOf<CustomTtsEffect>()
        collectScope.launch { vm.effects.collect { effects.add(it) } }
        return effects
    }

    @Test
    fun `初始设置应用到页面`() {
        settings.emit(
            CustomTtsSettings(
                defaultVoiceId = "v1",
                segmentStairs = listOf(30, 60, 80),
                segmentTarget = 99,
            )
        )
        val vm = createViewModel()
        assertEquals("v1", vm.uiState.value.defaultVoiceId)
        assertEquals("30,60,80", vm.uiState.value.segmentStairsText)
        assertEquals("99", vm.uiState.value.segmentTarget)
    }

    @Test
    fun `后续设置更新不覆盖页面未保存草稿`() {
        val vm = createViewModel()
        vm.onIntent(CustomTtsIntent.SetSegmentStairs("25,50,80"))
        vm.onIntent(CustomTtsIntent.SetSegmentTarget("70"))
        // 保存单个音色参数等价于 settings 更新
        settings.emit(CustomTtsSettings(segmentTarget = 42, segmentStairs = listOf(20, 40, 80)))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("25,50,80", vm.uiState.value.segmentStairsText)
        assertEquals("70", vm.uiState.value.segmentTarget)
    }

    @Test
    fun `保存时非法 PSK 给出提示且不落盘`() {
        val vm = createViewModel()
        val effects = collectEffects(vm)
        vm.onIntent(CustomTtsIntent.SetPsk("not-base64"))
        vm.onIntent(CustomTtsIntent.Save)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(effects.any { it is CustomTtsEffect.ShowToast && it.message.contains("PSK") })
    }

    @Test
    fun `保存时非法阶梯序列给出提示`() {
        val vm = createViewModel()
        val effects = collectEffects(vm)
        vm.onIntent(CustomTtsIntent.SetPsk(android.util.Base64.encodeToString(
            ByteArray(32), android.util.Base64.NO_WRAP)))
        vm.onIntent(CustomTtsIntent.SetSegmentStairs("0,abc"))
        vm.onIntent(CustomTtsIntent.Save)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(effects.any { it is CustomTtsEffect.ShowToast && it.message.contains("阶梯") })
    }

    @Test
    fun `保存音色参数非法时提示`() {
        val vm = createViewModel()
        val effects = collectEffects(vm)
        vm.onIntent(
            CustomTtsIntent.SaveVoiceParams(
                "v1",
                com.opensecurity.customtts.model.CustomTtsVoiceParams(voiceId = "v1", speed = 9f),
            )
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(effects.any { it is CustomTtsEffect.ShowToast })
    }

    @Test
    fun `仅改速度时保存立即落盘且不重启网关`() {
        val store = com.opensecurity.remotelink.gateway.ConfigStore(app)
        val psk = android.util.Base64.encodeToString(ByteArray(32), android.util.Base64.NO_WRAP)
        store.relayHost = "74.120.173.111"
        store.relayPort = 41000
        store.pskBase64 = psk
        store.nodeId = "macmini"
        store.localPort = 18888
        // 网关未启用：连接参数不变且无重试需求 → 必不重启
        //（“网关运行中”的组合由 requiresGatewayRestart 纯函数用例覆盖）
        store.enabled = false
        val vm = createViewModel()
        val restarts = AtomicInteger(0)
        vm.restartGateway = { restarts.incrementAndGet() }

        vm.onIntent(CustomTtsIntent.SetCustomSpeed(1.2f))
        vm.onIntent(CustomTtsIntent.SetFallbackSpeed(0.6f))
        vm.onIntent(CustomTtsIntent.Save)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // 连接参数未变 → 不重启网关（朗读中的在途远端请求不受打扰）
        assertEquals(0, restarts.get())
        // 设置已同步落盘：朗读中的下一段立即读到新速度
        assertEquals(1.2f, settings.currentSettings.hybrid.customSpeed)
        assertEquals(0.6f, settings.currentSettings.hybrid.fallbackSpeed)
    }

    @Test
    fun `网关重启条件`() {
        // 连接参数变化 → 必重启
        assertTrue(requiresGatewayRestart(changed = true, enabled = false, runningPort = 0))
        // 未启用且无变化 → 不重启
        assertFalse(requiresGatewayRestart(changed = false, enabled = false, runningPort = 0))
        // 已启用且正常运行（朗读中改速度的场景）→ 不重启
        assertFalse(requiresGatewayRestart(changed = false, enabled = true, runningPort = 18888))
        // 已启用但没跑起来（启动失败后重新保存=重试）→ 重启
        assertTrue(requiresGatewayRestart(changed = false, enabled = true, runningPort = 0))
    }

    @Test
    fun `改中继地址时保存需要重启网关`() {
        val store = com.opensecurity.remotelink.gateway.ConfigStore(app)
        val psk = android.util.Base64.encodeToString(ByteArray(32), android.util.Base64.NO_WRAP)
        store.relayHost = "74.120.173.111"
        store.relayPort = 41000
        store.pskBase64 = psk
        store.nodeId = "macmini"
        store.localPort = 18888
        store.enabled = true
        val vm = createViewModel()
        val restarts = AtomicInteger(0)
        vm.restartGateway = { restarts.incrementAndGet() }

        vm.onIntent(CustomTtsIntent.SetRelayHost("10.0.0.8"))
        vm.onIntent(CustomTtsIntent.Save)

        // withContext(IO) 异步完成后回主线程，等待其落地
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && restarts.get() == 0) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals(1, restarts.get())
    }
}
