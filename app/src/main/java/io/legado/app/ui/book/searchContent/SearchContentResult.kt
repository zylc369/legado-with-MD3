package io.legado.app.ui.book.searchContent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Singleton bus for passing search results from [SearchContentScreen] back to the reader.
 *
 * Uses [MutableSharedFlow] with `replay = 1` so the reader receives the result even
 * if it subscribes after the emission. 搜索叠层盖在阅读器上时阅读器保持组合（STARTED），
 * 收集器在此状态即可消费；replay 兜底组合尚未就绪或 Activity 重建恢复的场景。
 */
object SearchContentResult {

    sealed interface Event {
        val bookUrl: String
    }

    data class Result(
        override val bookUrl: String,
        val searchResults: List<SearchResult>,
        val index: Int,
        val query: String,
    ) : Event

    data class Clear(override val bookUrl: String) : Event

    private val _results = MutableSharedFlow<Event>(replay = 1)
    val results = _results.asSharedFlow()

    fun emitResult(result: Result) {
        _results.tryEmit(result)
    }

    fun clearResults(bookUrl: String) {
        _results.tryEmit(Clear(bookUrl))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun resetReplayCache() {
        _results.resetReplayCache()
    }
}
