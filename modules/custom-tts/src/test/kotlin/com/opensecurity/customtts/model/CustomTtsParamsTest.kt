package com.opensecurity.customtts.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomTtsParamsTest {

    @Test
    fun `默认参数合法`() {
        assertTrue(CustomTtsParams.validate(CustomTtsVoiceParams()).isSuccess)
    }

    @Test
    fun `速度边界`() {
        assertTrue(CustomTtsParams.validate(CustomTtsVoiceParams(speed = 0.5f)).isSuccess)
        assertTrue(CustomTtsParams.validate(CustomTtsVoiceParams(speed = 3.0f)).isSuccess)
        assertTrue(CustomTtsParams.validate(CustomTtsVoiceParams(speed = 0.49f)).isFailure)
        assertTrue(CustomTtsParams.validate(CustomTtsVoiceParams(speed = 3.01f)).isFailure)
    }

    @Test
    fun `种子边界`() {
        assertTrue(CustomTtsParams.validate(CustomTtsVoiceParams(seed = -1)).isSuccess)
        assertTrue(CustomTtsParams.validate(CustomTtsVoiceParams(seed = 0)).isSuccess)
        assertTrue(CustomTtsParams.validate(CustomTtsVoiceParams(seed = 99_999)).isSuccess)
        assertTrue(CustomTtsParams.validate(CustomTtsVoiceParams(seed = -2)).isFailure)
        assertTrue(CustomTtsParams.validate(CustomTtsVoiceParams(seed = 100_000)).isFailure)
    }

    @Test
    fun `指令长度边界`() {
        assertTrue(CustomTtsParams.validate(CustomTtsVoiceParams(instruct = "字".repeat(200))).isSuccess)
        assertTrue(CustomTtsParams.validate(CustomTtsVoiceParams(instruct = "字".repeat(201))).isFailure)
    }

    @Test
    fun `模式白名单`() {
        assertTrue(CustomTtsParams.validate(CustomTtsVoiceParams(mode = "")).isSuccess)
        assertTrue(CustomTtsParams.validate(CustomTtsVoiceParams(mode = "instruct")).isSuccess)
        assertTrue(CustomTtsParams.validate(CustomTtsVoiceParams(mode = "foo")).isFailure)
    }

    @Test
    fun `音色 ID 长度`() {
        assertTrue(CustomTtsParams.validate(CustomTtsVoiceParams(voiceId = "v".repeat(64))).isSuccess)
        assertTrue(CustomTtsParams.validate(CustomTtsVoiceParams(voiceId = "v".repeat(65))).isFailure)
    }

    @Test
    fun `请求体字段与服务端对齐`() {
        val params = CustomTtsVoiceParams(
            voiceId = "spk1", speed = 1.2f, instruct = "轻声", seed = 7, mode = "instruct",
        )
        val map = CustomTtsParams.toRequestMap(params, "你好")
        assertEquals("你好", map["text"])
        assertEquals("spk1", map["voice"])
        assertEquals(1.2f, map["speed"])
        assertEquals("轻声", map["instruct"])
        assertEquals(7, map["seed"])
        assertEquals("instruct", map["mode"])
    }
}
