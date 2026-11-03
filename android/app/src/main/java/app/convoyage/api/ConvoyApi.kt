package app.convoyage.api

import app.convoyage.protocol.Hazard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
) : HazardApi {
    val refresher = TokenRefresher(tokens, client, baseUrl, onSessionLost)
    private val http: OkHttpClient = client.newBuilder().authenticator(TokenAuthenticator(refresher)).build()
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

    // ---- hazards ---------------------------------------------------------------------------------

    override suspend fun createHazard(body: CreateHazard): ApiResult<Hazard> =
        send("POST", "v1/hazards", ApiJson.encodeToString(CreateHazard.serializer(), body), Hazard.serializer())

    override suspend fun listHazards(bbox: String): ApiResult<List<Hazard>> =
        send("GET", "v1/hazards", null, ListSerializer(Hazard.serializer()), query = mapOf("bbox" to bbox))

    override suspend fun confirmHazard(id: String): ApiResult<Hazard> =
        send("POST", "v1/hazards/$id/confirm", null, Hazard.serializer())

    override suspend fun dismissHazard(id: String): ApiResult<Hazard> =
        send("POST", "v1/hazards/$id/dismiss", null, Hazard.serializer())

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
    ): ApiResult<R> = withContext(Dispatchers.IO) {
        val target = url(path, query) ?: return@withContext ApiResult.Err(0, "Server URL is not valid")
        val builder = Request.Builder().url(target).header("Accept", "application/json")
        // A bodiless POST (confirm/dismiss) must carry NO content-type: Fastify answers 400
        // "Body cannot be empty when content-type is set to application/json" otherwise.
        builder.method(method, payload?.toRequestBody(jsonType) ?: if (method == "GET") null else ByteArray(0).toRequestBody(null))
        if (auth) {
            val t = tokens.tokens ?: return@withContext ApiResult.Err(401, "Not signed in")
            builder.header("Authorization", "Bearer ${t.access}")
        }
        try {
            http.newCall(builder.build()).execute().use { res ->
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
