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
    /** This server has PhotoScout and the account is linked: the layer toggles are offered. */
    val available: Boolean = false,
    val spots: List<PsSpot> = emptyList(),
    val events: List<PsEvent> = emptyList(),
    val error: String? = null,
)

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

    /** Asks the server whether PhotoScout is there and linked. Call after sign-in and when Settings links it. */
    fun refreshStatus() {
        scope.launch {
            val ok = (api.photoscoutStatus() as? ApiResult.Ok)?.value?.let { it.configured && it.linked } ?: false
            _state.update { if (ok) it.copy(available = true) else LayersState() }
        }
    }

    fun clear() {
        spotsBox = null; eventsCentre = null; spotsAt = 0; eventsAt = 0
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
                val box = view.grown(0.5).clamped(2.0)
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

    /** Going / interested / take it back ([status] null). The list updates with the server's counts. */
    suspend fun rsvp(event: PsEvent, status: String?): String? {
        val body = if (status == null) PsRsvpRequest(going = false) else PsRsvpRequest(status = status)
        return when (val r = api.photoscoutRsvp(event.id, body)) {
            is ApiResult.Ok -> {
                _state.update { s ->
                    s.copy(events = s.events.map { if (it.id == event.id) it.copy(myRsvp = r.value.myRsvp, going = r.value.going, interested = r.value.interested) else it })
                }
                null
            }
            is ApiResult.Err -> r.message
        }
    }
}

/** True when [inner] lies wholly inside this box (no antimeridian handling: the boxes here are small). */
internal fun Bbox.covers(inner: Bbox) = inner.south >= south && inner.north <= north && inner.west >= west && inner.east <= east

/** The same box with [fraction] of its size added on every side. */
internal fun Bbox.grown(fraction: Double): Bbox {
    val dLat = (north - south) * fraction
    val dLng = (east - west) * fraction
    return Bbox((south - dLat).coerceAtLeast(-90.0), (west - dLng).coerceAtLeast(-180.0), (north + dLat).coerceAtMost(90.0), (east + dLng).coerceAtMost(180.0))
}

internal fun Bbox.halfDiagonalKm(): Double {
    val midLat = Math.toRadians((south + north) / 2)
    val h = (north - south) * 111.2
    val w = (east - west) * 111.2 * cos(midLat)
    return Math.hypot(h, w) / 2
}
