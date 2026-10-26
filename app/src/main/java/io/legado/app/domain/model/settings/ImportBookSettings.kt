package io.legado.app.domain.model.settings

data class ImportBookSettings(
    val bookImportFileName: String? = null,
    val remoteServerId: Long = 0L,
)
