package app.convoyage.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

sealed interface MagicLinkResult {
    data object Sent : MagicLinkResult

    /** The server has no email sign-in yet (404/501/connection refused). Use Dev sign-in. */
    data class NotReady(val detail: String) : MagicLinkResult
    data class Failed(val message: String) : MagicLinkResult
}

/**
 * Email magic-link sign-in.
 *
 * TODO(auth): the server has no auth endpoints yet (server/src/auth.ts is dev-only). This posts to
 * `POST <server>/auth/magic-link {"email": "..."}`, which is the shape we expect; adjust once the
 * server defines it, and handle the deep link that completes sign-in (app.convoyage://auth?token=...).
 */
class AuthApi(private val client: OkHttpClient = OkHttpClient()) {
    suspend fun requestMagicLink(baseUrl: String, email: String): MagicLinkResult = withContext(Dispatchers.IO) {
        val url = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()?.newBuilder()?.addPathSegments("auth/magic-link")?.build()
            ?: return@withContext MagicLinkResult.Failed("Server URL is not valid")
        val body = JSONObject().put("email", email.trim()).toString().toRequestBody("application/json".toMediaType())
        try {
            client.newCall(Request.Builder().url(url).post(body).build()).execute().use { res ->
                when {
                    res.isSuccessful -> MagicLinkResult.Sent
                    res.code == 404 || res.code == 501 || res.code == 405 -> MagicLinkResult.NotReady("Server answered ${res.code}")
                    else -> MagicLinkResult.Failed("Server answered ${res.code}")
                }
            }
        } catch (e: Exception) {
            MagicLinkResult.NotReady(e.message ?: "Could not reach the server")
        }
    }
}
