package io.legado.app.ui.book.read

import io.legado.app.feature.reader.core.model.ReaderPage
import io.legado.app.feature.reader.core.navigation.ReaderChapterPaginationSnapshot
import io.legado.app.feature.reader.core.navigation.ReaderPageNavigator

/**
 * 可见页起点解析。
 *
 * [directReaderPages] 是跨章排序的全局窗口，页下标不是章内下标；分页快照的
 * pageStarts 只按章内页索引存储。两者必须先经章节定位换算，不能在邻章页
 * 还在窗口里时直接把全局下标传给当前章快照。
 */
object ReadAloudVisiblePage {

    fun pageStart(
        pages: List<ReaderPage>,
        pageIndex: Int,
        currentChapterIndex: Int,
        pagination: (Int) -> ReaderChapterPaginationSnapshot?,
    ): Int? {
        val page = pages.getOrNull(pageIndex) ?: return null
        if (page.isPlaceholder) return null
        val position = ReaderPageNavigator.chapterPosition(pages, pageIndex) ?: return null
        if (position.chapterIndex != currentChapterIndex) return null
        return pagination(position.chapterIndex)?.pageStart(position.pageIndex)
    }
}
