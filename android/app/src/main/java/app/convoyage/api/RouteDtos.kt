package app.convoyage.api

import kotlinx.serialization.Serializable

/** A point on the map, as the server sends it. */
@Serializable
data class LatLngDto(val lat: Double, val lng: Double)

/**
 * GET /v1/routes: a saved route (PhotoScout's model). [waypoints] are what the author placed, [geometry] the road line
 * through them (polyline6), and [convoyStops] the at-most-25 stops a convoy follows ([lng, lat]; a circuit closes).
 */
@Serializable
data class SavedRoute(
    val id: String,
    val ownerId: String = "",
    val name: String,
    val notes: String = "",
    /** "sprint" (point to point) or "circuit" (ends where it starts). */
    val type: String = "sprint",
    val waypoints: List<List<Double>> = emptyList(),
    val geometry: String? = null,
    val distanceM: Double? = null,
    val durationS: Double? = null,
    /** Where the convoy gathers before the drive. */
    val staging: LatLngDto? = null,
    val convoyStops: List<List<Double>> = emptyList(),
    val visibility: String = "followers",
    /** "convoyage" or "photoscout" (an import). */
    val source: String = "convoyage",
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    val isCircuit: Boolean get() = type == "circuit"
}

/** POST /v1/convoys/{code}/live: a link anyone can open in a browser to watch the convoy. */
@Serializable
data class LiveLink(val token: String, val url: String, val expiresAt: String = "")

@Serializable
data class RouteOwner(val handle: String = "", val displayName: String = "")

/** GET /v1/routes/discover: a route somebody else shares, starting [distanceM] from where we asked. */
@Serializable
data class DiscoveredRoute(
    val route: SavedRoute,
    val owner: RouteOwner = RouteOwner(),
    val distanceM: Double = 0.0,
    /** "relaxed", "flowing" or "twisty". */
    val character: String = "relaxed",
    val turnDegPerKm: Double = 0.0,
)

/** POST /v1/routes. Nulls are left out of the JSON. */
@Serializable
data class SaveRoute(
    val name: String,
    val waypoints: List<List<Double>>,
    val type: String = "sprint",
    val notes: String? = null,
    val geometry: String? = null,
    val distanceM: Double? = null,
    val durationS: Double? = null,
    val staging: LatLngDto? = null,
    val visibility: String? = null,
)

/** GET /v1/photoscout/routes: a route saved in PhotoScout, not yet imported. */
@Serializable
data class PsRoute(
    val id: String,
    val name: String,
    val notes: String = "",
    val type: String = "sprint",
    val snap: Boolean = false,
    val vertices: List<List<Double>> = emptyList(),
    val waypoints: List<List<Double>>? = null,
    val staging: LatLngDto? = null,
)

@Serializable
data class ConditionsBody(val geometry: String, val durationS: Double? = null)

/** Something on or by the route: a police report, crash, roadworks, jam, flood... */
@Serializable
data class ConditionItem(
    val id: String,
    /** tfnsw, waze or convoyage. */
    val source: String = "",
    /** police, camera, hazard, crash, jam, closure, roadwork, event, flood, fire, alpine. */
    val kind: String = "",
    val title: String = "",
    val detail: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** Kilometres from the start of the route. */
    val km: Double = 0.0,
    val reportedAt: String? = null,
)

@Serializable
data class TrafficConditions(val level: String = "clear", val delayMin: Double = 0.0, val summary: String = "", val items: List<ConditionItem> = emptyList())

@Serializable
data class WeatherPoint(
    val km: Double = 0.0, val lat: Double = 0.0, val lng: Double = 0.0, val at: String = "",
    val tempC: Double? = null, val precipProbPct: Double? = null, val precipMm: Double? = null, val code: Int? = null,
    val windKmh: Double? = null, val gustKmh: Double? = null, val visibilityM: Double? = null, val label: String = "", val isDay: Boolean = true,
)

@Serializable
data class WeatherConditions(val summary: String = "", val worst: Int = 0, val points: List<WeatherPoint> = emptyList())

@Serializable
data class RoadKm(val good: Double = 0.0, val fair: Double = 0.0, val poor: Double = 0.0, val unknown: Double = 0.0)

@Serializable
data class RoadRun(val grade: String = "unknown", val km: Double = 0.0, val name: String = "", val surface: String = "", val line: String = "")

@Serializable
data class RoadStretch(val name: String = "", val km: Double = 0.0, val surface: String = "")

@Serializable
data class RoadSummary(
    val km: RoadKm = RoadKm(), val unpavedKm: Double = 0.0, val runs: List<RoadRun> = emptyList(),
    val worst: List<RoadStretch> = emptyList(), val fetchedAt: String = "",
)

/** Each source: ok, off (not set up), error, partial or pending (still loading on the server; ask again soon). */
@Serializable
data class ConditionSources(val tfnsw: String = "off", val waze: String = "off", val weather: String = "off", val roads: String = "off")

/** GET /v1/routes/{id}/conditions and POST /v1/conditions. */
@Serializable
data class RouteConditions(
    val computedAt: String = "",
    val traffic: TrafficConditions = TrafficConditions(),
    val police: List<ConditionItem> = emptyList(),
    val hazards: List<ConditionItem> = emptyList(),
    val weather: WeatherConditions = WeatherConditions(),
    val roads: RoadSummary? = null,
    val sources: ConditionSources = ConditionSources(),
    val cached: Boolean = false,
)
