package app.convoyage.data

import app.convoyage.api.ApiResult
import app.convoyage.api.ConvoyApi
import app.convoyage.api.PsEvent
import app.convoyage.api.PsRsvpRequest
import app.convoyage.api.PsSpot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.max

data class LayersState(
    /** This server has PhotoScout: the layer toggles are offered (they ask to link first). */
    val configured: Boolean = false,
    val linked: Boolean = false,
    /** The PhotoScout username, when it told us. */
    val username: String? = null,
    /** Linked and configured: layers can load. */
    val available: Boolean = false,
    val spots: List<PsSpot> = emptyList(),
    val events: List<PsEvent> = emptyList(),
    /** The Home tab's five soonest events within 150 km, filled by [PhotoScoutLayers.loadHomeEvents]. */
    val homeEvents: List<PsEvent> = emptyList(),
    val error: String? = null,
)

/** How far around the driver the Home tab looks for events. */
private const val HOME_RADIUS_KM: Double = 150.0

/** An event that started over an hour ago is over: Home stops showing it. */
private const val HOME_GRACE_MS: Long = 3_600_000L

/**
 * PhotoScout spots and events for the Drive map. Spots load for the visible box; events for a radius around its centre
 * (they are few, and a meet may sit just off screen). Both are re-fetched only when the view leaves what was loaded
 * or the data is older than [maxAgeMs].
 */
class PhotoScoutLayers(
    private val scope: CoroutineScope,
    private val api: ConvoyApi,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = 5 * 60_000L,
) {
    private val _state = MutableStateFlow(LayersState())
    val state: StateFlow<LayersState> = _state.asStateFlow()

    private var spotsBox: Bbox? = null
    private var spotsAt = 0L
    private var eventsCentre: Pair<Double, Double>? = null
    private var eventsRadiusKm = 0.0
    private var eventsAt = 0L
    private var spotsJob: Job? = null
    private var eventsJob: Job? = null
    private var homeJob: Job? = null

    /** Asks the server whether PhotoScout is there and linked. Call after sign-in and when Settings links it. */
    fun refreshStatus() {
        scope.launch {
            val s = (api.photoscoutStatus() as? ApiResult.Ok)?.value ?: return@launch
            val ok = s.configured && s.linked
            _state.update {
                if (ok) it.copy(configured = true, linked = true, username = s.username, available = true)
                else LayersState(configured = s.configured)
            }
            // Just linked: force a load for the current view on the next viewport call.
            if (ok) { spotsAt = 0; eventsAt = 0 }
        }
    }

    fun clear() {
        spotsBox = null; eventsCentre = null; spotsAt = 0; eventsAt = 0
        homeJob?.cancel()
        _state.value = LayersState()
    }

    /** The map came to rest on [view]; load whichever layers are on. */
    fun onViewport(view: Bbox, spots: Boolean, events: Boolean) {
        if (!_state.value.available) return
        val t = now()
        if (spots) {
            val loaded = spotsBox
            if (loaded == null || !loaded.covers(view) || t - spotsAt > maxAgeMs) {
                // A margin around the view, so small pans need nothing new.
                val box = view.grown(0.5).atLeast(0.3).clamped(2.0)
                spotsJob?.cancel()
                spotsJob = scope.launch {
                    when (val r = api.photoscoutSpots(box)) {
                        is ApiResult.Ok -> { spotsBox = box; spotsAt = now(); _state.update { it.copy(spots = r.value, error = null) } }
                        is ApiResult.Err -> _state.update { it.copy(error = r.message) }
                    }
                }
            }
        }
        if (events) {
            val cLat = (view.south + view.north) / 2
            val cLng = (view.west + view.east) / 2
            val needKm = max(50.0, view.halfDiagonalKm() * 1.5)
            val c = eventsCentre
            val stale = c == null || t - eventsAt > maxAgeMs || needKm > eventsRadiusKm ||
                Geo.distanceM(c.first, c.second, cLat, cLng) / 1000 > eventsRadiusKm - view.halfDiagonalKm()
            if (stale) {
                val radius = (needKm * 2).coerceAtMost(500.0)
                eventsJob?.cancel()
                eventsJob = scope.launch {
                    when (val r = api.photoscoutEvents(cLat, cLng, radius)) {
                        is ApiResult.Ok -> {
                            eventsCentre = cLat to cLng; eventsRadiusKm = radius; eventsAt = now()
                            _state.update { it.copy(events = r.value.filter { e -> e.lat != null && e.lng != null }, error = null) }
                        }
                        is ApiResult.Err -> _state.update { it.copy(error = r.message) }
                    }
                }
            }
        }
    }

    /**
     * The Home tab's events: within [HOME_RADIUS_KM] of [lat],[lng], soonest first, at most five. Ones that started
     * over an hour ago are over and are left out. Only loads when the account is linked.
     */
    fun loadHomeEvents(lat: Double, lng: Double) {
        if (!_state.value.available) {
            _state.update { it.copy(homeEvents = emptyList()) }
            return
        }
        homeJob?.cancel()
        homeJob = scope.launch {
            when (val r = api.photoscoutEvents(lat, lng, HOME_RADIUS_KM)) {
                is ApiResult.Ok -> {
                    val cutoff = now() - HOME_GRACE_MS
                    val upcoming = r.value.mapNotNull { e -> startMs(e)?.let { it to e } }
                        .filter { it.first >= cutoff }
                        .sortedBy { it.first }
                        .take(5)
                        .map { it.second }
                    _state.update { it.copy(homeEvents = upcoming) }
                }
                // The Drive map shows its own error for the layer; Home keeps what it had.
                is ApiResult.Err -> Unit
            }
        }
    }

    /** The consent page to open, or an error to show. */
    suspend fun connectUrl(): Result<String> = when (val r = api.photoscoutConnect()) {
        is ApiResult.Ok -> Result.success(r.value.authorizeUrl)
        is ApiResult.Err -> Result.failure(IllegalStateException(r.message))
    }

    suspend fun unlink(): String? = when (val r = api.photoscoutUnlink()) {
        is ApiResult.Ok -> { val configured = _state.value.configured; clear(); _state.value = LayersState(configured = configured); null }
        is ApiResult.Err -> r.message
    }

    /** Going / interested / take it back ([status] null). The list updates with the server's counts. */
    suspend fun rsvp(event: PsEvent, status: String?): String? {
        val body = if (status == null) PsRsvpRequest(going = false) else PsRsvpRequest(status = status)
        return when (val r = api.photoscoutRsvp(event.id, body)) {
            is ApiResult.Ok -> {
                val v = r.value
                _state.update { s ->
                    s.copy(
                        events = s.events.map { e -> if (e.id == event.id) e.copy(myRsvp = v.myRsvp, going = v.going, interested = v.interested) else e },
                        homeEvents = s.homeEvents.map { e -> if (e.id == event.id) e.copy(myRsvp = v.myRsvp, going = v.going, interested = v.interested) else e },
                    )
                }
                null
            }
            is ApiResult.Err -> r.message
        }
    }
}

/** When [e] starts, in epoch milliseconds; null when the server sent a time we cannot read. */
private fun startMs(e: PsEvent): Long? = runCatching { java.time.Instant.parse(e.startTime).toEpochMilli() }.getOrNull()

/** True when [inner] lies wholly inside this box (no antimeridian handling: the boxes here are small). */
internal fun Bbox.covers(inner: Bbox) = inner.south >= south && inner.north <= north && inner.west >= west && inner.east <= east

/** The same box with [fraction] of its size added on every side. */
internal fun Bbox.grown(fraction: Double): Bbox {
    val dLat = (north - south) * fraction
    val dLng = (east - west) * fraction
    return Bbox((south - dLat).coerceAtLeast(-90.0), (west - dLng).coerceAtLeast(-180.0), (north + dLat).coerceAtMost(90.0), (east + dLng).coerceAtMost(180.0))
}

/** The same centre, at least [degrees] across each way. */
internal fun Bbox.atLeast(degrees: Double): Bbox {
    val cLat = (south + north) / 2
    val cLng = (west + east) / 2
    val hLat = maxOf((north - south) / 2, degrees / 2)
    val hLng = maxOf((east - west) / 2, degrees / 2)
    return Bbox((cLat - hLat).coerceAtLeast(-90.0), (cLng - hLng).coerceAtLeast(-180.0), (cLat + hLat).coerceAtMost(90.0), (cLng + hLng).coerceAtMost(180.0))
}

internal fun Bbox.halfDiagonalKm(): Double {
    val midLat = Math.toRadians((south + north) / 2)
    val h = (north - south) * 111.2
    val w = (east - west) * 111.2 * cos(midLat)
    return Math.hypot(h, w) / 2
}
