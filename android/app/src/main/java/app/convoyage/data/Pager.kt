package app.convoyage.data

import app.convoyage.api.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One page from the server: the items and the cursor for the next page (null = last page). */
data class PageOf<T>(val items: List<T>, val nextCursor: String?)

data class PagerState<T>(
    val items: List<T> = emptyList(),
    val nextCursor: String? = null,
    /** True once the first page has arrived. */
    val loadedOnce: Boolean = false,
    /** First page (or a refresh) in flight. */
    val refreshing: Boolean = false,
    /** A later page in flight. */
    val loadingMore: Boolean = false,
    val error: String? = null,
) {
    val endReached: Boolean get() = loadedOnce && nextCursor == null
    val busy: Boolean get() = refreshing || loadingMore
}

/**
 * Cursor paging, newest first: [refresh] replaces the list with page one, [loadMore] appends the page after
 * the last cursor. Server pages can overlap at equal timestamps, so items are de-duplicated by [key].
 * A refresh cancels a page that is still loading, and [loadMore] never runs twice at once.
 */
class Pager<T>(
    private val scope: CoroutineScope,
    private val key: (T) -> String,
    private val fetch: suspend (cursor: String?) -> ApiResult<PageOf<T>>,
) {
    private val _state = MutableStateFlow(PagerState<T>())
    val state: StateFlow<PagerState<T>> = _state.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        _state.update { it.copy(refreshing = true, loadingMore = false, error = null) }
        job = scope.launch {
            when (val r = fetch(null)) {
                is ApiResult.Ok -> _state.value = PagerState(
                    items = r.value.items.distinctBy(key), nextCursor = r.value.nextCursor, loadedOnce = true,
                )
                is ApiResult.Err -> _state.update { it.copy(refreshing = false, error = r.message) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (!s.loadedOnce || s.busy) return
        _state.update { it.copy(loadingMore = true, error = null) }
        job = scope.launch {
            when (val r = fetch(cursor)) {
                is ApiResult.Ok -> _state.update { cur ->
                    val seen = cur.items.mapTo(HashSet(), key)
                    cur.copy(
                        items = cur.items + r.value.items.filter { seen.add(key(it)) },
                        nextCursor = r.value.nextCursor, loadingMore = false,
                    )
                }
                is ApiResult.Err -> _state.update { it.copy(loadingMore = false, error = r.message) }
            }
        }
    }

    /** Changes the item with [id] in place (a like, a new comment count). */
    fun update(id: String, change: (T) -> T) {
        _state.update { s -> s.copy(items = s.items.map { if (key(it) == id) change(it) else it }) }
    }

    fun remove(id: String) {
        _state.update { s -> s.copy(items = s.items.filter { key(it) != id }) }
    }

    /** Adds [item] at the end unless one with the same key is already there (a comment I just sent). */
    fun append(item: T) {
        _state.update { s -> if (s.items.any { key(it) == key(item) }) s else s.copy(items = s.items + item) }
    }

    fun prepend(item: T) {
        _state.update { s -> s.copy(items = listOf(item) + s.items.filter { key(it) != key(item) }) }
    }

    fun clear() {
        job?.cancel()
        _state.value = PagerState()
    }
}
