package io.legado.app.help.readaloud.playback

import android.app.Application
import android.speech.tts.TextToSpeech
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SystemTtsEngineFactoryTest {

    @Test
    fun `初始化成功时回调同一个实例`() {
        var result: TextToSpeech? = null
        var called = false
        val tts = SystemTtsEngineFactory.create(
            context = RuntimeEnvironment.getApplication(),
            enginePackage = "",
        ) {
            called = true
            result = it
        }

        Shadows.shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)

        assertTrue(called)
        assertSame(tts, result)
        assertFalse(Shadows.shadowOf(tts).isShutdown)
    }

    @Test
    fun `初始化失败时回调 null 并关闭实例`() {
        var result: TextToSpeech? = null
        var called = false
        val tts = SystemTtsEngineFactory.create(
            context = RuntimeEnvironment.getApplication(),
            enginePackage = "",
        ) {
            called = true
            result = it
        }

        Shadows.shadowOf(tts).onInitListener.onInit(TextToSpeech.ERROR)

        assertTrue(called)
        assertNull(result)
        assertTrue(Shadows.shadowOf(tts).isShutdown)
    }
}
