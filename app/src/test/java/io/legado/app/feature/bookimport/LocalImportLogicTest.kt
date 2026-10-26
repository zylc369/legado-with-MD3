package io.legado.app.feature.bookimport

import io.legado.app.data.repository.ScannedBookFile
import io.legado.app.domain.gateway.ImportFileOutcome
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImportLogicTest {

    private fun scanned(id: String, name: String = id) =
        ScannedBookFile(id = id, name = name, size = 1L, lastModified = 0L)

    @Test
    fun `已上架按 uri 精确匹配`() {
        val files = listOf(scanned("content://f/1"), scanned("content://f/2"))
        val candidates = LocalImportLogic.resolveCandidates(
            files,
            shelfUrls = setOf("content://f/1"),
        )
        assertTrue(candidates[0].isOnBookShelf)
        assertFalse(candidates[1].isOnBookShelf)
    }

    @Test
    fun `同名不同路径不误标已上架`() {
        // 旧实现按 originName 匹配会误标；importFile 实际按 bookUrl 去重
        val files = listOf(
            scanned(id = "content://a/书.txt", name = "书.txt"),
            scanned(id = "content://b/书.txt", name = "书.txt"),
        )
        val candidates = LocalImportLogic.resolveCandidates(
            files,
            shelfUrls = setOf("content://a/书.txt"),
        )
        assertTrue(candidates[0].isOnBookShelf)
        assertFalse(candidates[1].isOnBookShelf)
    }

    @Test
    fun `候选按自然数字顺序排序`() {
        val files = listOf(
            scanned("u1", "第10章.txt"),
            scanned("u2", "第2章.txt"),
            scanned("u3", "第1章.txt"),
        )
        val names = LocalImportLogic.resolveCandidates(files, emptySet()).map { it.name }
        assertEquals(listOf("第1章.txt", "第2章.txt", "第10章.txt"), names)
    }

    @Test
    fun `可勾选项排除已上架`() {
        val candidates = LocalImportLogic.resolveCandidates(
            listOf(scanned("a"), scanned("b"), scanned("c")),
            shelfUrls = setOf("b"),
        )
        assertEquals(listOf("a", "c"), LocalImportLogic.checkableIds(candidates))
    }

    @Test
    fun `全选等于全部可勾选项`() {
        val candidates = LocalImportLogic.resolveCandidates(
            listOf(scanned("a"), scanned("b"), scanned("c")),
            shelfUrls = setOf("b"),
        )
        val selected = LocalImportLogic.selectAll(candidates, persistentSetOf("a"))
        assertEquals(setOf("a", "c"), selected.toSet())
    }

    @Test
    fun `反选等于可勾选项减去已选`() {
        val candidates = LocalImportLogic.resolveCandidates(
            listOf(scanned("a"), scanned("b"), scanned("c"), scanned("d")),
            shelfUrls = setOf("b"),
        )
        val selected = LocalImportLogic.invert(candidates, persistentSetOf("a", "d"))
        assertEquals(setOf("c"), selected.toSet())
    }

    @Test
    fun `toggle 增删选择`() {
        val selected = persistentSetOf("a")
        assertEquals(setOf("a", "b"), LocalImportLogic.toggle(selected, "b").toSet())
        assertEquals(emptySet<String>(), LocalImportLogic.toggle(selected, "a").toSet())
    }

    @Test
    fun `结果聚合全成功`() {
        val outcomes = listOf(
            ImportFileOutcome("u1", "a.txt", null),
            ImportFileOutcome("u2", "b.txt", null),
        )
        val summary = LocalImportLogic.summarize(outcomes)
        assertEquals(2, summary.total)
        assertEquals(2, summary.successCount)
        assertEquals(0, summary.failureCount)
        assertFalse(summary.allFailed)
    }

    @Test
    fun `结果聚合部分失败保留明细`() {
        val outcomes = listOf(
            ImportFileOutcome("u1", "a.txt", null),
            ImportFileOutcome("u2", "b.zip", "解压失败"),
            ImportFileOutcome("u3", "c.epub", "文件为空"),
        )
        val summary = LocalImportLogic.summarize(outcomes)
        assertEquals(1, summary.successCount)
        assertEquals(2, summary.failureCount)
        assertEquals(
            listOf("b.zip" to "解压失败", "c.epub" to "文件为空"),
            summary.failures.map { it.name to it.error },
        )
    }

    @Test
    fun `结果聚合全部失败`() {
        val outcomes = listOf(ImportFileOutcome("u1", "a.txt", "err"))
        val summary = LocalImportLogic.summarize(outcomes)
        assertTrue(summary.allFailed)
        assertEquals(0, summary.successCount)
    }

    @Test
    fun `结果聚合空名单`() {
        val summary = LocalImportLogic.summarize(emptyList())
        assertEquals(0, summary.total)
        assertFalse(summary.allFailed)
    }

    @Test
    fun `失败条目名称回退为 uri`() {
        val summary = LocalImportLogic.summarize(
            listOf(ImportFileOutcome("content://x/1", "", "读取失败")),
            )
        assertEquals("content://x/1", summary.failures.first().name)
    }

    @Test
    fun `同名同错误的失败条目保持唯一 key`() {
        val summary = LocalImportLogic.summarize(
            listOf(
                ImportFileOutcome("content://a/第1章.txt", "第1章.txt", "空文件"),
                ImportFileOutcome("content://b/第1章.txt", "第1章.txt", "空文件"),
            ),
        )
        assertEquals(2, summary.failures.size)
        assertEquals(2, summary.failures.map { it.id }.toSet().size)
    }

    @Test
    fun `空扫描产出空候选`() {
        val candidates = LocalImportLogic.resolveCandidates(emptyList(), emptySet())
        assertEquals(persistentListOf<ImportCandidate>(), candidates)
        assertEquals(0, candidates.size)
    }
}
