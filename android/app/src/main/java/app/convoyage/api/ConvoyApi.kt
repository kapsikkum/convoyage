package app.convoyage.api

import app.convoyage.data.FeedApi
import app.convoyage.protocol.Hazard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** [status] is the HTTP status, or 0 when the server could not be reached at all. */
    data class Err(val status: Int, val message: String) : ApiResult<Nothing> {
        val isNetwork: Boolean get() = status == 0
        val isUnauthorized: Boolean get() = status == 401
    }
}

/** The hazard endpoints the app needs; an interface so [app.convoyage.data.HazardRepository] can be tested. */
interface HazardApi {
    suspend fun createHazard(body: CreateHazard): ApiResult<Hazard>
    suspend fun listHazards(bbox: String): ApiResult<List<Hazard>>
    suspend fun confirmHazard(id: String): ApiResult<Hazard>
    suspend fun dismissHazard(id: String): ApiResult<Hazard>
}

/** The routing endpoint, an interface so [app.convoyage.nav.NavigationController] can be tested. */
interface RouteApi {
    /**
     * `GET /v1/route`. [profile] is "car" or "motorcycle". [headingDeg] is the way we are moving, so the route sets off
     * that way instead of asking for a U-turn.
     */
    suspend fun route(
        from: app.convoyage.data.LatLngD, to: app.convoyage.data.LatLngD, profile: String,
        via: List<app.convoyage.data.LatLngD> = emptyList(), headingDeg: Double? = null,
    ): ApiResult<RouteResponse>
}

/** What the ride recorder needs from the server; an interface so [app.convoyage.data.RideRecorder] can be tested. */
interface RideApi {
    suspend fun createRide(body: CreateRide): ApiResult<Ride>
    suspend fun finishRide(id: String, body: FinishRide): ApiResult<Ride>
    suspend fun getRide(id: String): ApiResult<Ride>
    suspend fun updateRide(id: String, body: UpdateRide): ApiResult<Ride>
    suspend fun deleteRide(id: String): ApiResult<OkBody>
    suspend fun uploadMedia(file: File, mime: String = "image/jpeg"): ApiResult<Media>
    suspend fun attachRidePhoto(rideId: String, body: AttachPhoto): ApiResult<RidePhoto>
}

/**
 * The REST client: OkHttp + kotlinx.serialization against protocol/openapi.json.
 *
 * Authenticated calls send `Authorization: Bearer <access>`. A 401 triggers one refresh through
 * [TokenRefresher] (single-flight, rotated refresh token saved) and one retry of the original request.
 */
class ConvoyApi(
    private val baseUrl: () -> String,
    val tokens: TokenStore,
    onSessionLost: () -> Unit = {},
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) : HazardApi, RideApi, FeedApi, RouteApi {
    val refresher = TokenRefresher(tokens, client, baseUrl, onSessionLost)
    private val http: OkHttpClient = client.newBuilder().authenticator(TokenAuthenticator(refresher)).build()
    /** Route conditions wait on Waze scans and Overpass: up to ~30 s. */
    private val slowHttp: OkHttpClient = http.newBuilder().readTimeout(70, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()

    // ---- auth (no bearer) ----------------------------------------------------------------------

    suspend fun requestMagicLink(email: String): ApiResult<MagicLinkResponse> =
        send("POST", "auth/magic-link", ApiJson.encodeToString(MagicLinkRequest.serializer(), MagicLinkRequest(email.trim())), MagicLinkResponse.serializer(), auth = false)

    suspend fun verifyMagicLink(token: String): ApiResult<AuthResult> =
        send("POST", "auth/magic-link/verify", ApiJson.encodeToString(VerifyRequest.serializer(), VerifyRequest(token.trim())), AuthResult.serializer(), auth = false)

    /** Revokes the session family of [refreshToken]. Idempotent on the server; failures are not interesting. */
    suspend fun logout(refreshToken: String): ApiResult<Unit> =
        send("POST", "auth/logout", ApiJson.encodeToString(LogoutRequest.serializer(), LogoutRequest(refreshToken)), null, auth = false)

    // ---- me --------------------------------------------------------------------------------------

    suspend fun me(): ApiResult<Me> = send("GET", "v1/me", null, Me.serializer())

    suspend fun updateMe(body: UpdateMe): ApiResult<Me> = send("PATCH", "v1/me", ApiJson.encodeToString(UpdateMe.serializer(), body), Me.serializer())

    /** Sets my profile photo to an uploaded media id, or clears it with null (sent as an explicit JSON null). */
    suspend fun setAvatar(mediaId: String?): ApiResult<Me> {
        val value = mediaId?.let { kotlinx.serialization.json.JsonPrimitive(it).toString() } ?: "null"
        return send("PATCH", "v1/me", """{"avatarMediaId":$value}""", Me.serializer())
    }

    // ---- hazards ---------------------------------------------------------------------------------

    override suspend fun createHazard(body: CreateHazard): ApiResult<Hazard> =
        send("POST", "v1/hazards", ApiJson.encodeToString(CreateHazard.serializer(), body), Hazard.serializer())

    override suspend fun listHazards(bbox: String): ApiResult<List<Hazard>> =
        send("GET", "v1/hazards", null, ListSerializer(Hazard.serializer()), query = mapOf("bbox" to bbox))

    override suspend fun confirmHazard(id: String): ApiResult<Hazard> =
        send("POST", "v1/hazards/$id/confirm", null, Hazard.serializer())

    override suspend fun dismissHazard(id: String): ApiResult<Hazard> =
        send("POST", "v1/hazards/$id/dismiss", null, Hazard.serializer())

    // ---- navigation and voice --------------------------------------------------------------------

    override suspend fun route(
        from: app.convoyage.data.LatLngD, to: app.convoyage.data.LatLngD, profile: String, via: List<app.convoyage.data.LatLngD>,
        headingDeg: Double?,
    ): ApiResult<RouteResponse> {
        fun p(l: app.convoyage.data.LatLngD) = String.format(java.util.Locale.US, "%.6f,%.6f", l.lng, l.lat)
        val q = buildMap {
            put("from", p(from))
            put("to", p(to))
            if (via.isNotEmpty()) put("via", via.joinToString(";") { p(it) })
            put("profile", if (profile == "motorcycle") "motorcycle" else "car")
            headingDeg?.let { put("heading", String.format(java.util.Locale.US, "%.0f", ((it % 360) + 360) % 360)) }
        }
        return send("GET", "v1/route", null, RouteResponse.serializer(), query = q)
    }

    /** `POST /v1/voice/token`: a LiveKit token for the room `convoy-<code>`. 501 when the server has no LiveKit. */
    suspend fun voiceToken(convoyCode: String): ApiResult<VoiceToken> =
        send("POST", "v1/voice/token", ApiJson.encodeToString(VoiceTokenRequest.serializer(), VoiceTokenRequest(convoyCode)), VoiceToken.serializer())

    // ---- vehicles --------------------------------------------------------------------------------

    /** `GET /v1/catalogue`: public and static. */
    suspend fun catalogue(): ApiResult<Catalogue> = send("GET", "v1/catalogue", null, Catalogue.serializer())

    suspend fun listVehicles(): ApiResult<List<Vehicle>> =
        send("GET", "v1/vehicles", null, ListSerializer(Vehicle.serializer()))

    suspend fun createVehicle(body: CreateVehicle): ApiResult<Vehicle> =
        send("POST", "v1/vehicles", ApiJson.encodeToString(CreateVehicle.serializer(), body), Vehicle.serializer())

    suspend fun updateVehicle(id: String, body: UpdateVehicle): ApiResult<Vehicle> =
        send("PATCH", "v1/vehicles/$id", ApiJson.encodeToString(UpdateVehicle.serializer(), body), Vehicle.serializer())

    suspend fun deleteVehicle(id: String): ApiResult<OkBody> = send("DELETE", "v1/vehicles/$id", null, OkBody.serializer())

    // ---- saved routes ----------------------------------------------------------------------------

    suspend fun listRoutes(): ApiResult<List<SavedRoute>> =
        send("GET", "v1/routes", null, ListSerializer(SavedRoute.serializer()))

    suspend fun saveRoute(body: SaveRoute): ApiResult<SavedRoute> =
        send("POST", "v1/routes", ApiJson.encodeToString(SaveRoute.serializer(), body), SavedRoute.serializer())

    suspend fun renameRoute(id: String, name: String): ApiResult<SavedRoute> =
        send("PATCH", "v1/routes/$id", """{"name":${kotlinx.serialization.json.JsonPrimitive(name)}}""", SavedRoute.serializer())

    /** visibility: "private", "followers" or "public". */
    suspend fun setRouteVisibility(id: String, visibility: String): ApiResult<SavedRoute> =
        send("PATCH", "v1/routes/$id", """{"visibility":${kotlinx.serialization.json.JsonPrimitive(visibility)}}""", SavedRoute.serializer())

    suspend fun deleteRoute(id: String): ApiResult<Unit> = send("DELETE", "v1/routes/$id", null, null)

    suspend fun liveLink(code: String): ApiResult<LiveLink> =
        send("POST", "v1/convoys/$code/live", null, LiveLink.serializer())

    suspend fun discoverRoutes(lat: Double, lng: Double, radiusKm: Int = 50): ApiResult<List<DiscoveredRoute>> =
        send("GET", "v1/routes/discover", null, ListSerializer(DiscoveredRoute.serializer()), query = mapOf("lat" to lat.toString(), "lng" to lng.toString(), "radiusKm" to radiusKm.toString()))

    /** Traffic, weather, road quality and police along a saved route. [cachedOnly]: the saved snapshot, however old. */
    suspend fun routeConditions(id: String, refresh: Boolean = false, cachedOnly: Boolean = false): ApiResult<RouteConditions> =
        send(
            "GET", "v1/routes/$id/conditions", null, RouteConditions.serializer(), slow = true,
            query = buildMap { if (refresh) put("refresh", "true"); if (cachedOnly) put("cachedOnly", "true") },
        )

    /** The same for a line that is not saved (polyline precision 6). */
    suspend fun conditions(geometry: String, durationS: Double?): ApiResult<RouteConditions> =
        send("POST", "v1/conditions", ApiJson.encodeToString(ConditionsBody.serializer(), ConditionsBody(geometry, durationS)), RouteConditions.serializer(), slow = true)

    /** Graded roads in a box as GeoJSON text (s, w, n, e; up to 0.15 degrees a side). */
    suspend fun roads(south: Double, west: Double, north: Double, east: Double): ApiResult<String> {
        val bbox = "%.5f,%.5f,%.5f,%.5f".format(java.util.Locale.ROOT, south, west, north, east)
        return when (val r = send("GET", "v1/roads", null, kotlinx.serialization.json.JsonElement.serializer(), slow = true, query = mapOf("bbox" to bbox))) {
            is ApiResult.Ok -> ApiResult.Ok(r.value.toString())
            is ApiResult.Err -> r
        }
    }

    /** Police, cameras, hazards and traffic in a box (up to 0.3 degrees a side). */
    suspend fun areaConditions(box: app.convoyage.data.Bbox): ApiResult<AreaConditionsDto> =
        send("GET", "v1/conditions/area", null, AreaConditionsDto.serializer(), query = mapOf("bbox" to "%.5f,%.5f,%.5f,%.5f".format(java.util.Locale.ROOT, box.south, box.west, box.north, box.east)))

    suspend fun photoscoutRoutes(): ApiResult<List<PsRoute>> =
        send("GET", "v1/photoscout/routes", null, ListSerializer(PsRoute.serializer()))

    suspend fun importPhotoscoutRoute(id: String): ApiResult<SavedRoute> =
        send("POST", "v1/photoscout/routes/${java.net.URLEncoder.encode(id, "UTF-8")}/import", null, SavedRoute.serializer())

    suspend fun makePrimary(id: String): ApiResult<Vehicle> = send("POST", "v1/vehicles/$id/primary", null, Vehicle.serializer())

    suspend fun markSold(id: String): ApiResult<Vehicle> =
        send("POST", "v1/vehicles/$id/sold", ApiJson.encodeToString(SoldBody.serializer(), SoldBody()), Vehicle.serializer())

    // ---- rides -----------------------------------------------------------------------------------

    override suspend fun createRide(body: CreateRide): ApiResult<Ride> =
        send("POST", "v1/rides", ApiJson.encodeToString(CreateRide.serializer(), body), Ride.serializer())

    override suspend fun finishRide(id: String, body: FinishRide): ApiResult<Ride> =
        send("POST", "v1/rides/$id/finish", ApiJson.encodeToString(FinishRide.serializer(), body), Ride.serializer())

    override suspend fun getRide(id: String): ApiResult<Ride> = send("GET", "v1/rides/$id", null, Ride.serializer())

    override suspend fun updateRide(id: String, body: UpdateRide): ApiResult<Ride> =
        send("PATCH", "v1/rides/$id", ApiJson.encodeToString(UpdateRide.serializer(), body), Ride.serializer())

    suspend fun listMyRides(before: String?, limit: Int = 50): ApiResult<RidePage> =
        send("GET", "v1/rides", null, RidePage.serializer(), query = pageQuery(before, limit))

    override suspend fun deleteRide(id: String): ApiResult<OkBody> = send("DELETE", "v1/rides/$id", null, OkBody.serializer())

    override suspend fun attachRidePhoto(rideId: String, body: AttachPhoto): ApiResult<RidePhoto> =
        send("POST", "v1/rides/$rideId/photos", ApiJson.encodeToString(AttachPhoto.serializer(), body), RidePhoto.serializer())

    // ---- media -----------------------------------------------------------------------------------

    /** `POST /v1/media`, multipart field "file". JPEG, PNG or WebP up to 15 MB. */
    override suspend fun uploadMedia(file: File, mime: String): ApiResult<Media> {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType()))
            .build()
        return send("POST", "v1/media", null, Media.serializer(), body = form)
    }

    /** The bytes behind a server-relative media path (`/v1/media/<id>` or `.../thumb`); needs the bearer token. */
    suspend fun fetchBytes(path: String): ApiResult<ByteArray> = withContext(Dispatchers.IO) {
        val target = url(path.trimStart('/'), emptyMap()) ?: return@withContext ApiResult.Err(0, "Server URL is not valid")
        val t = tokens.tokens ?: return@withContext ApiResult.Err(401, "Not signed in")
        try {
            http.newCall(Request.Builder().url(target).header("Authorization", "Bearer ${t.access}").build()).execute().use { res ->
                if (res.isSuccessful) ApiResult.Ok(res.body.bytes()) else ApiResult.Err(res.code, errorMessage(res.body.string(), res.code))
            }
        } catch (e: IOException) {
            ApiResult.Err(0, "Cannot reach the server (${e.message ?: e.javaClass.simpleName})")
        }
    }

    // ---- posts, feed, likes, comments, reports ---------------------------------------------------

    suspend fun createPost(body: CreatePost): ApiResult<Post> =
        send("POST", "v1/posts", ApiJson.encodeToString(CreatePost.serializer(), body), Post.serializer())

    suspend fun deletePost(id: String): ApiResult<OkBody> = send("DELETE", "v1/posts/$id", null, OkBody.serializer())

    override suspend fun feed(scope: FeedScope, before: String?, limit: Int, lat: Double?, lng: Double?): ApiResult<FeedPage> {
        val q = pageQuery(before, limit).toMutableMap()
        q["scope"] = scope.wire
        if (scope == FeedScope.Nearby && lat != null && lng != null) {
            q["lat"] = lat.toString()
            q["lng"] = lng.toString()
        }
        return send("GET", "v1/feed", null, FeedPage.serializer(), query = q)
    }

    override suspend fun userPosts(handle: String, before: String?, limit: Int): ApiResult<FeedPage> =
        send("GET", "v1/users/$handle/posts", null, FeedPage.serializer(), query = pageQuery(before, limit))

    suspend fun like(postId: String): ApiResult<LikeState> = send("POST", "v1/posts/$postId/like", null, LikeState.serializer())

    suspend fun unlike(postId: String): ApiResult<LikeState> = send("DELETE", "v1/posts/$postId/like", null, LikeState.serializer())

    suspend fun comments(postId: String, before: String?, limit: Int = 20): ApiResult<CommentPage> =
        send("GET", "v1/posts/$postId/comments", null, CommentPage.serializer(), query = pageQuery(before, limit))

    suspend fun addComment(postId: String, text: String): ApiResult<Comment> =
        send("POST", "v1/posts/$postId/comments", ApiJson.encodeToString(CreateComment.serializer(), CreateComment(text)), Comment.serializer())

    /** Deletes [id]: my comment, or any on my post. 404 once it is gone. */
    suspend fun deleteComment(id: String): ApiResult<OkBody> = send("DELETE", "v1/comments/$id", null, OkBody.serializer())

    suspend fun report(body: CreateReport): ApiResult<OkBody> =
        send("POST", "v1/reports", ApiJson.encodeToString(CreateReport.serializer(), body), OkBody.serializer())

    private fun pageQuery(before: String?, limit: Int): Map<String, String> =
        buildMap { put("limit", limit.toString()); if (before != null) put("before", before) }

    // ---- socket support --------------------------------------------------------------------------

    /**
     * An access token that is good for the next minute, refreshing first if it is about to run out
     * (or [force]: the server just refused the one we have). Null when signed out or the refresh failed.
     */
    suspend fun freshAccessToken(force: Boolean = false): String? = withContext(Dispatchers.IO) {
        val current = tokens.tokens ?: return@withContext null
        if (!force && !Jwt.expiresWithin(current.access, 60)) return@withContext current.access
        refresher.refresh(current.access) ?: current.access.takeIf { !force && !Jwt.expiresWithin(it, 0) }
    }

    // ---- PhotoScout map layers --------------------------------------------------------------------

    suspend fun photoscoutStatus(): ApiResult<PhotoScoutStatus> = send("GET", "v1/photoscout/status", null, PhotoScoutStatus.serializer())

    /** The PhotoScout consent page to open in the browser; it comes back to convoyage://photoscout/linked. */
    suspend fun photoscoutConnect(): ApiResult<PhotoScoutAuthorizeUrl> = send("GET", "v1/photoscout/connect", null, PhotoScoutAuthorizeUrl.serializer())

    suspend fun photoscoutUnlink(): ApiResult<OkBody> = send("DELETE", "v1/photoscout/link", null, OkBody.serializer())

    /** Spots inside [box]. */
    suspend fun photoscoutSpots(box: app.convoyage.data.Bbox): ApiResult<List<PsSpot>> =
        send("GET", "v1/photoscout/spots", null, ListSerializer(PsSpot.serializer()), query = mapOf("bbox" to "${box.south},${box.west},${box.north},${box.east}"))

    /** Upcoming events within [radiusKm] of [lat],[lng], soonest first. */
    suspend fun photoscoutEvents(lat: Double, lng: Double, radiusKm: Double): ApiResult<List<PsEvent>> =
        send("GET", "v1/photoscout/events", null, ListSerializer(PsEvent.serializer()), query = mapOf("near" to "$lat,$lng", "radiusKm" to radiusKm.toString()))

    suspend fun photoscoutRsvp(eventId: String, body: PsRsvpRequest): ApiResult<PsRsvp> =
        send(
            "POST", "v1/photoscout/events/${java.net.URLEncoder.encode(eventId, "UTF-8")}/rsvp",
            ApiJson.encodeToString(PsRsvpRequest.serializer(), body), PsRsvp.serializer(),
        )

    // ---- push devices ----------------------------------------------------------------------------

    /** Registers this device for notifications: a UnifiedPush endpoint and its Web Push keys. Idempotent. */
    suspend fun registerPushDevice(device: PushDevice): ApiResult<OkBody> =
        send("POST", "v1/me/devices", ApiJson.encodeToString(PushDevice.serializer(), device), OkBody.serializer())

    /** Stops notifications to [token] (signed out, or notifications turned off). */
    suspend fun forgetPushDevice(token: String): ApiResult<OkBody> =
        send("DELETE", "v1/me/devices", ApiJson.encodeToString(PushDeviceToken.serializer(), PushDeviceToken(token)), OkBody.serializer())

    // ---- plumbing --------------------------------------------------------------------------------

    private fun url(path: String, query: Map<String, String>): HttpUrl? =
        baseUrl().trim().trimEnd('/').toHttpUrlOrNull()?.newBuilder()?.addPathSegments(path)?.apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }?.build()

    /** One request. A null [responseSerializer] means "no body expected": the result is [Unit]. */
    private suspend fun <R> send(
        method: String,
        path: String,
        payload: String?,
        responseSerializer: KSerializer<R>?,
        auth: Boolean = true,
        query: Map<String, String> = emptyMap(),
        /** Replaces [payload] (a multipart upload). */
        body: RequestBody? = null,
        slow: Boolean = false,
    ): ApiResult<R> = withContext(Dispatchers.IO) {
        val target = url(path, query) ?: return@withContext ApiResult.Err(0, "Server URL is not valid")
        val builder = Request.Builder().url(target).header("Accept", "application/json")
        // A bodiless POST (confirm/dismiss) must carry NO content-type: Fastify answers 400
        // "Body cannot be empty when content-type is set to application/json" otherwise.
        builder.method(method, body ?: payload?.toRequestBody(jsonType) ?: if (method == "GET") null else ByteArray(0).toRequestBody(null))
        if (auth) {
            val t = tokens.tokens ?: return@withContext ApiResult.Err(401, "Not signed in")
            builder.header("Authorization", "Bearer ${t.access}")
        }
        try {
            (if (slow) slowHttp else http).newCall(builder.build()).execute().use { res ->
                val text = res.body.string()
                if (!res.isSuccessful) {
                    ApiResult.Err(res.code, errorMessage(text, res.code))
                } else if (responseSerializer == null) {
                    @Suppress("UNCHECKED_CAST")
                    ApiResult.Ok(Unit as R)
                } else {
                    ApiResult.Ok(ApiJson.decodeFromString(responseSerializer, text))
                }
            }
        } catch (e: IOException) {
            ApiResult.Err(0, "Cannot reach the server (${e.message ?: e.javaClass.simpleName})")
        } catch (e: kotlinx.serialization.SerializationException) {
            ApiResult.Err(502, "Unexpected response from the server")
        }
    }

    companion object {
        /** `message` from the standard error body, else a generic line for the status. */
        fun errorMessage(body: String, status: Int): String {
            val parsed = try {
                ApiJson.decodeFromString(ApiError.serializer(), body).message.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
            return parsed ?: "Server answered $status"
        }
    }
}
