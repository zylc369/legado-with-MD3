package io.legado.app.data.repository

import android.app.Application
import io.legado.app.data.local.preferences.LocalPreferencesKeys
import io.legado.app.domain.model.settings.BackupSettings
import io.legado.app.help.config.AppConfigStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

/**
 * 备份密码按地址记忆：
 * - 同一地址反复备份用同一密码，换地址才需要新密码；
 * - 本地/WebDav/混合地址键互不相同；
 * - 旧版全局密码只作迁移默认与恢复预填。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BackupPasswordStoreTest {

    private val localSettings = BackupSettings(backupPath = "content://backup/local-a")
    private val webDavSettings = BackupSettings(
        webDavUrl = "https://dav.example.com",
        webDavAccount = "user@example.com",
        webDavDir = "legado",
        backupPath = "content://backup/local-a",
    )

    @Before
    fun setUp() {
        AppConfigStore.init(RuntimeEnvironment.getApplication())
        RuntimeEnvironment.getApplication().injectAsAppCtx()
        runBlocking {
            AppConfigStore.putAllAndAwait(
                mapOf(
                    LocalPreferencesKeys.BACKUP_ADDRESS_PASSWORDS.name to "",
                    LocalPreferencesKeys.PASSWORD.name to "",
                )
            )
        }
    }

    @Test
    fun addressKey_localUsesPath() {
        assertEquals(
            "local|content://backup/local-a",
            BackupPasswordStore.addressKey(BackupPasswordStore.MODE_LOCAL, localSettings)
        )
        // 同一地址不同调用方式（显式传入 path）得到同一个键
        assertEquals(
            BackupPasswordStore.addressKey(BackupPasswordStore.MODE_LOCAL, localSettings),
            BackupPasswordStore.addressKey(
                BackupPasswordStore.MODE_LOCAL,
                localSettings,
                "content://backup/local-a"
            )
        )
    }

    @Test
    fun addressKey_webDavUsesUrlDirAndAccount() {
        val key = BackupPasswordStore.addressKey(
            BackupPasswordStore.MODE_WEBDAV,
            webDavSettings
        )
        assertEquals("webdav|https://dav.example.com|legado|user@example.com", key)
        // 换账号/目录/URL 都是不同地址
        assertNotEquals(
            key,
            BackupPasswordStore.addressKey(
                BackupPasswordStore.MODE_WEBDAV,
                webDavSettings.copy(webDavDir = "other")
            )
        )
        assertNotEquals(
            key,
            BackupPasswordStore.addressKey(
                BackupPasswordStore.MODE_WEBDAV,
                webDavSettings.copy(webDavAccount = "other@example.com")
            )
        )
        assertNotEquals(
            key,
            BackupPasswordStore.addressKey(BackupPasswordStore.MODE_LOCAL, webDavSettings)
        )
    }

    @Test
    fun addressKey_bothPrefersWebDavWhenConfigured() {
        assertEquals(
            BackupPasswordStore.addressKey(BackupPasswordStore.MODE_WEBDAV, webDavSettings),
            BackupPasswordStore.addressKey(BackupPasswordStore.MODE_BOTH, webDavSettings)
        )
        // 未配置 WebDav 时混合模式实际只落本地
        assertEquals(
            "local|content://backup/local-a",
            BackupPasswordStore.addressKey(BackupPasswordStore.MODE_BOTH, localSettings)
        )
    }

    @Test
    fun rememberAndRead_perAddress() = runBlocking {
        val keyA = BackupPasswordStore.addressKey(BackupPasswordStore.MODE_LOCAL, localSettings)
        BackupPasswordStore.remember(keyA, "pa")

        assertEquals("pa", BackupPasswordStore.passwordFor(keyA))
        assertNull(BackupPasswordStore.passwordFor("local|content://backup/other"))
    }

    @Test
    fun remember_overwritesSameAddressKeepsOthers() = runBlocking {
        val keyA = "local|a"
        val keyB = "local|b"
        BackupPasswordStore.remember(keyA, "p1")
        BackupPasswordStore.remember(keyB, "p2")
        BackupPasswordStore.remember(keyA, "p1-new")

        assertEquals("p1-new", BackupPasswordStore.passwordFor(keyA))
        assertEquals("p2", BackupPasswordStore.passwordFor(keyB))
    }

    @Test
    fun legacyDefault_readsGlobalBackupPasswordWithoutCopying() = runBlocking {
        AppConfigStore.putAllAndAwait(
            mapOf(LocalPreferencesKeys.PASSWORD.name to "legacy")
        )

        assertEquals("legacy", BackupPasswordStore.legacyDefault())
        // 仅作默认/预填，不自动写入任何地址
        assertNull(BackupPasswordStore.passwordFor("local|a"))
    }

    @Test
    fun restoreSuggestion_prefersRememberedThenLegacy() = runBlocking {
        AppConfigStore.putAllAndAwait(
            mapOf(LocalPreferencesKeys.PASSWORD.name to "legacy")
        )
        assertEquals("legacy", BackupPasswordStore.suggestedRestorePassword())

        BackupPasswordStore.rememberRestoreSuggestion("lastUsed")
        assertEquals("lastUsed", BackupPasswordStore.suggestedRestorePassword())
    }
}
