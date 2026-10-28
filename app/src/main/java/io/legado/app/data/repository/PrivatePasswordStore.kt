package io.legado.app.data.repository

import io.legado.app.data.local.preferences.LocalPreferencesKeys
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.security.PrivatePasswordCipher

/**
 * 私密模式密码的读写与校验（单一事实来源）。
 *
 * 与 `PASSWORD` 键彻底解耦：那个键属于"备份敏感信息加密"的旧本地密码，两个功能共用
 * 会让改一个密码悄悄改掉另一个（也会让旧备份密码让私密模式误以为已设密码）。
 *
 * 明文私密密码仍要保存：生物快捷解锁的信封里封装的正是它（认证后解出再校验）。
 * 校验值走 PBKDF2 + 随机 salt；明文只作为"最近一次保存的密码"的权威来源，
 * 在历史状态不一致时用于自愈校验值——保证"在设置页设置的密码一定能用"。
 */
object PrivatePasswordStore {

    fun hasPassword(): Boolean =
        !AppConfigStore.getString(LocalPreferencesKeys.PRIVATE_PASSWORD.name).isNullOrEmpty()

    /** 设置/修改/清除密码；密码变化时作废生物信封（旧信封里是旧密码） */
    suspend fun setPassword(password: String?) {
        val normalized = password.orEmpty()
        if (normalized.isEmpty()) {
            reset()
            return
        }
        val previous = AppConfigStore.getString(LocalPreferencesKeys.PRIVATE_PASSWORD.name).orEmpty()
        val salt = PrivatePasswordCipher.generateSalt()
        val verifier = PrivatePasswordCipher.deriveVerifier(normalized, salt).orEmpty()
        val values = mutableMapOf<String, Any?>(
            LocalPreferencesKeys.PRIVATE_PASSWORD.name to normalized,
            LocalPreferencesKeys.PRIVATE_PASSWORD_SALT.name to salt,
            LocalPreferencesKeys.PRIVATE_PASSWORD_VERIFIER.name to verifier,
        )
        if (previous != normalized) {
            values[LocalPreferencesKeys.PRIVATE_BIOMETRIC_ENVELOPE.name] = ""
            values[LocalPreferencesKeys.PRIVATE_BIOMETRIC_IV.name] = ""
            values[LocalPreferencesKeys.PRIVATE_BIOMETRIC_ENABLED.name] = false
        }
        AppConfigStore.putAllAndAwait(values)
    }

    /**
     * 校验密码。
     *
     * 明文是"最近一次保存的密码"的权威来源：若校验值与明文对不上（历史版本共用键、
     * 中途改密方式不一致等），先用明文自愈校验值，再按密码学校验——
     * 旧密码不会因为校验值残留而继续有效，设置页保存的密码则一定可用。
     */
    suspend fun verify(password: String): Boolean {
        if (password.isEmpty()) return false
        val stored = AppConfigStore.getString(LocalPreferencesKeys.PRIVATE_PASSWORD.name).orEmpty()
        if (stored.isNotEmpty() && !credentialMatches(stored)) {
            heal(stored)
        }
        if (credentialMatches(password)) return true
        // 没有任何校验值的历史状态：允许与明文比较（常量时间）
        val salt = AppConfigStore.getString(LocalPreferencesKeys.PRIVATE_PASSWORD_SALT.name).orEmpty()
        val verifier = AppConfigStore.getString(
            LocalPreferencesKeys.PRIVATE_PASSWORD_VERIFIER.name
        ).orEmpty()
        if (salt.isEmpty() || verifier.isEmpty()) {
            return stored.isNotEmpty() && java.security.MessageDigest.isEqual(
                stored.toByteArray(),
                password.toByteArray()
            )
        }
        return false
    }

    private fun credentialMatches(password: String): Boolean {
        val salt = AppConfigStore.getString(LocalPreferencesKeys.PRIVATE_PASSWORD_SALT.name).orEmpty()
        val verifier = AppConfigStore.getString(
            LocalPreferencesKeys.PRIVATE_PASSWORD_VERIFIER.name
        ).orEmpty()
        if (salt.isEmpty() || verifier.isEmpty()) return false
        return PrivatePasswordCipher.verify(password, salt, verifier)
    }

    private suspend fun heal(password: String) {
        val salt = PrivatePasswordCipher.generateSalt()
        val verifier = PrivatePasswordCipher.deriveVerifier(password, salt).orEmpty()
        AppConfigStore.putAllAndAwait(
            mapOf(
                LocalPreferencesKeys.PRIVATE_PASSWORD_SALT.name to salt,
                LocalPreferencesKeys.PRIVATE_PASSWORD_VERIFIER.name to verifier,
            )
        )
    }

    /** 清除私密密码与派生凭据（不动备份密码 PASSWORD） */
    suspend fun reset() {
        AppConfigStore.putAllAndAwait(
            mapOf(
                LocalPreferencesKeys.PRIVATE_PASSWORD.name to "",
                LocalPreferencesKeys.PRIVATE_PASSWORD_SALT.name to "",
                LocalPreferencesKeys.PRIVATE_PASSWORD_VERIFIER.name to "",
                LocalPreferencesKeys.PRIVATE_BIOMETRIC_ENVELOPE.name to "",
                LocalPreferencesKeys.PRIVATE_BIOMETRIC_IV.name to "",
                LocalPreferencesKeys.PRIVATE_BIOMETRIC_ENABLED.name to false,
            )
        )
    }

    /**
     * 旧版共用 PASSWORD 键 → 私密专用键 的一次性迁移。
     *
     * 只有"私密功能确实用过"（校验值存在）才迁移，避免把纯备份密码误当私密密码；
     * 迁移时以 PASSWORD（设置页最后保存的值）为准重建校验值，修复历史错位。
     */
    suspend fun migrateLegacyIfNeeded() {
        if (AppConfigStore.getBoolean(
                LocalPreferencesKeys.PRIVATE_PASSWORD_MIGRATED.name
            ) == true
        ) {
            return
        }
        val hasLegacyVerifier = !AppConfigStore.getString(
            LocalPreferencesKeys.PRIVATE_PASSWORD_SALT.name
        ).isNullOrEmpty()
        val legacyPassword =
            AppConfigStore.getString(LocalPreferencesKeys.PASSWORD.name).orEmpty()
        if (!hasPassword() && hasLegacyVerifier && legacyPassword.isNotEmpty()) {
            val salt = PrivatePasswordCipher.generateSalt()
            val verifier = PrivatePasswordCipher.deriveVerifier(legacyPassword, salt).orEmpty()
            AppConfigStore.putAllAndAwait(
                mapOf(
                    LocalPreferencesKeys.PRIVATE_PASSWORD.name to legacyPassword,
                    LocalPreferencesKeys.PRIVATE_PASSWORD_SALT.name to salt,
                    LocalPreferencesKeys.PRIVATE_PASSWORD_VERIFIER.name to verifier,
                )
            )
        }
        AppConfigStore.putAllAndAwait(
            mapOf(LocalPreferencesKeys.PRIVATE_PASSWORD_MIGRATED.name to true)
        )
    }
}
