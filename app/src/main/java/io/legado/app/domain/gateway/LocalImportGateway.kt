package io.legado.app.domain.gateway

import android.net.Uri

/**
 * 本地导入的 SAF 平台能力：目录校验/持久化、文件读权限持久化、
 * 书籍压缩包识别。
 *
 * 实现：`data/repository/LocalImportGatewayImpl`。
 */
interface LocalImportGateway {
    /**
     * 规范化 tree URI 并校验可枚举；校验通过才持久化授权。
     * 失败返回原因（如失效授权、Provider 拒绝），调用方不得保存该 URI。
     */
    suspend fun prepareImportFolder(uri: Uri): FolderPrepareResult

    /** 为选中的书籍文件持久化读权限。 */
    suspend fun persistFileReadPermissions(uris: List<Uri>)

    /**
     * 所给文件中是否包含会被解压到私有目录的“书籍压缩包”
     * （漫画压缩包整包直接导入，不在此列）。
     */
    suspend fun hasBookArchive(uris: List<Uri>): Boolean
}

data class PreparedFolder(
    val treeUri: Uri,
    val displayName: String,
)

sealed interface FolderPrepareResult {
    data class Ok(val folder: PreparedFolder) : FolderPrepareResult
    data class Failed(val reason: String) : FolderPrepareResult
}
