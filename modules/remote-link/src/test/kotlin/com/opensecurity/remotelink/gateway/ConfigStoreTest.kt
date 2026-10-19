package com.opensecurity.remotelink.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConfigStoreTest {

    private fun store() = ConfigStore(RuntimeEnvironment.getApplication())

    @Test
    fun `默认值`() {
        val s = store()
        assertFalse(s.enabled)
        assertEquals(41000, s.relayPort)
        assertEquals("macmini", s.nodeId)
        assertEquals(18888, s.localPort)
        assertFalse(s.configured()) // 空 PSK
    }

    @Test
    fun `非法 PSK configured 为 false 不抛异常`() {
        val s = store()
        s.relayHost = "1.2.3.4"
        s.pskBase64 = "!!!not-base64!!!"
        assertFalse(s.configured())
    }

    @Test
    fun `长度不足的 PSK configured 为 false`() {
        val s = store()
        s.relayHost = "1.2.3.4"
        s.pskBase64 = android.util.Base64.encodeToString(
            ByteArray(16), android.util.Base64.NO_WRAP)
        assertFalse(s.configured())
    }

    @Test
    fun `合法 PSK configured 为 true`() {
        val s = store()
        s.relayHost = "1.2.3.4"
        s.pskBase64 = android.util.Base64.encodeToString(
            ByteArray(32), android.util.Base64.NO_WRAP)
        assertTrue(s.configured())
    }

    @Test
    fun `读写往返`() {
        val s = store()
        s.relayHost = "relay.example"
        s.relayPort = 1234
        s.nodeId = "n1"
        s.localPort = 19999
        s.enabled = true
        val again = store()
        assertEquals("relay.example", again.relayHost)
        assertEquals(1234, again.relayPort)
        assertEquals("n1", again.nodeId)
        assertEquals(19999, again.localPort)
        assertTrue(again.enabled)
    }
}
