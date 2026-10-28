package io.legado.app.domain.gateway

import io.legado.app.domain.model.PrivateAccessState
import io.legado.app.domain.model.settings.PrivateAccessSettings
import kotlinx.coroutines.flow.Flow

/**
 * 私密模式解锁凭据与会话状态。
 *
 * 本地密码是唯一权威凭据；生物识别只是"免手输密码"的快捷方式，生物验证成功后
 * 仍然是用本地密码完成解锁（见 help/security/PrivatePasswordCipher）。
 *
 * 契约里不出现 Keystore / Cipher / Activity 等平台类型：平台侧的弹框与解密由
 * help/security 下的组件完成，成功后仍回到 [unlockSession] 这条唯一入口。
 */
interface PrivateAccessGateway {

    val state: Flow<PrivateAccessState>

    val settings: Flow<PrivateAccessSettings>

    /** 与普通设置网关一致：一次 copy 原子提交相关字段 */
    suspend fun updateSettings(transform: (PrivateAccessSettings) -> PrivateAccessSettings)

    /**
     * 验证本地密码并解锁私密模式会话；成功返回 true，失败返回 false（调用方负责提示）。
     * 生物识别解出的密码也走这里。
     */
    suspend fun unlockSession(password: String): Boolean

    /**
     * 只校验密码，**不授予任何访问**。
     *
     * 给"验证后才能进入"的场景用（隐私设置页）：这类场景要的是"证明你是本人"，
     * 而不是"解锁私密内容"。
     */
    suspend fun verifyPasswordOnly(password: String): Boolean

    /** 设置/修改/清除私密模式密码（与备份密码解耦） */
    suspend fun setPassword(password: String?)

    /** 忘记密码：清除私密密码与全部派生凭据（私密标记由调用方一并清除） */
    suspend fun resetPassword()

    /** 主动回到锁定态：退出私密模式 / 切后台宽限期到期都调用这里 */
    fun lock()

    /** 同步判定私密会话此刻是否已解锁（AI 工具等同步路径用，不做 IO） */
    fun isSessionUnlockedNow(): Boolean

    /**
     * 关闭生物快捷：清空信封并把开关落成关闭。
     *
     * 开启**不**在这里做——信封必须由一次真实的生物认证产出
     * （见 help/security/BiometricEnvelopeWriter），认证成功后经 [storeBiometricEnvelope] 落盘。
     * 把"开启"拆成两步，是为了让"信封究竟有没有写成"有唯一的事实来源。
     */
    suspend fun disableBiometricShortcut()

    /**
     * 保存生物认证后产出的信封。
     *
     * 只有真正写入成功才置位开关并返回 true；传入空信封或设备能力已不可用时返回 false。
     * 绝不留下"开关亮着、信封却是空的"这种假支持——那会让生物路径每次都在启动时静默回退。
     */
    suspend fun storeBiometricEnvelope(ciphertext: String, iv: String): Boolean

    /** 重新探测设备生物识别能力（从设置页返回、录入新指纹后调用）。 */
    fun refreshBiometricStatus()
}
