package io.legado.app.data.repository

import io.legado.app.data.dao.BookDao
import io.legado.app.domain.gateway.PrivateContentGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 私密书籍标记的数据实现。
 *
 * 公开/私密的过滤全部在 SQL 侧（BookDao.PUBLIC_BOOK_FILTER 与 isPrivate 查询）完成，
 * 这里只承载标记读写与判定入口。
 */
class PrivateContentRepository(
    private val bookDao: BookDao,
) : PrivateContentGateway {

    override suspend fun isBookPrivate(bookUrl: String): Boolean =
        withContext(Dispatchers.IO) { bookDao.isBookPrivate(bookUrl) }

    override suspend fun isBookPrivateByName(name: String, author: String): Boolean =
        withContext(Dispatchers.IO) { bookDao.isBookPrivateByName(name, author) }

    override suspend fun setBooksPrivate(bookUrls: Set<String>, isPrivate: Boolean) {
        if (bookUrls.isEmpty()) return
        withContext(Dispatchers.IO) { bookDao.setBooksPrivate(bookUrls, isPrivate) }
    }

    override fun flowPrivateBookUrls(): Flow<Set<String>> = bookDao.flowPrivateBookUrls()
        .map { it.toSet() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
}
