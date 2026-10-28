package io.legado.app.domain.model

import androidx.compose.runtime.Stable

/**
 * 生物识别能力的显式建模。
 *
 * 平台能力不可用时必须如实上报，不得用"可用"伪造支持：验证页与设置页据此决定
 * 是弹出系统验证框、还是直接回退到应用内密码输入。
 */
enum class PrivateBiometricStatus {
    /** 设备有可用生物识别且已录入凭据 */
    Available,

    /** 有硬件但尚未录入指纹/人脸 */
    NotEnrolled,

    /** 设备没有生物识别硬件 */
    NoHardware,

    /** 硬件暂时不可用（系统策略、维修模式等） */
    Unavailable,

    /** 系统版本过低，不能做加密绑定的生物解锁 */
    UnsupportedSystem
}

/**
 * 私密模式访问状态。
 *
 * 唯一的解锁语义是「私密模式会话」（[isSessionUnlocked]）：
 * - 只存活于当前进程，进程重启即回到锁定态；
 * - 主动退出私密模式立即回到锁定态；
 * - 应用切后台超过宽限期（见 [io.legado.app.domain.model.settings.PrivateAccessSettings]）
 *   后回到锁定态。
 * 会话解锁期间，私密书籍可读、不再逐本/逐分组验证。
 */
@Stable
data class PrivateAccessState(
    val hasPassword: Boolean = false,
    val isSessionUnlocked: Boolean = false,
    val biometricStatus: PrivateBiometricStatus = PrivateBiometricStatus.Unavailable,
    val biometricEnabled: Boolean = false,
) {
    /** 是否可以直接弹系统生物验证框（而不是密码输入） */
    val canUseBiometricShortcut: Boolean
        get() = biometricEnabled && biometricStatus == PrivateBiometricStatus.Available
}
