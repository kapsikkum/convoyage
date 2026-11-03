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

/** What an avatar badge needs of a vehicle: its kind and the build fields that change how it looks (`VehicleBadge` in the API, `MemberVehicle` in the convoy protocol). */
@Serializable
data class VehicleBadge(val kind: String = "car", val build: Build = Build())

fun Vehicle.badge() = VehicleBadge(kind, build)

/** GET /v1/catalogue: the ids the build fields carry. Static on the server. */
@Serializable
data class Catalogue(
    val version: Int = 0,
    val kinds: List<CatalogueEntry> = emptyList(),
    val paints: List<CataloguePaint> = emptyList(),
    val finishes: List<CatalogueEntry> = emptyList(),
    val wheels: List<CatalogueEntry> = emptyList(),
)

@Serializable
data class CatalogueEntry(val id: String, val name: String = "")

@Serializable
data class CataloguePaint(val id: String, val name: String = "", val hex: String = "")

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

    /** The ids the server's catalogue carries. `GET /v1/catalogue` replaces these through [apply]; until it answers they are what it said last time we looked. */
    @Volatile var FINISHES: List<String> = listOf("gloss", "satin", "matte", "metallic", "pearl")

    /** Wheel ids and names. */
    @Volatile var WHEELS: List<Pair<String, String>> = listOf(
        "stock" to "Stock", "sport5" to "Sport 5-Spoke", "mesh" to "Mesh", "split_spoke" to "Split Spoke",
        "deep_dish" to "Deep Dish", "steelie" to "Steel Wheel",
    )

    /** Paint swatches: `#RRGGBB` and a name. */
    @Volatile var PAINTS: List<Pair<String, String>> = listOf(
        "#2de2e6" to "Convoy Cyan", "#f4f1ea" to "Pearl White", "#0b0b0d" to "Black", "#c8102e" to "Race Red",
        "#1f4fd8" to "Cobalt Blue", "#1e7b45" to "Forest Green", "#f2c230" to "Solar Yellow", "#b8bcc2" to "Silver",
    )

    fun apply(c: Catalogue) {
        if (c.finishes.isNotEmpty()) FINISHES = c.finishes.map { it.id }
        if (c.wheels.isNotEmpty()) WHEELS = c.wheels.map { it.id to it.name.ifBlank { it.id } }
        if (c.paints.isNotEmpty()) PAINTS = c.paints.map { it.hex to it.name.ifBlank { it.hex } }
    }

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
    /** Their primary vehicle, for the avatar badge; null when they have none or the viewer may not see it. */
    val primaryVehicle: VehicleBadge? = null,
) {
    /** Server-relative path of the profile photo, or null. */
    val avatarPath: String? get() = avatarMediaId?.let { "/v1/media/$it/thumb" }
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
