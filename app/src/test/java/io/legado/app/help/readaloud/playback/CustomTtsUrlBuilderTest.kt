package io.legado.app.help.readaloud.playback

import com.opensecurity.customtts.model.CustomTtsVoiceParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomTtsUrlBuilderTest {

    private val template = "http://127.0.0.1:18888/tts?text={{speakText}}"

    @Test
    fun `模板展开并追加全部参数`() {
        val url = CustomTtsUrlBuilder.buildCustomTtsUrl(
            template,
            "你好",
            CustomTtsVoiceParams(
                voiceId = "spk1", speed = 1.3f, instruct = "轻声细语", seed = 42, mode = "instruct",
            ),
        )
        assertTrue(url.startsWith("http://127.0.0.1:18888/tts?text="))
        assertTrue(url.contains("voice=spk1"))
        assertTrue(url.contains("speed=1.3"))
        assertTrue(url.contains("instruct=%E8%BD%BB%E5%A3%B0%E7%BB%86%E8%AF%AD"))
        assertTrue(url.contains("seed=42"))
        assertTrue(url.contains("mode=instruct"))
    }

    @Test
    fun `空 instruct mode 与负 seed 省略`() {
        val url = CustomTtsUrlBuilder.buildCustomTtsUrl(
            template, "abc", CustomTtsVoiceParams(voiceId = "", speed = 1.0f),
        )
        assertTrue(!url.contains("instruct="))
        assertTrue(!url.contains("mode="))
        assertTrue(!url.contains("seed="))
        assertTrue(!url.contains("voice="))
        assertTrue(url.contains("speed=1.0"))
    }

    @Test
    fun `无问号模板使用问号起始`() {
        val url = CustomTtsUrlBuilder.buildCustomTtsUrl(
            "http://127.0.0.1:1/tts", "x", CustomTtsVoiceParams(speed = 2.0f),
        )
        assertTrue(url.contains("/tts?speed=2.0"))
    }

    @Test
    fun `文本中的特殊字符被编码`() {
        val url = CustomTtsUrlBuilder.buildCustomTtsUrl(
            template, "a&b=c 你好", CustomTtsVoiceParams(speed = 1.0f),
        )
        assertTrue(url.contains("text=a%26b%3Dc+%E4%BD%A0%E5%A5%BD"))
    }

    @Test
    fun `seed 0 合法传递`() {
        val url = CustomTtsUrlBuilder.buildCustomTtsUrl(
            template, "x", CustomTtsVoiceParams(speed = 1.0f, seed = 0),
        )
        assertTrue(url.contains("seed=0"))
    }

    @Test
    fun `请求级超时参数追加`() {
        val url = CustomTtsUrlBuilder.buildCustomTtsUrl(
            template, "x", CustomTtsVoiceParams(speed = 1.0f), requestTimeoutMs = 10_000,
        )
        assertTrue(url.contains("timeout_ms=10000"))
    }

    @Test
    fun `未传超时不追加`() {
        val url = CustomTtsUrlBuilder.buildCustomTtsUrl(
            template, "x", CustomTtsVoiceParams(speed = 1.0f),
        )
        assertTrue(!url.contains("timeout_ms"))
    }
}
