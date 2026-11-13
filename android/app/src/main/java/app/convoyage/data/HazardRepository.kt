package app.convoyage.data

import app.convoyage.api.ApiResult
import app.convoyage.api.CreateHazard
import app.convoyage.api.HazardApi
import app.convoyage.protocol.Hazard
import app.convoyage.protocol.ServerMessage
import app.convoyage.protocol.expiresAtMs
import app.convoyage.protocol.toHazard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** Map box in degrees. The server wants `south,west,north,east` and refuses more than 10 degrees each way. */
data class Bbox(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun toParam(): String = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", south, west, north, east)

    fun contains(lat: Double, lng: Double) = lat in south..north && (if (west <= east) lng in west..east else lng >= west || lng <= east)

    /** Same centre, but at most [maxDegrees] across, so the server never answers 400 for a zoomed-out map. */
    fun clamped(maxDegrees: Double = 8.0): Bbox {
        val cLat = (south + north) / 2
        val cLng = (west + east) / 2
        val halfLat = ((north - south) / 2).coerceAtMost(maxDegrees / 2)
        val halfLng = ((east - west) / 2).coerceAtMost(maxDegrees / 2)
        return Bbox(
            (cLat - halfLat).coerceAtLeast(-90.0), (cLng - halfLng).coerceAtLeast(-180.0),
            (cLat + halfLat).coerceAtMost(90.0), (cLng + halfLng).coerceAtMost(180.0),
        )
    }

    companion object {
        /** A square of roughly [radiusM] around a point. */
        fun around(lat: Double, lng: Double, radiusM: Double): Bbox {
            val dLat = radiusM / 111_320.0
            val dLng = radiusM / (111_320.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
            return Bbox(lat - dLat, lng - dLng, lat + dLat, lng + dLng)
        }
    }
}

/** A hazard a convoy member just reported, waiting for Confirm / Dismiss. */
data class HazardAlert(val hazard: Hazard, val room: String, val error: String? = null)

sealed interface ReportResult {
    data class Sent(val hazard: Hazard) : ReportResult
    data class Failed(val message: String) : ReportResult
}

/**
 * Active hazards for the map plus the live "a member reported a hazard" alert.
 *
 * Sources: `GET /v1/hazards?bbox=` for what is on screen, the socket's `hazard` frames for live reports, and our
 * own POST responses. Everything is merged by id; expired hazards and ones we dismissed are filtered out.
 */
class HazardRepository(
    private val scope: CoroutineScope,
    private val api: HazardApi,
    incoming: Flow<ServerMessage.HazardReported>,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val byId = MutableStateFlow<Map<String, Hazard>>(emptyMap())
    private val _alert = MutableStateFlow<HazardAlert?>(null)

    /** Ids of hazards I reported: the server echoes them to the whole room, me included, and I do not need a banner. */
    private val mine = HashSet<String>()

    private val _active = MutableStateFlow<List<Hazard>>(emptyList())

    /** What the map shows right now. */
    val active: StateFlow<List<Hazard>> = _active.asStateFlow()

    /**
     * Reports whose POST has not answered yet. The server pushes the `hazard` frame to the room BEFORE it replies
     * to the POST, so my own report can arrive on the socket first; match it by kind and place.
     */
    private val inFlight = ArrayList<CreateHazard>()

    /** The newest unanswered report from another member, or null. */
    val alert: StateFlow<HazardAlert?> = _alert.asStateFlow()

    init {
        scope.launch { incoming.collect { onSocketHazard(it.room, it.hazard.toHazard()) } }
    }

    /** Folds a live report in. A report from someone else raises [alert]. */
    fun onSocketHazard(room: String, hazard: Hazard) {
        upsert(hazard)
        if (inFlight.any { it.kind == hazard.kind && Geo.distanceM(it.lat, it.lng, hazard.lat, hazard.lng) < OWN_REPORT_RADIUS_M }) mine += hazard.id
        val seen = hazard.id in mine || hazard.myAction != null
        if (!seen && !isExpired(hazard)) _alert.value = HazardAlert(hazard, room)
    }

    /** Replaces what we know inside [box] with the server's answer; hazards outside it are kept. */
    suspend fun refresh(box: Bbox): ApiResult<List<Hazard>> {
        val r = api.listHazards(box.clamped().toParam())
        if (r is ApiResult.Ok) {
            byId.update { old ->
                val fetched = r.value.associateBy { it.id }
                val kept = old.filterValues { !box.contains(it.lat, it.lng) && it.id !in fetched }
                kept + fetched
            }
            publish()
        }
        return r
    }

    suspend fun report(kind: String, subkind: String, lat: Double, lng: Double, headingDeg: Double?): ReportResult {
        val body = CreateHazard(
            kind = kind,
            subkind = subkind.ifBlank { null },
            lat = lat,
            lng = lng,
            heading = headingDeg?.let { ((it % 360) + 360) % 360 },
        )
        inFlight += body
        val result = try {
            api.createHazard(body)
        } finally {
            inFlight -= body
        }
        return when (val r = result) {
            is ApiResult.Ok -> {
                mine += r.value.id
                clearAlert(r.value.id)
                upsert(r.value)
                ReportResult.Sent(r.value)
            }
            is ApiResult.Err -> ReportResult.Failed(
                when {
                    r.isUnauthorized -> "Sign in with your email to report hazards."
                    r.status == 429 -> "Slow down, too many reports."
                    else -> r.message
                },
            )
        }
    }

    fun confirm(id: String) = vote(id) { api.confirmHazard(id) }

    fun dismiss(id: String) = vote(id) { api.dismissHazard(id) }

    /** Hides the banner without voting. */
    fun clearAlert(id: String? = null) = _alert.update { if (id == null || it?.hazard?.id == id) null else it }

    fun clear() {
        byId.value = emptyMap()
        mine.clear()
        _alert.value = null
        publish()
    }

    private fun vote(id: String, call: suspend () -> ApiResult<Hazard>) {
        scope.launch {
            when (val r = call()) {
                is ApiResult.Ok -> {
                    upsert(r.value)
                    clearAlert(id)
                }
                is ApiResult.Err -> {
                    // 409 = I already voted / it is my own report; 404 = it expired. Either way the banner has done its job.
                    if (r.status == 409 || r.status == 404) {
                        if (r.status == 404) byId.update { it - id }.also { publish() }
                        clearAlert(id)
                    } else {
                        _alert.update { a -> if (a?.hazard?.id == id) a.copy(error = r.message) else a }
                    }
                }
            }
        }
    }

    private fun upsert(h: Hazard) {
        byId.update { it + (h.id to h) }
        publish()
    }

    private fun isExpired(h: Hazard) = h.expiresAtMs()?.let { it <= nowMs() } ?: false

    private fun publish() {
        _active.value = byId.value.values
            .filter { !isExpired(it) && it.myAction != "dismiss" }
            .sortedBy { it.id }
    }

    private companion object {
        const val OWN_REPORT_RADIUS_M = 30.0
    }
}
