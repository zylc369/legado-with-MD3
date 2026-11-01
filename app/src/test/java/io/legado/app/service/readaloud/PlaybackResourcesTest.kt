package io.legado.app.service.readaloud

import android.app.Application
import android.net.wifi.WifiManager
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 播放期资源所有权：所有播放态转换共享同一个幂等入口。
 *
 * 回归背景：唤醒锁只在 play() 获取、pauseReadAloud() 释放，而 resumeReadAloud()
 * 不获取——一次暂停（含通知音导致的焦点瞬断）之后朗读就在无锁状态继续，
 * 熄屏深睡导致下一段永久不读，只有重建朗读会话才恢复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PlaybackResourcesTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var keepAlive: FakeKeepAlive

    private class FakeKeepAlive : ReadAloudKeepAlive() {
        var running = false
        override fun start() {
            running = true
        }

        override fun stop() {
            running = false
        }
    }

    @Before
    fun setUp() {
        val power = app.getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "test:ReadAloud")
        @Suppress("DEPRECATION")
        wifiLock = app.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "test:ReadAloud")
        keepAlive = FakeKeepAlive()
    }

    private fun resources(wakeLockEnabled: Boolean = true) = PlaybackResources(
        wakeLockProvider = { if (wakeLockEnabled) wakeLock else null },
        wifiLockProvider = { if (wakeLockEnabled) wifiLock else null },
        keepAlive = keepAlive,
        log = {},
    )

    @Test
    fun `获取后持有锁与保活，释放后全部归还`() {
        val r = resources()
        r.acquire("play")
        assertTrue(r.isAcquired)
        assertTrue(wakeLock.isHeld)
        assertTrue(keepAlive.running)

        r.release("pause")
        assertFalse(r.isAcquired)
        assertFalse(wakeLock.isHeld)
        assertFalse(keepAlive.running)
    }

    @Test
    fun `重复获取是幂等的`() {
        val r = resources()
        r.acquire("play")
        r.acquire("resume")
        assertTrue(wakeLock.isHeld)
        r.release("pause")
        assertFalse(wakeLock.isHeld)
    }

    @Test
    fun `未获取时释放不报错也不误释放`() {
        val r = resources()
        r.release("destroy")
        assertFalse(r.isAcquired)
        assertFalse(wakeLock.isHeld)
    }

    @Test
    fun `暂停后恢复必须重新持有唤醒锁`() {
        val r = resources()
        r.acquire("play")
        r.release("pause")     // 通知音导致焦点瞬断、用户暂停等
        r.acquire("resume")    // 通知栏/MediaSession/焦点恢复都走这里
        assertTrue("恢复播放后必须重新持有唤醒锁", wakeLock.isHeld)
        assertTrue(r.isAcquired)
    }

    @Test
    fun `关闭唤醒锁设置时仍保持播放态与保活音频`() {
        val r = resources(wakeLockEnabled = false)
        r.acquire("play")
        assertTrue(r.isAcquired)
        assertFalse(wakeLock.isHeld)
        assertTrue(keepAlive.running)
        r.release("pause")
        assertFalse(keepAlive.running)
    }

    @Test
    fun `设置关闭后下一次获取不再持锁`() {
        var enabled = true
        val r = PlaybackResources(
            wakeLockProvider = { if (enabled) wakeLock else null },
            wifiLockProvider = { null },
            keepAlive = keepAlive,
            log = {},
        )
        r.acquire("play")
        assertTrue(wakeLock.isHeld)
        r.release("pause")
        enabled = false
        r.acquire("resume")
        assertFalse(wakeLock.isHeld)
        assertNotNull(keepAlive)
        r.release("pause")
    }
}
