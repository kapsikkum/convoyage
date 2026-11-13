package app.convoyage.api

import kotlinx.serialization.Serializable

/**
 * DTOs for vehicles, rides, media, posts and comments (protocol/openapi.json). Same rule as [ApiJson]:
 * only the fields the app reads, unknown fields ignored. UserRef and Post are the generated mirrors of their schemas
 * (see the posts section); the rest are hand-written and stay lenient on purpose.
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
    /** Body style (see Ps1Body); null = the kind's default. */
    val body: String? = null,
    /** Catalogue model id (see Ps1Catalog), "generic" for a body picked by hand, null = match make and model. */
    val shape: String? = null,
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

/** The badge others see; a vehicle saved before models were matched gets its match here. */
fun Vehicle.badge() = VehicleBadge(kind, build.copy(shape = build.shape ?: app.convoyage.render.Ps1Catalog.best(make, model, year, app.convoyage.render.Ps1Kind.of(kind))?.id))

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

// UserRef and Post are the two shapes that change most often, so they are not hand-written: they are the generated,
// strict mirrors of openapi.json (app.convoyage.api.gen, from protocol/codegen/generate.mjs). The server validates
// responses against those same schemas, so a drift fails codegen instead of decoding silently. Only the conveniences
// the UI reads live here, as extensions.

typealias UserRef = app.convoyage.api.gen.GenUserRef
typealias Post = app.convoyage.api.gen.GenPost

/** The avatar badge's view of a primary vehicle (the generated schema names its own build type). */
val UserRef.vehicleBadge: VehicleBadge?
    get() = primaryVehicle?.let { VehicleBadge(it.kind, it.build.toBadgeBuild()) }

/** `Post.author` is its own copy of the UserRef schema. */
val app.convoyage.api.gen.GenPostAuthor.vehicleBadge: VehicleBadge?
    get() = primaryVehicle?.let { VehicleBadge(it.kind, it.build.toBadgeBuild()) }

/** Server-relative path of the profile photo, or null. */
val UserRef.avatarPath: String? get() = avatarMediaId?.let { "/v1/media/$it/thumb" }

/** The display name, else the handle, else a placeholder. */
val UserRef.name: String get() = displayName.ifBlank { handle }.ifBlank { "Driver" }

val app.convoyage.api.gen.GenPostAuthor.avatarPath: String? get() = avatarMediaId?.let { "/v1/media/$it/thumb" }

val app.convoyage.api.gen.GenPostAuthor.name: String get() = displayName.ifBlank { handle }.ifBlank { "Driver" }

private fun app.convoyage.api.gen.GenUserRefPrimaryVehicleBuild.toBadgeBuild() = Build(
    paint = paint, finish = finish, wheel = wheel, rideHeightMm = rideHeightMm, body = body, shape = shape,
)

private fun app.convoyage.api.gen.GenPostAuthorPrimaryVehicleBuild.toBadgeBuild() = Build(
    paint = paint, finish = finish, wheel = wheel, rideHeightMm = rideHeightMm, body = body, shape = shape,
)

/** The post's ride as the summary the ride UI reads. */
fun app.convoyage.api.gen.GenPostRide.toRideSummary() = RideSummary(
    id = id,
    ownerId = ownerId,
    vehicleId = vehicleId,
    title = title,
    status = status,
    visibility = visibility,
    startedAt = startedAt,
    endedAt = endedAt,
    startLat = startLat,
    startLng = startLng,
    distanceM = distanceM,
    durationS = durationS?.toLong(),
    maxSpeedMps = maxSpeedMps,
    avgSpeedMps = avgSpeedMps,
    elevationGainM = elevationGainM,
    maxLeanDeg = maxLeanDeg,
    convoyCode = convoyCode,
    createdAt = createdAt,
)

/** The post's vehicle as the garage's [Vehicle]. */
fun app.convoyage.api.gen.GenPostVehicle.toVehicle() = Vehicle(
    id = id,
    ownerId = ownerId,
    kind = kind,
    make = make,
    model = model,
    year = year,
    nickname = nickname,
    build = Build(
        paint = build.paint,
        finish = build.finish,
        wheel = build.wheel,
        rideHeightMm = build.rideHeightMm,
        decals = build.decals?.map { Decal(it.id, it.slot, it.color) },
        body = build.body,
        shape = build.shape,
    ),
    isPrimary = isPrimary,
    soldAt = soldAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
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
