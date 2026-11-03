package app.convoyage.data

import app.convoyage.api.ApiResult
import app.convoyage.api.ConvoyApi
import app.convoyage.api.Ride
import app.convoyage.api.RideSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RidesState(
    val rides: List<RideSummary> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
) {
    val finished: List<RideSummary> get() = rides.filter { it.isFinished }
}

/**
 * The signed-in person's rides (`GET /v1/rides`, all pages up to [MAX_PAGES]) for the garage stats and the profile
 * Routes grid, and a small cache of decoded routes for the previews. The server sends the trace only with the full
 * ride, so a preview costs one `GET /v1/rides/{id}`; each route is fetched once.
 */
class RidesRepository(private val scope: CoroutineScope, private val api: ConvoyApi) {
    private val _state = MutableStateFlow(RidesState())
    val state: StateFlow<RidesState> = _state.asStateFlow()

    private val routes = object : LinkedHashMap<String, List<LatLngD>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LatLngD>>?) = size > MAX_ROUTES
    }
    private val inFlight = HashMap<String, Deferred<List<LatLngD>?>>()

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            val all = ArrayList<RideSummary>()
            var cursor: String? = null
            var pages = 0
            do {
                when (val r = api.listMyRides(cursor, 50)) {
                    is ApiResult.Ok -> {
                        all += r.value.items
                        cursor = r.value.nextCursor
                    }
                    is ApiResult.Err -> {
                        _state.update { it.copy(loading = false, error = r.message) }
                        return@launch
                    }
                }
            } while (cursor != null && ++pages < MAX_PAGES)
            _state.value = RidesState(rides = all, loaded = true)
        }
    }

    fun clear() {
        _state.value = RidesState()
        synchronized(routes) { routes.clear() }
    }

    /** Totals over finished rides on [vehicleId], or all of them when null. */
    fun totals(vehicleId: String? = null): LifetimeStats = RideTotals.of(_state.value.rides, vehicleId)

    fun cachedRoute(rideId: String): List<LatLngD>? = synchronized(routes) { routes[rideId] }

    /** The decoded, thinned route of any ride I may see, or null if it cannot be loaded. Shared between callers. */
    suspend fun route(rideId: String): List<LatLngD>? {
        cachedRoute(rideId)?.let { return it }
        val job = synchronized(inFlight) {
            inFlight.getOrPut(rideId) {
                scope.async {
                    val r = (api.getRide(rideId) as? ApiResult.Ok)?.value
                    val pts = r?.let(::decodeRoute)
                    if (pts != null) synchronized(routes) { routes[rideId] = pts }
                    synchronized(inFlight) { inFlight.remove(rideId) }
                    pts
                }
            }
        }
        return job.await()
    }

    suspend fun ride(id: String): ApiResult<Ride> = api.getRide(id)

    companion object {
        const val MAX_PAGES = 20
        private const val MAX_ROUTES = 120
        private const val PREVIEW_POINTS = 160

        fun decodeRoute(r: Ride): List<LatLngD>? {
            val encoded = r.polyline ?: return null
            return try {
                TraceMath.thin(Polyline.decode(encoded, r.polylinePrecision), PREVIEW_POINTS)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
