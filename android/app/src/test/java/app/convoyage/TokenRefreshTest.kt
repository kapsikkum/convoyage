package app.convoyage

import app.convoyage.api.ApiResult
import app.convoyage.api.ConvoyApi
import app.convoyage.api.InMemoryTokenStore
import app.convoyage.api.Jwt
import app.convoyage.api.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * The server rotates refresh tokens and revokes the whole session if an old one is replayed, so the client must
 * (1) save the new refresh token every time, (2) never refresh twice for the same expiry, (3) leave the session
 * alone when the failure is only the network.
 */
class TokenRefreshTest {
    private lateinit var server: MockWebServer
    private lateinit var fake: FakeAuthServer

    /** A tiny stand-in for server/src/api/tokens.ts: one live refresh token at a time, old ones are theft. */
    private class FakeAuthServer : Dispatcher() {
        val refreshCalls = AtomicInteger(0)
        val meCalls = AtomicInteger(0)
        val bodies = mutableListOf<String>()
        private var generation = 0

        @Volatile var access = "access-0"
        @Volatile var refresh = "refresh-0"
        @Volatile var refreshStatus = 0 // non-zero: answer /auth/refresh with this status
        @Volatile var revoked = false

        override fun dispatch(request: RecordedRequest): MockResponse {
            return when (request.url.encodedPath) {
                "/v1/me" -> {
                    meCalls.incrementAndGet()
                    if (!revoked && request.headers["Authorization"] == "Bearer $access") json(200, """{"id":"u1","handle":"alex","displayName":"Alex"}""")
                    else json(401, """{"statusCode":401,"error":"Unauthorized","message":"Invalid token"}""")
                }
                "/auth/refresh" -> synchronized(this) {
                    refreshCalls.incrementAndGet()
                    val body = request.body?.utf8().orEmpty()
                    bodies += body
                    if (refreshStatus != 0) return json(refreshStatus, """{"statusCode":$refreshStatus,"error":"x","message":"nope"}""")
                    if (revoked || !body.contains("\"$refresh\"")) {
                        revoked = true // replaying an old refresh token kills the session
                        json(401, """{"statusCode":401,"error":"Unauthorized","message":"Invalid refresh token"}""")
                    } else {
                        generation++
                        access = "access-$generation"
                        refresh = "refresh-$generation"
                        json(200, """{"accessToken":"$access","refreshToken":"$refresh","tokenType":"Bearer","expiresIn":900}""")
                    }
                }
                "/auth/magic-link/verify" -> json(401, """{"statusCode":401,"error":"Unauthorized","message":"Invalid or expired link"}""")
                else -> MockResponse.Builder().code(404).build()
            }
        }

        private fun json(code: Int, body: String) =
            MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
    }

    @Before
    fun setUp() {
        fake = FakeAuthServer()
        server = MockWebServer()
        server.dispatcher = fake
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun api(store: InMemoryTokenStore, lost: AtomicInteger = AtomicInteger()) =
        ConvoyApi(baseUrl = { server.url("/").toString() }, tokens = store, onSessionLost = { lost.incrementAndGet() })

    @Test
    fun expiredAccessTokenIsRefreshedAndTheRequestRetried() = runBlocking {
        val store = InMemoryTokenStore(Tokens("stale-access", "refresh-0"))
        val r = api(store).me()

        assertTrue("expected Ok, got $r", r is ApiResult.Ok)
        assertEquals("Alex", (r as ApiResult.Ok).value.displayName)
        assertEquals(1, fake.refreshCalls.get())
        assertEquals(2, fake.meCalls.get()) // the 401, then the retry
        // The rotated pair is what is stored: the NEW refresh token, not the one we sent.
        assertEquals(Tokens("access-1", "refresh-1"), store.tokens)
    }

    @Test
    fun refreshSendsTheStoredRefreshTokenAndRotatesEveryTime() = runBlocking {
        val store = InMemoryTokenStore(Tokens("stale-access", "refresh-0"))
        val api = api(store)
        api.me()
        assertTrue(fake.bodies.single().contains("\"refreshToken\":\"refresh-0\""))

        // Expire again: the second refresh must use refresh-1, never refresh-0 (that would be a replay).
        fake.access = "access-secret-2"
        api.me()
        assertEquals(2, fake.refreshCalls.get())
        assertTrue(fake.bodies[1].contains("\"refreshToken\":\"refresh-1\""))
        assertEquals("refresh-2", store.tokens?.refresh)
        assertFalse("the session must not have been revoked", fake.revoked)
    }

    @Test
    fun parallelRequestsThatAllGet401RefreshOnlyOnce() = runBlocking {
        val store = InMemoryTokenStore(Tokens("stale-access", "refresh-0"))
        val lost = AtomicInteger()
        val api = api(store, lost)

        val results = (1..12).map { async(Dispatchers.IO) { api.me() } }.awaitAll()

        assertTrue("all requests succeed: $results", results.all { it is ApiResult.Ok })
        assertEquals("one rotation for the whole burst", 1, fake.refreshCalls.get())
        assertFalse("replaying the old refresh token would have revoked the session", fake.revoked)
        assertEquals(0, lost.get())
        assertEquals(Tokens("access-1", "refresh-1"), store.tokens)
    }

    @Test
    fun aRefusedRefreshTokenEndsTheSession() = runBlocking {
        fake.revoked = true
        val store = InMemoryTokenStore(Tokens("stale-access", "refresh-0"))
        val lost = AtomicInteger()
        val r = api(store, lost).me()

        assertTrue(r is ApiResult.Err && r.isUnauthorized)
        assertNull("tokens are cleared", store.tokens)
        assertEquals(1, lost.get())
    }

    @Test
    fun aServerErrorDuringRefreshKeepsTheSession() = runBlocking {
        fake.refreshStatus = 503
        val store = InMemoryTokenStore(Tokens("stale-access", "refresh-0"))
        val lost = AtomicInteger()
        val r = api(store, lost).me()

        assertTrue(r is ApiResult.Err)
        assertEquals(Tokens("stale-access", "refresh-0"), store.tokens) // try again later
        assertEquals(0, lost.get())
    }

    @Test
    fun requestsWithoutABearerNeverTriggerARefresh() = runBlocking {
        val store = InMemoryTokenStore(Tokens("a", "r"))
        val r = api(store).verifyMagicLink("x".repeat(30)) // 401 from the server, but not an expired session
        assertTrue(r is ApiResult.Err && r.status == 401)
        assertEquals(0, fake.refreshCalls.get())
        assertEquals(Tokens("a", "r"), store.tokens)
    }

    @Test
    fun signedOutCallsFailLocallyWithout401Traffic() = runBlocking {
        val r = api(InMemoryTokenStore(null)).me()
        assertTrue(r is ApiResult.Err && r.isUnauthorized)
        assertEquals(0, fake.meCalls.get())
    }

    // ---- socket token freshness -----------------------------------------------------------------

    private fun jwt(expEpochSeconds: Long): String {
        fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        return b64("""{"alg":"HS256"}""") + "." + b64("""{"sub":"u1","exp":$expEpochSeconds}""") + ".sig"
    }

    @Test
    fun jwtExpiryIsReadFromTheTokenPayload() {
        assertEquals(1_900_000_000L, Jwt.expiresAtSeconds(jwt(1_900_000_000L)))
        assertNull(Jwt.expiresAtSeconds("not-a-jwt"))
        val now = 1_000_000_000_000L
        assertTrue(Jwt.expiresWithin(jwt(1_000_000_030L), 60, now))
        assertFalse(Jwt.expiresWithin(jwt(1_000_000_300L), 60, now))
        assertTrue("an unreadable token counts as expiring", Jwt.expiresWithin("junk", 60, now))
    }

    @Test
    fun socketGetsTheStoredTokenWhileItIsFresh() = runBlocking {
        val fresh = jwt(System.currentTimeMillis() / 1000 + 600)
        val store = InMemoryTokenStore(Tokens(fresh, "refresh-0"))
        assertEquals(fresh, api(store).freshAccessToken())
        assertEquals(0, fake.refreshCalls.get())
    }

    @Test
    fun socketRefreshesATokenThatIsAboutToExpire() = runBlocking {
        val store = InMemoryTokenStore(Tokens(jwt(System.currentTimeMillis() / 1000 + 20), "refresh-0"))
        val token = api(store).freshAccessToken()
        assertEquals("access-1", token)
        assertEquals(1, fake.refreshCalls.get())
        assertNotNull(store.tokens)
        assertEquals("refresh-1", store.tokens?.refresh)
    }

    @Test
    fun forcedRefreshAfterA401OnTheSocket() = runBlocking {
        val fresh = jwt(System.currentTimeMillis() / 1000 + 600)
        val store = InMemoryTokenStore(Tokens(fresh, "refresh-0"))
        assertEquals("access-1", api(store).freshAccessToken(force = true))
    }
}
