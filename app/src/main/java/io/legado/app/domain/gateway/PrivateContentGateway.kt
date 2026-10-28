package io.legado.app.domain.gateway

import kotlinx.coroutines.flow.Flow

/**
 * 私密书籍标记。
 *
 * 新模型只有"单本私密"一种机制：标记后书籍从所有公开查询（书架列表/分组/计数/搜索）
 * 中消失，只在私密模式里可见；私密分组机制已删除（存量数据由一次性迁移转为单本私密）。
 */
interface PrivateContentGateway {

    /** 这本书是否被标记为私密 */
    suspend fun isBookPrivate(bookUrl: String): Boolean

    /**
     * 只有书名 + 作者时的判定入口。
     *
     * 阅读记录这类表里根本没存 bookUrl（书签也是），只能按名+作者反查一次；
     * 反查不到按"与私密无关"处理——书都没了，也就没有可泄露的内容。
     */
    suspend fun isBookPrivateByName(name: String, author: String): Boolean

    /** 批量设置/取消书籍的私密标记 */
    suspend fun setBooksPrivate(bookUrls: Set<String>, isPrivate: Boolean)

    /** 被标记为私密的书籍 url（私密模式书架数据源） */
    fun flowPrivateBookUrls(): Flow<Set<String>>
}
