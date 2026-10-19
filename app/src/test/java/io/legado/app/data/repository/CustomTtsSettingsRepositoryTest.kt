package io.legado.app.data.repository

import io.legado.app.domain.model.settings.CustomTtsSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class CustomTtsSettingsRepositoryTest {

    private val app = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }

    @Before
    fun clean() {
        File(app.filesDir, CustomTtsSettingsRepository.FILE_NAME).delete()
    }

    @Test
    fun `无文件时返回默认值`() {
        assertEquals(CustomTtsSettings(), CustomTtsSettingsRepository().currentSettings)
    }

    @Test
    fun `更新后新实例可读回`() = runBlocking {
        val repo = CustomTtsSettingsRepository()
        repo.update {
            it.copy(
                defaultVoiceId = "v1",
                segmentStairs = listOf(30, 60, 80),
                voices = mapOf("v1" to com.opensecurity.customtts.model.CustomTtsVoiceParams(
                    voiceId = "v1", displayName = "小北", speed = 1.2f,
                    instruct = "轻声", seed = 7, mode = "instruct",
                )),
            )
        }
        val again = CustomTtsSettingsRepository()
        assertEquals("v1", again.currentSettings.defaultVoiceId)
        assertEquals(listOf(30, 60, 80), again.currentSettings.segmentStairs)
        assertEquals(1.2f, again.currentSettings.voices["v1"]?.speed)
        assertEquals("小北", again.currentSettings.voices["v1"]?.displayName)
        assertEquals("轻声", again.currentSettings.voices["v1"]?.instruct)
        assertEquals(7, again.currentSettings.voices["v1"]?.seed)
    }

    @Test
    fun `损坏 JSON 回退默认值`() {
        File(app.filesDir, CustomTtsSettingsRepository.FILE_NAME).writeText("{bad json")
        assertEquals(CustomTtsSettings(), CustomTtsSettingsRepository().currentSettings)
    }

    @Test
    fun `缺失字段逐项回退默认`() {
        File(app.filesDir, CustomTtsSettingsRepository.FILE_NAME).writeText("""{"defaultVoiceId":"x"}""")
        val s = CustomTtsSettingsRepository().currentSettings
        assertEquals("x", s.defaultVoiceId)
        assertEquals(80, s.segmentTarget)
        assertEquals(listOf(20, 40, 60, 80), s.segmentStairs)
        assertEquals(true, s.hybrid.firstSegmentFallback)
    }

    @Test
    fun `StateFlow 随更新变化`() = runBlocking {
        val repo = CustomTtsSettingsRepository()
        repo.update { it.copy(defaultVoiceId = "a") }
        assertEquals("a", repo.currentSettings.defaultVoiceId)
        assertEquals("a", repo.settings.value.defaultVoiceId)
    }
}
