package io.legado.app.domain.gateway

interface BackupRestoreGateway {
    suspend fun backup(path: String?, mode: String, password: String? = null)
    suspend fun restoreLocal(uri: String, password: String? = null)
}
