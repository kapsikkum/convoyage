package app.convoyage.api

import kotlinx.serialization.Serializable

/** GET /v1/route (protocol/openapi.json): a driving route with turn-by-turn steps. Geometry is polyline6. */
@Serializable
data class RouteResponse(
    val profile: String = "car",
    val geometry: String,
    val geometryPrecision: Int = 6,
    val distanceM: Double = 0.0,
    val durationS: Double = 0.0,
    val steps: List<RouteStepDto> = emptyList(),
)

@Serializable
data class RouteStepDto(
    val maneuver: ManeuverDto,
    val name: String = "",
    val distanceM: Double = 0.0,
    val durationS: Double = 0.0,
    val instruction: String = "",
)

@Serializable
data class ManeuverDto(
    val type: String = "",
    val modifier: String? = null,
    /** [lng, lat]. */
    val location: List<Double> = emptyList(),
)

/** POST /v1/voice/token. */
@Serializable
data class VoiceTokenRequest(val convoyCode: String)

/** [url] is the LiveKit server URL (ws://...). */
@Serializable
data class VoiceToken(
    val token: String,
    val url: String,
    val room: String = "",
    val identity: String = "",
    val expiresIn: Int = 0,
)
