package app.convoyage.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Hand-written DTOs for the REST API in protocol/openapi.json (the Hazard type lives in `protocol`, because
 * the WebSocket `hazard` message carries the same object).
 *
 * TODO(codegen): generate these from openapi.json. Until then keep them in step by hand; only the fields the
 * app reads are listed and unknown fields are ignored, so the server can add fields within v1.
 */

@OptIn(ExperimentalSerializationApi::class)
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

/** POST /auth/magic-link. */
@Serializable
data class MagicLinkRequest(val email: String)

/** 202 from POST /auth/magic-link. [devLink] is only present when the server runs with MAIL_DEV=1. */
@Serializable
data class MagicLinkResponse(val ok: Boolean = true, val devLink: String? = null)

/** POST /auth/magic-link/verify. */
@Serializable
data class VerifyRequest(val token: String)

/** POST /auth/refresh. */
@Serializable
data class RefreshRequest(val refreshToken: String)

/** POST /auth/logout. */
@Serializable
data class LogoutRequest(val refreshToken: String)

/** 200 from POST /auth/refresh. The refresh token rotates: the old one is dead as soon as this is returned. */
@Serializable
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    /** Access token lifetime, seconds. */
    val expiresIn: Int = 900,
)

/** 200 from POST /auth/magic-link/verify. */
@Serializable
data class AuthResult(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Int = 900,
    val isNewUser: Boolean = false,
) {
    fun pair() = TokenPair(accessToken, refreshToken, tokenType, expiresIn)
}

/** GET|PATCH /v1/me (the part of `Me` the app shows). */
@Serializable
data class Me(
    val id: String,
    val handle: String = "",
    val handleChosen: Boolean = false,
    val displayName: String = "",
    val bio: String = "",
    val avatarMediaId: String? = null,
    val isPrivate: Boolean = false,
    val email: String? = null,
    val createdAt: String = "",
    val followerCount: Int = 0,
    val followingCount: Int = 0,
    val pendingRequestCount: Int = 0,
    val primaryVehicle: Vehicle? = null,
) {
    /** Server-relative path of my profile photo, or null. */
    val avatarPath: String? get() = avatarMediaId?.let { "/v1/media/$it/thumb" }
}

/** PATCH /v1/me. Only the fields that are set are sent. */
@Serializable
data class UpdateMe(val displayName: String? = null, val handle: String? = null)

/** POST /v1/hazards. [kind] is one of [HazardKinds.ALL]; [subkind] matches `^[a-z0-9_]{0,24}$`. */
@Serializable
data class CreateHazard(
    val kind: String,
    val subkind: String? = null,
    val lat: Double,
    val lng: Double,
    /** Direction of travel, 0..360. */
    val heading: Double? = null,
)

/** Error body for every non-2xx: `{ statusCode, error, message }`. */
@Serializable
data class ApiError(val statusCode: Int = 0, val error: String = "", val message: String = "")

/** The hazard kinds the server accepts (protocol/openapi.json, `Hazard.kind`). */
object HazardKinds {
    const val POLICE = "police"
    const val HAZARD = "hazard"
    const val WORKS = "works"
    const val FUEL = "fuel"
    const val REST = "rest"
    const val CRASH = "crash"
    val ALL = listOf(POLICE, HAZARD, WORKS, FUEL, REST, CRASH)
}
