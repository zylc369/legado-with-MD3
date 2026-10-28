package io.legado.app.ui.widget.components.privacy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.domain.gateway.PrivateAccessGateway
import io.legado.app.domain.gateway.PrivateContentGateway
import io.legado.app.domain.model.PrivateAccessState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * 列表页共用：这批书里**此刻需要脱敏**的是哪些。
 *
 * 新模型下私密书不进入公开查询，这里的职责是防"旁路列表"（书签、缓存管理、
 * 书架搜索提示等仍可能引用私密书）：私密且私密模式会话未解锁 → 遮住书名与封面。
 * 判定与阅读器闸门共用同一会话状态，不另立第二份。
 */
@Composable
fun rememberPrivateLockedBookUrls(bookUrls: List<String>): Set<String> {
    val privateContentGateway: PrivateContentGateway = koinInject()
    val privateAccessGateway: PrivateAccessGateway = koinInject()
    val access by privateAccessGateway.state.collectAsStateWithLifecycle(PrivateAccessState())

    val facts by produceState<Set<String>>(
        initialValue = emptySet(),
        key1 = bookUrls,
    ) {
        value = withContext(Dispatchers.IO) {
            bookUrls.distinct().filterTo(mutableSetOf()) {
                privateContentGateway.isBookPrivate(it)
            }
        }
    }

    return remember(bookUrls, facts, access) {
        if (access.isSessionUnlocked) emptySet() else facts
    }
}

/** 只有书名 + 作者的列表条目（阅读记录）的标识。 */
data class PrivateRecordKey(val name: String, val author: String)

/**
 * 与 [rememberPrivateLockedBookUrls] 同义，换成"按名+作者"的入口。
 *
 * 阅读记录、书签这类表里没有 bookUrl，只能反查 books；反查不到按"与私密无关"处理。
 */
@Composable
fun rememberPrivateLockedRecords(records: List<PrivateRecordKey>): Set<PrivateRecordKey> {
    val privateContentGateway: PrivateContentGateway = koinInject()
    val privateAccessGateway: PrivateAccessGateway = koinInject()
    val access by privateAccessGateway.state.collectAsStateWithLifecycle(PrivateAccessState())

    val facts by produceState<Set<PrivateRecordKey>>(
        initialValue = emptySet(),
        key1 = records,
    ) {
        value = withContext(Dispatchers.IO) {
            records.distinct().filterTo(mutableSetOf()) { key ->
                privateContentGateway.isBookPrivateByName(key.name, key.author)
            }
        }
    }

    return remember(records, facts, access) {
        if (access.isSessionUnlocked) emptySet() else facts
    }
}
