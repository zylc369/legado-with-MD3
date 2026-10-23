package io.legado.app.ui.book.read

import io.legado.app.feature.reader.core.model.ReaderElement
import io.legado.app.feature.reader.core.model.ReaderPage
import io.legado.app.feature.reader.core.model.ReaderPageId
import io.legado.app.feature.reader.core.model.ReaderRect
import io.legado.app.feature.reader.core.model.ReaderTextStyle
import io.legado.app.feature.reader.core.navigation.ReaderChapterPaginationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadAloudVisiblePageTest {

    private fun page(index: Int, chapterIndex: Int, placeholder: Boolean = false) = ReaderPage(
        id = ReaderPageId(chapterIndex, index),
        chapterTitle = "",
        text = "字",
        widthPx = 100,
        heightPx = 100,
        contentTopPx = 0f,
        contentBottomPx = 100f,
        elements = listOf(
            ReaderElement.Text(
                ReaderRect(0f, 0f, 10f, 10f),
                8f,
                "字",
                ReaderTextStyle(0, 10f),
                false,
                false,
                chapterPosition = index * 20,
            )
        ),
        revision = 1,
        isPlaceholder = placeholder,
    )

    private fun snapshot(chapterIndex: Int, starts: List<Int>) = ReaderChapterPaginationSnapshot(
        chapterIndex = chapterIndex,
        pageStarts = starts,
        contentEnd = starts.lastOrNull() ?: 0,
        generation = 1,
    )

    @Test
    fun `global page index is converted to chapter local index`() {
        val pages = listOf(
            page(0, chapterIndex = 3),
            page(1, chapterIndex = 3),
            page(0, chapterIndex = 4),
            page(1, chapterIndex = 4),
            page(2, chapterIndex = 4),
        )
        val snapshots = mapOf(
            3 to snapshot(3, listOf(100, 200)),
            4 to snapshot(4, listOf(0, 50, 100)),
        )

        assertEquals(
            50,
            ReadAloudVisiblePage.pageStart(pages, pageIndex = 3, currentChapterIndex = 4) {
                snapshots[it]
            },
        )
    }

    @Test
    fun `page from neighboring chapter is rejected`() {
        val pages = listOf(page(0, chapterIndex = 3), page(0, chapterIndex = 4))
        val snapshots = mapOf(3 to snapshot(3, listOf(0)), 4 to snapshot(4, listOf(0)))

        assertNull(
            ReadAloudVisiblePage.pageStart(pages, pageIndex = 0, currentChapterIndex = 4) {
                snapshots[it]
            }
        )
    }

    @Test
    fun `missing snapshot or out of range page returns null`() {
        val pages = listOf(page(0, chapterIndex = 4))

        assertNull(
            ReadAloudVisiblePage.pageStart(pages, pageIndex = 0, currentChapterIndex = 4) { null }
        )
        assertNull(
            ReadAloudVisiblePage.pageStart(emptyList(), pageIndex = 0, currentChapterIndex = 4) {
                snapshot(4, listOf(0))
            }
        )
    }

    @Test
    fun `placeholder page has no readable start`() {
        val pages = listOf(page(0, chapterIndex = 4, placeholder = true))
        assertNull(
            ReadAloudVisiblePage.pageStart(pages, pageIndex = 0, currentChapterIndex = 4) {
                snapshot(4, listOf(0))
            }
        )
    }
}
