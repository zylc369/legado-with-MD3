package io.legado.app.service.readaloud

import android.annotation.SuppressLint
import android.net.wifi.WifiManager
import android.os.PowerManager
import io.legado.app.constant.AppLog

/**
 * 朗读播放期资源（唤醒锁 / WiFi 锁 / 保活音频）的唯一所有权。
 *
 * 历史缺陷（实测导致息屏后下一段永久不读）：获取只写在 `play()`、释放写在
 * `pauseReadAloud()`，而所有"恢复播放"路径——通知栏恢复、MediaSession onPlay、
 * 音频焦点恢复、通话结束、章节等待恢复——都走 `resumeReadAloud()`，它不重新获取。
 * 于是一次暂停（哪怕只是通知提示音导致的焦点瞬断）之后，朗读在**没有唤醒锁**的
 * 状态下继续：熄屏 → CPU 深睡（AppFreezeMonitor 与墙钟/uptime 差值均可证实）→
 * 下一段的调度永远得不到执行，直到用户解锁或重建朗读会话（重建走 `play()` 才重新拿锁）。
 *
 * 本类把这些资源收敛为**幂等**的 acquire/release，所有播放态转换只能经过它，
 * 从结构上消除"某条路径漏拿/漏放"的可能：
 * - 唤醒锁按**当前设置**获取（关闭后下一次转换即生效，不必重启服务）；
 * - 保活音频独立于唤醒锁设置始终跟随播放态，用于厂商进程冻结场景；
 * - 每次转换带原因与持有状态日志，便于真机核查。
 */
class PlaybackResources(
    private val wakeLockProvider: () -> PowerManager.WakeLock?,
    private val wifiLockProvider: () -> WifiManager.WifiLock?,
    private val keepAlive: ReadAloudKeepAlive = ReadAloudKeepAlive(),
    private val log: (String) -> Unit = { AppLog.putDebug(it) },
) {

    private var acquired = false
    private var heldWakeLock: PowerManager.WakeLock? = null
    private var heldWifiLock: WifiManager.WifiLock? = null

    val isAcquired: Boolean get() = acquired

    @SuppressLint("WakelockTimeout")
    @Synchronized
    fun acquire(reason: String) {
        if (acquired) {
            log("朗读播放资源: 已持有，忽略重复获取（$reason）")
            return
        }
        acquired = true
        heldWakeLock = runCatching {
            wakeLockProvider()?.also { if (!it.isHeld) it.acquire() }
        }.getOrNull()
        heldWifiLock = runCatching {
            wifiLockProvider()?.also { if (!it.isHeld) it.acquire() }
        }.getOrNull()
        keepAlive.start()
        log(
            "朗读播放资源: 获取（$reason）wakeLock=${heldWakeLock != null} " +
                "wifiLock=${heldWifiLock != null} keepAlive=${keepAlive.isRunning}"
        )
    }

    @Synchronized
    fun release(reason: String) {
        if (!acquired) return
        acquired = false
        runCatching { heldWakeLock?.takeIf { it.isHeld }?.release() }
        heldWakeLock = null
        runCatching { heldWifiLock?.takeIf { it.isHeld }?.release() }
        heldWifiLock = null
        keepAlive.stop()
        log("朗读播放资源: 释放（$reason）")
    }
}
