package io.legado.app.domain.usecase

import io.legado.app.domain.gateway.BackupRestoreGateway

class BackupRestoreUseCase(
    private val gateway: BackupRestoreGateway,
) {
    suspend fun backup(path: String?, mode: String, password: String? = null) {
        gateway.backup(path, mode, password)
    }

    suspend fun restoreLocal(uri: String, password: String? = null) {
        gateway.restoreLocal(uri, password)
    }
}
