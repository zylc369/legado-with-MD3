package io.legado.app.ui.config.privateConfig

import android.app.Application
import android.os.Looper
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.gateway.PrivateAccessGateway
import io.legado.app.domain.model.PrivateAccessState
import io.legado.app.domain.model.PrivateBiometricStatus
import io.legado.app.domain.model.settings.OtherSettings
import io.legado.app.domain.model.settings.PrivateAccessSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PrivateConfigViewModelTest {

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().injectAsAppCtx()
    }

    @Test
    fun savePassword_writesThroughLocalPasswordGateway() = runBlocking {
        val access = FakePrivateAccessGateway()
        val viewModel = createViewModel(privateAccessGateway = access)
        idleMainLooper()

        viewModel.onIntent(PrivateConfigIntent.SavePassword("secret"))
        idleMainLooper()

        assertEquals("secret", access.savedPassword)
        assertFalse(viewModel.uiState.value.showPasswordDialog)
    }

    @Test
    fun backgroundGraceSeconds_persistThroughPrivateAccessGateway() = runBlocking {
        val accessGateway = FakePrivateAccessGateway()
        val viewModel = createViewModel(privateAccessGateway = accessGateway)
        idleMainLooper()

        viewModel.onIntent(PrivateConfigIntent.SetBackgroundGraceSeconds(60))
        idleMainLooper()

        assertEquals(60, accessGateway.settingsValue.backgroundGraceSeconds)
        assertEquals(60, viewModel.uiState.value.settings.backgroundGraceSeconds)
    }

    @Test
    fun biometricShortcut_reportsUnavailableDeviceInsteadOfPretendingSuccess() = runBlocking {
        val accessGateway = FakePrivateAccessGateway()
        val viewModel = createViewModel(privateAccessGateway = accessGateway)
        idleMainLooper()

        val effect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effects.first() }
        viewModel.onIntent(PrivateConfigIntent.SetBiometricShortcutEnabled(true))
        idleMainLooper()

        assertFalse(accessGateway.biometricEnabled)
        assertFalse(viewModel.uiState.value.biometricEnabled)
        assertTrue(effect.await() is PrivateConfigEffect.ShowMessage)
    }

    @Test
    fun enablingBiometricShortcut_asksForAuthenticationAndDoesNotFlipSwitchEarly() = runBlocking {
        val accessGateway = FakePrivateAccessGateway(PrivateBiometricStatus.Available)
        val viewModel = createViewModel(privateAccessGateway = accessGateway)
        idleMainLooper()

        val effect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effects.first() }
        viewModel.onIntent(PrivateConfigIntent.SetBiometricShortcutEnabled(true))
        idleMainLooper()

        // 信封要等一次真实认证才产出，所以这里只能请求认证，开关绝不能先亮起来
        assertEquals(PrivateConfigEffect.RequestBiometricEnvelope, effect.await())
        assertFalse(accessGateway.envelopeStored)
        assertFalse(accessGateway.biometricEnabled)
        assertFalse(viewModel.uiState.value.biometricEnabled)
    }

    @Test
    fun storedEnvelope_flipsSwitchOnlyAfterGatewayAccepts() = runBlocking {
        val accessGateway = FakePrivateAccessGateway(PrivateBiometricStatus.Available)
        val viewModel = createViewModel(privateAccessGateway = accessGateway)
        idleMainLooper()

        viewModel.onIntent(PrivateConfigIntent.StoreBiometricEnvelope("ciphertext", "iv"))
        idleMainLooper()

        assertTrue(accessGateway.envelopeStored)
        assertTrue(viewModel.uiState.value.biometricEnabled)
    }

    @Test
    fun emptyEnvelope_neverFlipsSwitch() = runBlocking {
        val accessGateway = FakePrivateAccessGateway(PrivateBiometricStatus.Available)
        val viewModel = createViewModel(privateAccessGateway = accessGateway)
        idleMainLooper()

        viewModel.onIntent(PrivateConfigIntent.StoreBiometricEnvelope("", ""))
        idleMainLooper()

        assertFalse(accessGateway.envelopeStored)
        assertFalse(viewModel.uiState.value.biometricEnabled)
    }

    @Test
    fun disablingBiometricShortcut_clearsStoredEnvelope() = runBlocking {
        val accessGateway = FakePrivateAccessGateway(PrivateBiometricStatus.Available)
        val viewModel = createViewModel(privateAccessGateway = accessGateway)
        idleMainLooper()

        viewModel.onIntent(PrivateConfigIntent.StoreBiometricEnvelope("ciphertext", "iv"))
        idleMainLooper()
        assertTrue(viewModel.uiState.value.biometricEnabled)

        viewModel.onIntent(PrivateConfigIntent.SetBiometricShortcutEnabled(false))
        idleMainLooper()

        assertTrue(accessGateway.disabled)
        assertFalse(accessGateway.envelopeStored)
        assertFalse(viewModel.uiState.value.biometricEnabled)
    }

    private fun idleMainLooper() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun createViewModel(
        privateAccessGateway: FakePrivateAccessGateway = FakePrivateAccessGateway(),
        otherSettingsGateway: FakeOtherSettingsGateway = FakeOtherSettingsGateway(),
    ) = PrivateConfigViewModel(
        privateAccessGateway = privateAccessGateway,
        otherSettingsGateway = otherSettingsGateway,
    )

    private class FakePrivateAccessGateway(
        biometricStatus: PrivateBiometricStatus = PrivateBiometricStatus.NoHardware,
    ) : PrivateAccessGateway {
        private val accessFlow = MutableStateFlow(
            PrivateAccessState(
                hasPassword = true,
                biometricEnabled = false,
                biometricStatus = biometricStatus,
            )
        )
        private val settingsFlow = MutableStateFlow(PrivateAccessSettings())

        var biometricEnabled = false
            private set

        /** 信封是否真的落过盘 */
        var envelopeStored = false
            private set

        var disabled = false
            private set

        var savedPassword: String? = null
            private set

        val settingsValue: PrivateAccessSettings get() = settingsFlow.value

        override val state: Flow<PrivateAccessState> get() = accessFlow
        override val settings: Flow<PrivateAccessSettings> get() = settingsFlow

        override suspend fun updateSettings(transform: (PrivateAccessSettings) -> PrivateAccessSettings) {
            settingsFlow.value = transform(settingsFlow.value)
        }

        override suspend fun verifyPasswordOnly(password: String): Boolean = true

        override suspend fun unlockSession(password: String): Boolean = password.isNotEmpty()

        override fun isSessionUnlockedNow(): Boolean = accessFlow.value.isSessionUnlocked

        override suspend fun setPassword(password: String?) {
            savedPassword = password
            if (!password.isNullOrEmpty()) {
                accessFlow.value = accessFlow.value.copy(hasPassword = true)
            }
        }

        override suspend fun resetPassword() = Unit

        override suspend fun disableBiometricShortcut() {
            disabled = true
            biometricEnabled = false
            envelopeStored = false
            accessFlow.value = accessFlow.value.copy(biometricEnabled = false)
        }

        /** 与真实实现一致：空信封或能力不可用时拒绝写入，不假装成功 */
        override suspend fun storeBiometricEnvelope(ciphertext: String, iv: String): Boolean {
            if (ciphertext.isBlank() || iv.isBlank()) return false
            if (accessFlow.value.biometricStatus != PrivateBiometricStatus.Available) return false
            envelopeStored = true
            biometricEnabled = true
            accessFlow.value = accessFlow.value.copy(biometricEnabled = true)
            return true
        }

        override fun refreshBiometricStatus() = Unit
        override fun lock() = Unit
    }

    private class FakeOtherSettingsGateway : OtherSettingsGateway {
        private val settingsFlow = MutableStateFlow(OtherSettings())
        override val currentSettings: OtherSettings get() = settingsFlow.value
        override val settings: Flow<OtherSettings> get() = settingsFlow

        override suspend fun update(transform: (OtherSettings) -> OtherSettings) {
            settingsFlow.value = transform(settingsFlow.value)
        }
    }
}
