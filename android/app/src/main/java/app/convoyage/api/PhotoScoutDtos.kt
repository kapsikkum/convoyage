package app.convoyage.api

import kotlinx.serialization.Serializable

/** GET /v1/photoscout/status. [configured] false: this server has no PhotoScout, every other PhotoScout call answers 503. */
@Serializable
data class PhotoScoutStatus(val configured: Boolean = false, val linked: Boolean = false, val username: String? = null)

@Serializable
data class PhotoScoutAuthorizeUrl(val authorizeUrl: String)

@Serializable
data class PsGoodTimes(
    val phases: List<String> = emptyList(),
    val months: List<Int> = emptyList(),
    val days: String = "",
    val conditions: List<String> = emptyList(),
    val avoid: String = "",
    val notes: String = "",
)

@Serializable
data class PsPhoto(val id: String, val thumbUrl: String, val url: String)

/** GET /v1/photoscout/spots: a photo spot. Photo URLs are this server's and need the bearer token. */
@Serializable
data class PsSpot(
    val id: String,
    val name: String,
    val notes: String = "",
    val lat: Double,
    val lng: Double,
    val tags: List<String> = emptyList(),
    val goodTimes: PsGoodTimes = PsGoodTimes(),
    val photoCount: Int = 0,
    val photo: PsPhoto? = null,
)

@Serializable
data class PsMeet(val lat: Double, val lng: Double, val note: String = "")

/** A community event's cruise: open its convoy with the socket's `convoy.cruise`. */
@Serializable
data class PsCruise(val routeName: String = "", val meet: PsMeet, val rollOut: String = "")

/** GET /v1/photoscout/events: a community or crawled event. */
@Serializable
data class PsEvent(
    val id: String,
    val source: String = "community",
    val title: String,
    val description: String = "",
    val startTime: String,
    val endTime: String? = null,
    val venue: String = "",
    val address: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val imageUrl: String = "",
    val url: String = "",
    val going: Int = 0,
    val interested: Int = 0,
    val myRsvp: String? = null,
    val cruise: PsCruise? = null,
    val distanceKm: Double? = null,
)

/** POST /v1/photoscout/events/{id}/rsvp: [status] "going" or "interested" (community events: going only), or going = false to clear. */
@Serializable
data class PsRsvpRequest(val status: String? = null, val going: Boolean? = null)

@Serializable
data class PsRsvp(val myRsvp: String? = null, val going: Int = 0, val interested: Int = 0)
