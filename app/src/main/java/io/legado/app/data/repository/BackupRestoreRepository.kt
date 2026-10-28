package io.legado.app.data.repository

import androidx.core.net.toUri
import io.legado.app.domain.gateway.BackupRestoreGateway
import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.Restore
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class BackupRestoreRepository(
    private val settingsGateway: BackupSettingsGateway,
) : BackupRestoreGateway {

    override suspend fun backup(path: String?, mode: String, password: String?) {
        withContext(IO) {
            // 首页快捷备份等调用方不传密码：按目标地址解析（记住的 → 旧全局密码）
            val resolved = password ?: run {
                val settings = settingsGateway.currentSettings
                val key = BackupPasswordStore.addressKey(mode, settings, path)
                BackupPasswordStore.passwordFor(key) ?: BackupPasswordStore.legacyDefault()
            }
            Backup.backupLocked(appCtx, path, mode, resolved)
        }
    }

    override suspend fun restoreLocal(uri: String, password: String?) {
        withContext(IO) {
            // 本地备份来源地址未知：未显式传入时用"最近一次"预填值兜底
            val resolved = password ?: BackupPasswordStore.suggestedRestorePassword()
            Restore.restore(appCtx, uri.toUri(), resolved)
        }
    }
}
