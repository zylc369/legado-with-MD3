package io.legado.app.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 私密模式会话状态（新模型）。
 *
 * 解锁语义只有一个：会话（isSessionUnlocked）。它由进入私密模式时的验证置位，
 * 由退出私密模式 / 切后台宽限期到期 / 进程重启收回——最后一种由"状态只存内存"天然保证。
 */
class PrivateAccessTest {

    @Test
    fun `biometric shortcut requires both enable and availability`() {
        assertTrue(
            PrivateAccessState(
                biometricEnabled = true,
                biometricStatus = PrivateBiometricStatus.Available
            ).canUseBiometricShortcut
        )
        // 开关亮着但设备不可用 / 未录入 / 无硬件：都不能走生物路径
        assertFalse(
            PrivateAccessState(
                biometricEnabled = true,
                biometricStatus = PrivateBiometricStatus.NotEnrolled
            ).canUseBiometricShortcut
        )
        assertFalse(
            PrivateAccessState(
                biometricEnabled = false,
                biometricStatus = PrivateBiometricStatus.Available
            ).canUseBiometricShortcut
        )
    }

    @Test
    fun `default state is locked and credential-less`() {
        val state = PrivateAccessState()
        assertFalse(state.hasPassword)
        assertFalse(state.isSessionUnlocked)
        assertFalse(state.canUseBiometricShortcut)
    }
}
