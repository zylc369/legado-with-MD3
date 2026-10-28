package io.legado.app.data.repository

import io.legado.app.data.local.preferences.LocalPreferencesKeys
import io.legado.app.domain.model.settings.BackupSettings
import io.legado.app.help.config.AppConfigStore
import io.legado.app.utils.GSON

/**
 * 备份密码按"备份地址"记忆。
 *
 * 地址：
 * - 本地/混合：备份目录 path（"both" 也会落到该目录）
 * - WebDav：URL + 目录 + 账号
 *
 * 同一地址反复备份一定使用同一密码，否则旧备份恢复不出来；换地址时才需要新密码。
 * 历史用户只有全局 `LocalConfig.password`：首次备份到某地址时自动沿用它并记住，
 * 之后即可独立修改。明文存储与既有全局备份密码同级别（同一 DataStore）。
 */
object BackupPasswordStore {

    const val MODE_LOCAL = "local"
    const val MODE_WEBDAV = "webdav"
    const val MODE_BOTH = "both"

    /** 本地恢复的文件来源未知：使用固定"最近一次"槽位只做预填建议，不用于自动解密 */
    const val KEY_LOCAL_RESTORE_SUGGESTION = "restore|local"

    fun addressKey(
        mode: String,
        settings: BackupSettings,
        localPath: String? = null,
    ): String {
        val webdavAddress = "webdav|${settings.webDavUrl}|${settings.webDavDir}|" +
                settings.webDavAccount
        return when (mode) {
            MODE_WEBDAV -> webdavAddress
            MODE_BOTH -> if (settings.webDavUrl.isNotBlank()) {
                webdavAddress
            } else {
                "local|${localPath ?: settings.backupPath.orEmpty()}"
            }

            else -> "local|${localPath ?: settings.backupPath.orEmpty()}"
        }
    }

    fun passwordFor(addressKey: String): String? =
        readMap()[addressKey]?.takeIf { it.isNotEmpty() }

    suspend fun remember(addressKey: String, password: String) {
        val map = readMap().toMutableMap()
        map[addressKey] = password
        writeMap(map)
    }

    /** 旧版全局备份密码：仅作为迁移默认值与恢复时的预填建议 */
    fun legacyDefault(): String? = AppConfigStore
        .getString(LocalPreferencesKeys.PASSWORD.name)
        ?.takeIf { it.isNotBlank() }

    /** 恢复预填：优先记住的"最近一次"，其次旧版全局密码 */
    fun suggestedRestorePassword(): String? =
        passwordFor(KEY_LOCAL_RESTORE_SUGGESTION) ?: legacyDefault()

    suspend fun rememberRestoreSuggestion(password: String) {
        remember(KEY_LOCAL_RESTORE_SUGGESTION, password)
    }

    private fun readMap(): Map<String, String> {
        val json = AppConfigStore.getString(
            LocalPreferencesKeys.BACKUP_ADDRESS_PASSWORDS.name
        )
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            GSON.fromJson<Map<String, String>>(json, Map::class.java)?.mapValues {
                it.value?.toString().orEmpty()
            } ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    private suspend fun writeMap(map: Map<String, String>) {
        AppConfigStore.putAllAndAwait(
            mapOf(
                LocalPreferencesKeys.BACKUP_ADDRESS_PASSWORDS.name to
                        GSON.toJson(map)
            )
        )
    }
}
