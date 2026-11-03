package app.convoyage.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** An access JWT and the refresh token that goes with it. */
data class Tokens(val access: String, val refresh: String)

/** Where the tokens live. Reads must be cheap (they happen on every request); writes must be durable. */
interface TokenStore {
    var tokens: Tokens?
}

class InMemoryTokenStore(initial: Tokens? = null) : TokenStore {
    @Volatile
    override var tokens: Tokens? = initial
}

/** Just enough JWT reading to know when an access token runs out. The signature is the server's business. */
object Jwt {
    /** The `exp` claim in epoch seconds, or null if the token is not a readable JWT. */
    fun expiresAtSeconds(jwt: String): Long? = try {
        val part = jwt.split('.').getOrNull(1) ?: return null
        val payload = String(decodeBase64Url(part), Charsets.UTF_8)
        val obj: JsonObject = Json.parseToJsonElement(payload).jsonObject
        obj["exp"]?.jsonPrimitive?.longOrNull
    } catch (_: Exception) {
        null
    }

    /** True if the token is expired or will be within [seconds]. Unreadable tokens count as expiring. */
    fun expiresWithin(jwt: String, seconds: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        val exp = expiresAtSeconds(jwt) ?: return true
        return exp * 1000 - nowMs <= seconds * 1000
    }

    private fun decodeBase64Url(s: String): ByteArray = java.util.Base64.getUrlDecoder().decode(s)
}
