package app.convoyage.api

import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.io.IOException

/**
 * Exchanges the refresh token for a new pair, one caller at a time.
 *
 * The server rotates refresh tokens and treats a second use of an old one as theft (it revokes the whole
 * session family). So two requests that both get a 401 must NOT both refresh: the first one rotates, the
 * second finds that the stored access token is no longer the one it sent and simply reuses the new one.
 * Every successful rotation is written to [store] before anyone sees the new access token.
 *
 * Blocking on purpose: OkHttp calls [Authenticator]s on its own threads.
 */
class TokenRefresher(
    private val store: TokenStore,
    private val http: OkHttpClient,
    private val baseUrl: () -> String,
    /** Called (once per lost session) when the server says the refresh token is no good. Tokens are already cleared. */
    private val onSessionLost: () -> Unit = {},
) {
    private val lock = Any()

    /** Number of /auth/refresh calls made; for tests. */
    @Volatile
    var refreshCalls = 0
        private set

    /**
     * Returns an access token that is not [stale], refreshing if [stale] is still the current one.
     * Null when there is nothing to refresh with, the server refused (session lost) or it was unreachable.
     */
    fun refresh(stale: String?): String? = synchronized(lock) {
        val current = store.tokens ?: return null
        if (stale != null && current.access != stale) return current.access // another caller already rotated

        val url = baseUrl().trim().trimEnd('/').toHttpUrlOrNull()?.newBuilder()?.addPathSegments("auth/refresh")?.build()
            ?: return null
        val body = ApiJson.encodeToString(RefreshRequest.serializer(), RefreshRequest(current.refresh))
            .toRequestBody("application/json".toMediaType())
        refreshCalls++
        try {
            http.newCall(Request.Builder().url(url).post(body).build()).execute().use { res ->
                val text = res.body.string()
                when {
                    res.isSuccessful -> {
                        val pair = ApiJson.decodeFromString(TokenPair.serializer(), text)
                        store.tokens = Tokens(pair.accessToken, pair.refreshToken) // always keep the NEW refresh token
                        pair.accessToken
                    }
                    res.code == 400 || res.code == 401 || res.code == 403 -> {
                        store.tokens = null
                        onSessionLost()
                        null
                    }
                    else -> null // 429 / 5xx: try again later, keep the tokens
                }
            }
        } catch (_: IOException) {
            null // offline: keep the tokens
        } catch (_: kotlinx.serialization.SerializationException) {
            null
        }
    }
}

/** OkHttp hook: on a 401 for a request that carried a bearer token, refresh once and retry. */
class TokenAuthenticator(private val refresher: TokenRefresher) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val sent = response.request.header("Authorization")?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (generateSequence(response) { it.priorResponse }.count() > 1) return null // already retried once
        val fresh = refresher.refresh(sent) ?: return null
        return response.request.newBuilder().header("Authorization", "Bearer $fresh").build()
    }
}
