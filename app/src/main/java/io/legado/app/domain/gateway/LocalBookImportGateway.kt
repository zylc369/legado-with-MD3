package io.legado.app.domain.gateway

import android.net.Uri
import kotlinx.coroutines.flow.Flow

/** 单个导入条目的结果：error 为空即成功。 */
data class ImportFileOutcome(
    val id: String,
    val name: String,
    val error: String?,
)

/**
 * 本地书籍导入执行边界（导入、漫画目录导入、书架本地书 uri 集）。
 *
 * 实现：`data/repository/BookImportRepository`。
 */
interface LocalBookImportGateway {
    /** 书架上本地书的 bookUrl 集合（用于已上架精确匹配）。 */
    fun flowLocalBookUrls(): Flow<Set<String>>

    /**
     * 逐文件导入并流出结果；调用方收集以更新进度。
     * 单文件失败不中断批次，协程取消正常传播。
     */
    fun importUrisFlow(uris: List<Uri>): Flow<ImportFileOutcome>

    /** 将所选目录整目录导入为一本本地漫画。 */
    suspend fun importMangaDirectory(treeUri: Uri)
}
