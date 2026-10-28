package io.legado.app.help.storage

import io.legado.app.help.config.LocalConfig

/**
 * 备份加密密钥的调用作用域。
 *
 * 密码按"备份地址"记忆（见 BackupPasswordStore），加密/解密时必须用**对应地址**的密码，
 * 而不是全局唯一密码。这里给 Backup/Restore 提供一个可嵌套的覆盖值：
 * - 有覆盖值（正常备份/恢复入口）：用该地址的密码；
 * - 无覆盖值（旧调用、自动备份）：回退到全局 `LocalConfig.password`，保持兼容。
 */
object BackupKey {

    @Volatile
    private var override: String? = null

    val currentPassword: String
        get() = override ?: (LocalConfig.password ?: "")

    /** null 表示"沿用外层/默认"，不覆盖 */
    suspend fun <T> withPassword(password: String?, block: suspend () -> T): T {
        if (password == null) return block()
        val previous = override
        override = password
        return try {
            block()
        } finally {
            override = previous
        }
    }
}
