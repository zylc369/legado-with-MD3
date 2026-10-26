package io.legado.app.data.repository

import android.net.Uri
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.utils.FileDoc
import io.legado.app.utils.list
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 扫描结果条目：id 为文件 Uri 字符串（与本地书的 bookUrl 同源，用于已上架精确匹配）。
 */
data class ScannedBookFile(
    val id: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
)

interface ImportFolderScanner {
    /**
     * 递归扫描目录树，返回全部书籍/压缩包文件。
     * 跳过隐藏文件与隐藏目录；不可读子目录跳过该子树；支持协程取消。
     */
    suspend fun scan(treeUri: Uri): List<ScannedBookFile>
}

class DefaultImportFolderScanner : ImportFolderScanner {
    override suspend fun scan(treeUri: Uri): List<ScannedBookFile> {
        return withContext(Dispatchers.IO) {
            val root = FileDoc.fromUri(treeUri, true)
            scanImportFolder(root) { dir -> dir.list() }
                .map { doc ->
                    ScannedBookFile(
                        id = doc.toString(),
                        name = doc.name,
                        size = doc.size,
                        lastModified = doc.lastModified,
                    )
                }
        }
    }
}

/**
 * 纯算法：listFn 注入目录列举能力以便测试。
 * BFS 顺序稳定；visited 防御伪造的目录环。
 */
internal suspend fun scanImportFolder(
    root: FileDoc,
    listFn: suspend (FileDoc) -> List<FileDoc>?,
): List<FileDoc> {
    val visited = HashSet<String>()
    val pending = ArrayDeque<FileDoc>()
    val found = ArrayList<FileDoc>()
    visited.add(root.toString())
    pending.add(root)

    while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val dir = pending.removeFirst()
        val children = try {
            listFn(dir)
        } catch (e: SecurityException) {
            null
        } ?: continue

        for (child in children) {
            val name = child.name
            when {
                name.startsWith(".") -> continue
                child.isDir -> if (visited.add(child.toString())) pending.add(child)
                name.matches(bookFileRegex) || name.matches(archiveFileRegex) -> found.add(child)
            }
        }
    }
    return found
}
