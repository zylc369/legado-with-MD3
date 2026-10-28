package io.legado.app.domain.gateway

import io.legado.app.ui.main.bookshelf.BookShelfItem
import kotlinx.coroutines.flow.Flow

/**
 * 私密模式书架的数据源。
 *
 * 实现：`data/repository/BookRepository`（同一个 BookDao 投影，只是过滤方向相反）。
 */
interface PrivateShelfGateway {
    /** 私密模式书架：只有单本私密标记的书，按最近阅读排序 */
    fun flowPrivateBookShelf(): Flow<List<BookShelfItem>>
}
