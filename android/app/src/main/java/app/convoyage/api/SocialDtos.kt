package app.convoyage.api

import kotlinx.serialization.Serializable

/**
 * DTOs for vehicles, rides, media, posts and comments (protocol/openapi.json). Same rule as [ApiJson]:
 * only the fields the app reads, unknown fields ignored.
 */

// ---- vehicles ----------------------------------------------------------------------------------

@Serializable
data class Decal(val id: String, val slot: String? = null, val color: String? = null)

@Serializable
data class Build(
    /** `#RRGGBB`. */
    val paint: String? = null,
    /** One of [VehicleOptions.FINISHES]. */
    val finish: String? = null,
    /** A wheel id from the (not yet existing) garage catalogue; see [VehicleOptions.WHEELS]. */
    val wheel: String? = null,
    val rideHeightMm: Int? = null,
    val decals: List<Decal>? = null,
)

@Serializable
data class Vehicle(
    val id: String,
    val ownerId: String = "",
    val kind: String = "car",
    val make: String = "",
    val model: String = "",
    val year: Int? = null,
    val nickname: String = "",
    val build: Build = Build(),
    val isPrimary: Boolean = false,
    val soldAt: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    val isSold: Boolean get() = soldAt != null

    /** The nickname when there is one, else "Make Model". */
    val displayName: String get() = nickname.ifBlank { "$make $model".trim() }
}

/** POST /v1/vehicles. */
@Serializable
data class CreateVehicle(
    val kind: String,
    val make: String,
    val model: String,
    val year: Int? = null,
    val nickname: String? = null,
    val build: Build? = null,
    val isPrimary: Boolean? = null,
)

/** PATCH /v1/vehicles/{id}. `build` replaces the whole build object. Nulls are omitted, so a year can be changed but not cleared. */
@Serializable
data class UpdateVehicle(
    val kind: String? = null,
    val make: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val nickname: String? = null,
    val build: Build? = null,
)

@Serializable
data class SoldBody(val soldAt: String? = null)

/** The fixed choices the garage editor offers. */
object VehicleOptions {
    val KINDS = listOf("car", "motorcycle", "ute", "4wd")
    val FINISHES = listOf("gloss", "satin", "matte", "metallic", "pearl")

    /** TODO(garage-catalogue): the server has no wheel catalogue yet, so these ids are the app's own. */
    val WHEELS = listOf(
        "stock" to "Stock", "mesh" to "Mesh", "multi_spoke" to "Multi-spoke", "split_five" to "Split five",
        "deep_dish" to "Deep dish", "steelie" to "Steelie", "forged_bronze" to "Forged bronze",
    )

    /** Paint swatches: `#RRGGBB` and a name. */
    val PAINTS = listOf(
        "#F2F0EA" to "Pearl white", "#14181F" to "Night black", "#5B6675" to "Gunmetal", "#D6232B" to "Signal red",
        "#FFB020" to "Amber", "#46E08C" to "Go green", "#2DE2E6" to "Cyan storm", "#6F97FF" to "Police blue",
    )

    fun kindLabel(kind: String) = when (kind) {
        "motorcycle" -> "Motorcycle"
        "ute" -> "Ute"
        "4wd" -> "4WD"
        else -> "Car"
    }

    fun paintName(hex: String?): String = PAINTS.firstOrNull { it.first.equals(hex, true) }?.second ?: hex.orEmpty().ifBlank { "Stock paint" }
    fun wheelLabel(id: String?): String = WHEELS.firstOrNull { it.first == id }?.second ?: id.orEmpty().ifBlank { "Stock" }
}

// ---- rides -------------------------------------------------------------------------------------

@Serializable
data class Media(
    val id: String,
    val mime: String = "",
    val bytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val takenAt: String? = null,
    val createdAt: String = "",
    /** Server-relative path, e.g. `/v1/media/<id>`; fetching needs the bearer token. */
    val url: String = "",
    val thumbUrl: String = "",
)

@Serializable
data class RidePhoto(
    val id: String,
    val media: Media,
    val lat: Double? = null,
    val lng: Double? = null,
    val takenAt: String? = null,
)

/** A ride without its trace (lists, and inside posts). */
@Serializable
data class RideSummary(
    val id: String,
    val ownerId: String = "",
    val vehicleId: String? = null,
    val title: String = "",
    /** `active` or `finished`. */
    val status: String = "finished",
    /** `public`, `followers` or `private`. */
    val visibility: String = "followers",
    val startedAt: String = "",
    val endedAt: String? = null,
    val startLat: Double? = null,
    val startLng: Double? = null,
    val distanceM: Double? = null,
    val durationS: Long? = null,
    val maxSpeedMps: Double? = null,
    val avgSpeedMps: Double? = null,
    val elevationGainM: Double? = null,
    val maxLeanDeg: Double? = null,
    val convoyCode: String? = null,
    val createdAt: String = "",
) {
    val isFinished: Boolean get() = status == "finished"
}

/** One ride with its trace and photos (GET /v1/rides/{id}, and the answer to create/finish). */
@Serializable
data class Ride(
    val id: String,
    val ownerId: String = "",
    val vehicleId: String? = null,
    val title: String = "",
    val status: String = "active",
    val visibility: String = "followers",
    val startedAt: String = "",
    val endedAt: String? = null,
    val startLat: Double? = null,
    val startLng: Double? = null,
    val distanceM: Double? = null,
    val durationS: Long? = null,
    val maxSpeedMps: Double? = null,
    val avgSpeedMps: Double? = null,
    val elevationGainM: Double? = null,
    val maxLeanDeg: Double? = null,
    val convoyCode: String? = null,
    val createdAt: String = "",
    val polyline: String? = null,
    val polylinePrecision: Int = 5,
    val photos: List<RidePhoto> = emptyList(),
) {
    fun summary() = RideSummary(
        id, ownerId, vehicleId, title, status, visibility, startedAt, endedAt, startLat, startLng,
        distanceM, durationS, maxSpeedMps, avgSpeedMps, elevationGainM, maxLeanDeg, convoyCode, createdAt,
    )
}

@Serializable
data class RidePage(val items: List<RideSummary> = emptyList(), val nextCursor: String? = null)

/** POST /v1/rides. */
@Serializable
data class CreateRide(
    val vehicleId: String? = null,
    val title: String? = null,
    val visibility: String? = null,
    val startedAt: String? = null,
    val startLat: Double? = null,
    val startLng: Double? = null,
    val convoyCode: String? = null,
)

/** POST /v1/rides/{id}/finish. Speeds are metres per second. */
@Serializable
data class FinishRide(
    val endedAt: String,
    val distanceM: Double,
    val durationS: Long,
    val maxSpeedMps: Double,
    val avgSpeedMps: Double,
    val elevationGainM: Double? = null,
    val maxLeanDeg: Double? = null,
    val polyline: String,
    val polylinePrecision: Int = 5,
    val title: String? = null,
    val visibility: String? = null,
)

/** PATCH /v1/rides/{id}. */
@Serializable
data class UpdateRide(val title: String? = null, val visibility: String? = null)

/** POST /v1/rides/{id}/photos. lat and lng go together or not at all. */
@Serializable
data class AttachPhoto(val mediaId: String, val lat: Double? = null, val lng: Double? = null, val takenAt: String? = null)

// ---- posts -------------------------------------------------------------------------------------

@Serializable
data class UserRef(
    val id: String,
    val handle: String = "",
    val displayName: String = "",
    val avatarMediaId: String? = null,
) {
    val name: String get() = displayName.ifBlank { handle }.ifBlank { "Driver" }
}

@Serializable
data class Post(
    val id: String,
    /** `ride`, `build`, `photo` or `text`. */
    val kind: String,
    val author: UserRef,
    val body: String = "",
    val visibility: String = "public",
    val createdAt: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val ride: RideSummary? = null,
    val vehicle: Vehicle? = null,
    val media: List<Media> = emptyList(),
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val likedByMe: Boolean = false,
)

@Serializable
data class FeedPage(val items: List<Post> = emptyList(), val nextCursor: String? = null)

@Serializable
data class CreatePost(
    val kind: String,
    val body: String? = null,
    val rideId: String? = null,
    val vehicleId: String? = null,
    val mediaIds: List<String>? = null,
    /** `public` or `followers`. */
    val visibility: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
)

@Serializable
data class LikeState(val likeCount: Int = 0, val likedByMe: Boolean = false)

@Serializable
data class Comment(
    val id: String,
    val postId: String = "",
    val author: UserRef,
    val body: String = "",
    val createdAt: String = "",
)

@Serializable
data class CommentPage(val items: List<Comment> = emptyList(), val nextCursor: String? = null)

@Serializable
data class CreateComment(val body: String)

/** POST /v1/reports. */
@Serializable
data class CreateReport(val targetType: String, val targetId: String, val reason: String, val detail: String? = null)

object ReportReasons {
    val ALL = listOf("spam", "abuse", "nudity", "violence", "illegal", "other")
}

@Serializable
data class OkBody(val ok: Boolean = true)

/** Which slice of the feed to load. */
enum class FeedScope(val wire: String) { Everyone("everyone"), Following("following"), Nearby("nearby") }
