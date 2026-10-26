package io.legado.app.feature.bookimport

import androidx.compose.runtime.Stable
import io.legado.app.data.repository.ScannedBookFile
import io.legado.app.domain.gateway.ImportFileOutcome
import io.legado.app.utils.AlphanumComparator
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet

/** 确认列表条目；id 为文件 Uri 字符串（与本地书 bookUrl 同源）。 */
@Stable
data class ImportCandidate(
    val id: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val isOnBookShelf: Boolean,
)

@Stable
data class ImportFailure(
    val id: String,
    val name: String,
    val error: String,
)

@Stable
data class ImportSummary(
    val total: Int,
    val successCount: Int,
    val failures: ImmutableList<ImportFailure>,
) {
    val failureCount: Int get() = failures.size
    val allFailed: Boolean get() = total > 0 && successCount == 0
}

sealed interface ImportPhase {
    data object Entry : ImportPhase

    @Stable
    data class Scanning(val folderName: String) : ImportPhase

    @Stable
    data class Confirm(val folderName: String) : ImportPhase

    @Stable
    data class Importing(val total: Int) : ImportPhase

    /**
     * @param extractedToPrivateDir 本批导入含书籍压缩包，解压产物在应用私有目录
     * （卸载即删除），结果页需展示脚注说明。
     */
    @Stable
    data class Done(
        val summary: ImportSummary,
        val extractedToPrivateDir: Boolean = false,
    ) : ImportPhase
}

@Stable
data class LocalImportUiState(
    val phase: ImportPhase = ImportPhase.Entry,
    val candidates: ImmutableList<ImportCandidate> = persistentListOf(),
    val selectedIds: ImmutableSet<String> = persistentSetOf(),
    val importingDone: Int = 0,
    val fileNameRule: String = "",
) {
    val onShelfCount: Int get() = candidates.count { it.isOnBookShelf }
    val importableCount: Int get() = candidates.size - onShelfCount
    val selectedCount: Int get() = selectedIds.size
}

/** 本地导入纯逻辑：候选解析、选择运算、结果聚合。 */
object LocalImportLogic {

    /**
     * 扫描结果 → 候选列表。
     * 已上架按 uri 精确匹配（importFile 实际按 bookUrl 去重，按名匹配会误标同名不同路径文件）。
     */
    fun resolveCandidates(
        files: List<ScannedBookFile>,
        shelfUrls: Set<String>,
    ): ImmutableList<ImportCandidate> {
        return files
            .sortedWith(compareBy(AlphanumComparator) { it.name })
            .map { file ->
                ImportCandidate(
                    id = file.id,
                    name = file.name,
                    size = file.size,
                    lastModified = file.lastModified,
                    isOnBookShelf = file.id in shelfUrls,
                )
            }
            .toImmutableList()
    }

    /** 可勾选 id = 未上架条目。 */
    fun checkableIds(candidates: List<ImportCandidate>): List<String> {
        return candidates.filterNot { it.isOnBookShelf }.map { it.id }
    }

    fun toggle(selected: ImmutableSet<String>, id: String): ImmutableSet<String> {
        return if (id in selected) (selected - id).toImmutableSet() else (selected + id).toImmutableSet()
    }

    fun selectAll(
        candidates: List<ImportCandidate>,
        selected: ImmutableSet<String>,
    ): ImmutableSet<String> {
        return (checkableIds(candidates).toSet() + selected).toImmutableSet()
    }

    fun invert(
        candidates: List<ImportCandidate>,
        selected: ImmutableSet<String>,
    ): ImmutableSet<String> {
        val checkable = checkableIds(candidates).toSet()
        return (checkable - selected).toImmutableSet()
    }

    fun summarize(outcomes: List<ImportFileOutcome>): ImportSummary {
        val failures = outcomes.filter { it.error != null }
            .map {
                ImportFailure(
                    id = it.id,
                    name = it.name.ifBlank { it.id },
                    error = it.error!!,
                )
            }
            .toImmutableList()
        return ImportSummary(
            total = outcomes.size,
            successCount = outcomes.size - failures.size,
            failures = failures,
        )
    }
}
