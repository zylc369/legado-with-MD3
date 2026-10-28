package io.legado.app.data.repository

import android.app.Application
import io.legado.app.data.local.preferences.LocalPreferencesKeys
import io.legado.app.help.config.AppConfigStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

/**
 * 私密密码存储：与备份密码解耦、可自愈、可重置。
 *
 * 对应缺陷：在「隐私」页设置密码后在私密模式验证不通过——旧实现与备份密码共用
 * PASSWORD 键且校验值可能过期；这里锁住"设置页保存的密码一定能验证"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PrivatePasswordStoreTest {

    @Before
    fun setUp() {
        AppConfigStore.init(RuntimeEnvironment.getApplication())
        RuntimeEnvironment.getApplication().injectAsAppCtx()
        runBlocking { clearAll() }
    }

    private suspend fun clearAll() {
        AppConfigStore.putAllAndAwait(
            mapOf(
                LocalPreferencesKeys.PRIVATE_PASSWORD.name to "",
                LocalPreferencesKeys.PRIVATE_PASSWORD_SALT.name to "",
                LocalPreferencesKeys.PRIVATE_PASSWORD_VERIFIER.name to "",
                LocalPreferencesKeys.PRIVATE_BIOMETRIC_ENVELOPE.name to "",
                LocalPreferencesKeys.PRIVATE_BIOMETRIC_IV.name to "",
                LocalPreferencesKeys.PRIVATE_BIOMETRIC_ENABLED.name to false,
                LocalPreferencesKeys.PASSWORD.name to "",
                LocalPreferencesKeys.PRIVATE_PASSWORD_MIGRATED.name to false,
            )
        )
    }

    @Test
    fun setPassword_thenVerify() = runBlocking {
        PrivatePasswordStore.setPassword("abc123")

        assertTrue(PrivatePasswordStore.hasPassword())
        assertTrue(PrivatePasswordStore.verify("abc123"))
        assertFalse(PrivatePasswordStore.verify("wrong"))
        assertFalse(PrivatePasswordStore.verify(""))
    }

    @Test
    fun changePassword_verifiesNewOnly_andClearsBiometricEnvelope() = runBlocking {
        PrivatePasswordStore.setPassword("old")
        AppConfigStore.putAllAndAwait(
            mapOf(
                LocalPreferencesKeys.PRIVATE_BIOMETRIC_ENVELOPE.name to "cipher",
                LocalPreferencesKeys.PRIVATE_BIOMETRIC_IV.name to "iv",
                LocalPreferencesKeys.PRIVATE_BIOMETRIC_ENABLED.name to true,
            )
        )

        PrivatePasswordStore.setPassword("new")

        assertTrue(PrivatePasswordStore.verify("new"))
        assertFalse(PrivatePasswordStore.verify("old"))
        // 旧信封里是旧密码，必须作废
        assertEquals(
            "",
            AppConfigStore.getString(LocalPreferencesKeys.PRIVATE_BIOMETRIC_ENVELOPE.name)
        )
        assertEquals(
            false,
            AppConfigStore.getBoolean(LocalPreferencesKeys.PRIVATE_BIOMETRIC_ENABLED.name)
        )
    }

    @Test
    fun staleVerifier_isHealedByStoredPassword() = runBlocking {
        // 历史错位：校验值属于旧密码，明文是设置页最后保存的新密码
        val salt = io.legado.app.help.security.PrivatePasswordCipher.generateSalt()
        AppConfigStore.putAllAndAwait(
            mapOf(
                LocalPreferencesKeys.PRIVATE_PASSWORD.name to "current",
                LocalPreferencesKeys.PRIVATE_PASSWORD_SALT.name to salt,
                LocalPreferencesKeys.PRIVATE_PASSWORD_VERIFIER.name to
                        io.legado.app.help.security.PrivatePasswordCipher
                            .deriveVerifier("stale", salt).orEmpty(),
            )
        )

        // 明文权威：最近保存的密码可用，并修复校验值；旧密码不再放行
        assertTrue(PrivatePasswordStore.verify("current"))
        assertTrue(PrivatePasswordStore.verify("current"))
        assertFalse(PrivatePasswordStore.verify("stale"))
    }

    @Test
    fun legacyMigration_copiesOnlyWhenPrivateFeatureWasUsed() = runBlocking {
        // 私密功能用过（校验值存在）+ 旧 PASSWORD → 迁移；以 PASSWORD 为准重建校验值
        val salt = io.legado.app.help.security.PrivatePasswordCipher.generateSalt()
        AppConfigStore.putAllAndAwait(
            mapOf(
                LocalPreferencesKeys.PASSWORD.name to "legacyPass",
                LocalPreferencesKeys.PRIVATE_PASSWORD_SALT.name to salt,
                LocalPreferencesKeys.PRIVATE_PASSWORD_VERIFIER.name to
                        io.legado.app.help.security.PrivatePasswordCipher
                            .deriveVerifier("olderValue", salt).orEmpty(),
            )
        )

        PrivatePasswordStore.migrateLegacyIfNeeded()

        assertTrue(PrivatePasswordStore.hasPassword())
        assertTrue(PrivatePasswordStore.verify("legacyPass"))
    }

    @Test
    fun legacyMigration_skipsPureBackupPassword() = runBlocking {
        // 只有备份密码、从未用过私密功能：不得把它当成私密密码
        AppConfigStore.putAllAndAwait(
            mapOf(LocalPreferencesKeys.PASSWORD.name to "backupPass")
        )

        PrivatePasswordStore.migrateLegacyIfNeeded()

        assertFalse(PrivatePasswordStore.hasPassword())
        assertFalse(PrivatePasswordStore.verify("backupPass"))
    }

    @Test
    fun reset_clearsEverythingWithoutTouchingBackupPassword() = runBlocking {
        AppConfigStore.putAllAndAwait(
            mapOf(LocalPreferencesKeys.PASSWORD.name to "backupPass")
        )
        PrivatePasswordStore.setPassword("secret")

        PrivatePasswordStore.reset()

        assertFalse(PrivatePasswordStore.hasPassword())
        assertFalse(PrivatePasswordStore.verify("secret"))
        // 备份密码保持不动（两套凭据互不影响）
        assertEquals(
            "backupPass",
            AppConfigStore.getString(LocalPreferencesKeys.PASSWORD.name)
        )
    }
}
