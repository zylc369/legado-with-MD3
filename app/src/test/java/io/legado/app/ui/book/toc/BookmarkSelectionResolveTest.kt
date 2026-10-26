package io.legado.app.ui.book.toc

import io.legado.app.data.entities.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书签多选删除的解析契约：按**完整**清单解析选中 id。
 *
 * 搜索过滤只影响展示，不得缩小删除范围；被过滤隐藏的已选项同样会被删除。
 */
class BookmarkSelectionResolveTest {

    private fun item(id: Long, content: String = "c$id") = TocBookmarkItemUi(
        id = id,
        chapterIndex = 0,
        chapterPos = 0,
        content = content,
        chapterName = "章节",
        isDur = false,
        raw = Bookmark(time = id, content = content),
    )

    @Test
    fun `解析包含被搜索过滤隐藏的已选书签`() {
        val all = listOf(item(1), item(2), item(3))
        // 假设当前搜索只匹配到 2，但用户之前选了 1 和 3
        val resolved = resolveSelectedBookmarks(all, selectedIds = setOf(1L, 2L, 3L))
        assertEquals(listOf(1L, 2L, 3L), resolved.map { it.id })
    }

    @Test
    fun `未知 id 被忽略`() {
        val all = listOf(item(1))
        val resolved = resolveSelectedBookmarks(all, selectedIds = setOf(1L, 99L))
        assertEquals(listOf(1L), resolved.map { it.id })
    }

    @Test
    fun `未选中任何项时结果为空`() {
        val all = listOf(item(1), item(2))
        assertTrue(resolveSelectedBookmarks(all, emptySet()).isEmpty())
    }
}
