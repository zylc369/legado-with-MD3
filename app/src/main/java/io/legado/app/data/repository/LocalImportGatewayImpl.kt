package io.legado.app.data.repository

import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.DocumentFile.fromTreeUri
import io.legado.app.domain.gateway.FolderPrepareResult
import io.legado.app.domain.gateway.LocalImportGateway
import io.legado.app.domain.gateway.PreparedFolder
import io.legado.app.utils.ArchiveKind
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.takePersistablePermissionSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

class LocalImportGatewayImpl : LocalImportGateway {

    override suspend fun prepareImportFolder(uri: Uri): FolderPrepareResult =
        withContext(Dispatchers.IO) {
            val normalized = normalizeFolderUri(uri)
            if (!probeListable(normalized)) {
                return@withContext FolderPrepareResult.Failed(
                    appCtx.getString(io.legado.app.R.string.import_folder_unreadable)
                )
            }
            normalized.takePersistablePermissionSafely(appCtx)
            FolderPrepareResult.Ok(
                PreparedFolder(normalized, resolveDisplayName(normalized))
            )
        }

    override suspend fun persistFileReadPermissions(uris: List<Uri>) {
        withContext(Dispatchers.IO) {
            uris.forEach { it.takePersistablePermissionSafely(appCtx) }
        }
    }

    override suspend fun hasBookArchive(uris: List<Uri>): Boolean {
        return withContext(Dispatchers.IO) {
            uris.any { uri ->
                runCatching {
                    val fileDoc = FileDoc.fromUri(uri, false)
                    if (!ArchiveUtils.isArchive(fileDoc.name)) return@runCatching false
                    val entries = ArchiveUtils.getArchiveFilesName(fileDoc)
                    entries.isNotEmpty() &&
                            ArchiveUtils.archiveKind(fileDoc.name, entries) ==
                            ArchiveKind.BookArchive
                }.getOrDefault(false)
            }
        }
    }

    private fun normalizeFolderUri(uri: Uri): Uri {
        if (!uri.isContentScheme()) return uri
        return runCatching {
            DocumentsContract.buildTreeDocumentUri(
                uri.authority,
                DocumentsContract.getTreeDocumentId(uri)
            )
        }.recoverCatching {
            DocumentsContract.buildTreeDocumentUri(
                uri.authority,
                DocumentsContract.getDocumentId(uri)
            )
        }.getOrDefault(uri)
    }

    /** 廉价探测：只发起 children query 不迭代，验证 Provider 接受该 tree 授权。 */
    private fun probeListable(uri: Uri): Boolean {
        return runCatching {
            if (uri.isContentScheme()) {
                val docUri = fromTreeUri(appCtx, uri)?.uri ?: return false
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    docUri,
                    DocumentsContract.getDocumentId(docUri)
                )
                appCtx.contentResolver.query(childrenUri, null, null, null, null)?.close()
                true
            } else {
                File(uri.path!!).isDirectory
            }
        }.getOrDefault(false)
    }

    private fun resolveDisplayName(uri: Uri): String {
        val docName = runCatching {
            if (uri.isContentScheme()) fromTreeUri(appCtx, uri)?.name else null
        }.getOrNull()
        docName?.takeIf { it.isNotBlank() }?.let { return it }
        if (!uri.isContentScheme()) return File(uri.path!!).name
        return runCatching {
            DocumentsContract.getTreeDocumentId(uri)
                .substringAfter(':')
                .ifBlank { null }
        }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: uri.toString()
    }
}
