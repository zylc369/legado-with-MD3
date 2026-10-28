package io.legado.app.data.repository

import android.content.Context
import io.legado.app.data.local.preferences.LocalPreferencesKeys
import io.legado.app.domain.gateway.PrivateAccessGateway
import io.legado.app.domain.model.PrivateAccessState
import io.legado.app.domain.model.settings.PrivateAccessSettings
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.security.BiometricUnlockLauncher
import io.legado.app.help.security.PrivatePasswordCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 私密模式会话宿主。
 *
 * 解锁状态只放在进程内存里（[sessionUnlockedFlow]）：进程重启即回到锁定态；
 * 退出私密模式与切后台宽限期到期都通过 [lock] 立即收回。
 *
 * 两种读写路径并存是有意的：设置项走 [SettingsRepository] 的 flow（需要把变化推给界面），
 * 凭据（salt / 校验值 / 信封）走 [AppConfigStore] 直读写——它们是"校验时读一次"的短值，
 * 不需要被观察，也不该在 flow 里反复解码。两条路径落在同一份 DataStore 上，
 * 所以不存在"界面看到的和读到的不一致"。
 */
class PrivateAccessRepository(
    context: Context,
    private val settingsRepository: SettingsRepository,
) : PrivateAccessGateway {

    private val appContext = context.applicationContext

    private val sessionUnlockedFlow = MutableStateFlow(false)

    private val biometricStatusFlow = MutableStateFlow(
        BiometricUnlockLauncher.detectStatus(appContext)
    )

    private val hasPasswordFlow = settingsRepository
        .getString(LocalPreferencesKeys.PRIVATE_PASSWORD.name, "")
        .map { it.isNotEmpty() }
        .distinctUntilChanged()

    private val biometricEnabledFlow = settingsRepository
        .getBoolean(LocalPreferencesKeys.PRIVATE_BIOMETRIC_ENABLED.name, false)
        .distinctUntilChanged()

    private val backgroundGraceFlow = settingsRepository
        .getInt(
            LocalPreferencesKeys.PRIVATE_BACKGROUND_GRACE_SECONDS.name,
            DEFAULT_BACKGROUND_GRACE_SECONDS
        )
        .distinctUntilChanged()

    override val settings: Flow<PrivateAccessSettings> = backgroundGraceFlow
        .map { PrivateAccessSettings(backgroundGraceSeconds = it) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    override suspend fun updateSettings(transform: (PrivateAccessSettings) -> PrivateAccessSettings) {
        val current = PrivateAccessSettings(
            backgroundGraceSeconds = AppConfigStore.getInt(
                LocalPreferencesKeys.PRIVATE_BACKGROUND_GRACE_SECONDS.name
            ) ?: DEFAULT_BACKGROUND_GRACE_SECONDS
        )
        val next = transform(current)
        if (next == current) return
        AppConfigStore.putAllAndAwait(
            mapOf(
                LocalPreferencesKeys.PRIVATE_BACKGROUND_GRACE_SECONDS.name to
                        next.backgroundGraceSeconds
            )
        )
    }

    override val state: Flow<PrivateAccessState> = combine(
        sessionUnlockedFlow,
        hasPasswordFlow,
        biometricStatusFlow,
        biometricEnabledFlow,
    ) { unlocked, hasPassword, status, biometricEnabled ->
        PrivateAccessState(
            isSessionUnlocked = unlocked,
            hasPassword = hasPassword,
            biometricStatus = status,
            biometricEnabled = biometricEnabled,
        )
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    /** 校验 + 授权必须串行：并发两次验证不能出现"其中一次白验" */
    private val unlockMutex = Mutex()

    override suspend fun verifyPasswordOnly(password: String): Boolean =
        withContext(Dispatchers.Default) { checkPassword(password) }

    /** 校验密码本身。不碰任何授权状态——授权由 [unlockSession] 决定 */
    private suspend fun checkPassword(password: String): Boolean =
        withContext(Dispatchers.Default) {
            if (password.isEmpty()) return@withContext false
            PrivatePasswordStore.migrateLegacyIfNeeded()
            PrivatePasswordStore.verify(password)
        }

    override suspend fun setPassword(password: String?) {
        PrivatePasswordStore.setPassword(password)
        lock()
    }

    override suspend fun resetPassword() {
        PrivatePasswordStore.reset()
        lock()
    }

    override suspend fun unlockSession(password: String): Boolean =
        withContext(Dispatchers.Default) {
            if (!checkPassword(password)) return@withContext false
            unlockMutex.withLock {
                sessionUnlockedFlow.value = true
            }
            true
        }

    override fun isSessionUnlockedNow(): Boolean = sessionUnlockedFlow.value

    override fun lock() {
        sessionUnlockedFlow.value = false
    }

    override suspend fun disableBiometricShortcut() = withContext(Dispatchers.Default) {
        AppConfigStore.putAllAndAwait(
            mapOf(
                LocalPreferencesKeys.PRIVATE_BIOMETRIC_ENABLED.name to false,
                LocalPreferencesKeys.PRIVATE_BIOMETRIC_ENVELOPE.name to "",
                LocalPreferencesKeys.PRIVATE_BIOMETRIC_IV.name to ""
            )
        )
    }

    override suspend fun storeBiometricEnvelope(ciphertext: String, iv: String): Boolean =
        withContext(Dispatchers.Default) {
            if (ciphertext.isBlank() || iv.isBlank()) return@withContext false
            val status = BiometricUnlockLauncher.detectStatus(appContext)
            biometricStatusFlow.value = status
            if (status != io.legado.app.domain.model.PrivateBiometricStatus.Available) {
                return@withContext false
            }
            AppConfigStore.putAllAndAwait(
                mapOf(
                    LocalPreferencesKeys.PRIVATE_BIOMETRIC_ENVELOPE.name to ciphertext,
                    LocalPreferencesKeys.PRIVATE_BIOMETRIC_IV.name to iv,
                    LocalPreferencesKeys.PRIVATE_BIOMETRIC_ENABLED.name to true
                )
            )
            true
        }

    override fun refreshBiometricStatus() {
        biometricStatusFlow.value = BiometricUnlockLauncher.detectStatus(appContext)
    }

    companion object {
        /** 新模型默认宽限：60 秒内回到应用不重新验证 */
        const val DEFAULT_BACKGROUND_GRACE_SECONDS = 60
    }
}
