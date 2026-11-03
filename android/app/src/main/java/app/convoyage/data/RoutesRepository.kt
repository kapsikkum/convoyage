package app.convoyage.data

import app.convoyage.api.ApiResult
import app.convoyage.api.ConvoyApi
import app.convoyage.api.PsRoute
import app.convoyage.api.SaveRoute
import app.convoyage.api.SavedRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RoutesState(
    val routes: List<SavedRoute> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

/** My saved routes (sprint or circuit), plus importing the ones I saved in PhotoScout. */
class RoutesRepository(private val scope: CoroutineScope, private val api: ConvoyApi) {
    private val _state = MutableStateFlow(RoutesState())
    val state: StateFlow<RoutesState> = _state.asStateFlow()

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            when (val r = api.listRoutes()) {
                is ApiResult.Ok -> _state.update { it.copy(routes = r.value, loading = false, loaded = true) }
                is ApiResult.Err -> _state.update { it.copy(loading = false, loaded = true, error = r.message) }
            }
        }
    }

    suspend fun save(body: SaveRoute): ApiResult<SavedRoute> {
        val r = api.saveRoute(body)
        if (r is ApiResult.Ok) _state.update { it.copy(routes = listOf(r.value) + it.routes) }
        return r
    }

    suspend fun rename(id: String, name: String): ApiResult<SavedRoute> {
        val r = api.renameRoute(id, name)
        if (r is ApiResult.Ok) _state.update { s -> s.copy(routes = s.routes.map { if (it.id == id) r.value else it }) }
        return r
    }

    suspend fun delete(id: String): ApiResult<Unit> {
        val r = api.deleteRoute(id)
        if (r is ApiResult.Ok) _state.update { s -> s.copy(routes = s.routes.filterNot { it.id == id }) }
        return r
    }

    suspend fun photoscoutRoutes(): ApiResult<List<PsRoute>> = api.photoscoutRoutes()

    suspend fun importFromPhotoscout(id: String): ApiResult<SavedRoute> {
        val r = api.importPhotoscoutRoute(id)
        if (r is ApiResult.Ok) _state.update { s -> s.copy(routes = listOf(r.value) + s.routes.filterNot { it.id == r.value.id }) }
        return r
    }

    fun clear() { _state.value = RoutesState() }
}
