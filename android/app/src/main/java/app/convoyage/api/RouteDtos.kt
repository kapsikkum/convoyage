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
