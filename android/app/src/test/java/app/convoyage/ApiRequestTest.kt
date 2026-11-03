package app.convoyage

import app.convoyage.api.ApiResult
import app.convoyage.api.ConvoyApi
import app.convoyage.api.CreateHazard
import app.convoyage.api.InMemoryTokenStore
import app.convoyage.api.Tokens
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** What actually goes on the wire, checked against what the Fastify server accepts. */
class ApiRequestTest {
    private lateinit var server: MockWebServer

    private val hazardJson = """{"id":"h1","kind":"hazard","subkind":"","lat":1.0,"lng":2.0,"heading":null,"createdAt":"2026-11-03T04:00:00.000Z","expiresAt":"2026-11-03T04:30:00.000Z","confirmations":1,"dismissals":0,"myAction":"confirm"}"""

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private fun api() = ConvoyApi({ server.url("/").toString() }, InMemoryTokenStore(Tokens("tok", "ref")))

    @Test
    fun confirmAndDismissSendNoContentTypeBecauseThereIsNoBody() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(hazardJson).build())
        server.enqueue(MockResponse.Builder().code(200).body(hazardJson).build())
        assertTrue(api().confirmHazard("h1") is ApiResult.Ok)
        assertTrue(api().dismissHazard("h1") is ApiResult.Ok)

        for (path in listOf("/v1/hazards/h1/confirm", "/v1/hazards/h1/dismiss")) {
            val r = server.takeRequest()
            assertEquals("POST", r.method)
            assertEquals(path, r.url.encodedPath)
            assertNull("Fastify rejects an empty body that claims to be JSON", r.headers["Content-Type"])
            assertEquals("Bearer tok", r.headers["Authorization"])
        }
    }

    @Test
    fun createHazardPostsJsonAndListSendsTheBbox() = runBlocking {
        server.enqueue(MockResponse.Builder().code(201).body(hazardJson).build())
        server.enqueue(MockResponse.Builder().code(200).body("[$hazardJson]").build())
        api().createHazard(CreateHazard("hazard", "oil", 1.0, 2.0, 10.0))
        val list = api().listHazards("-1.00000,1.00000,3.00000,3.00000")

        val create = server.takeRequest()
        assertTrue(create.headers["Content-Type"]!!.startsWith("application/json"))
        assertEquals("""{"kind":"hazard","subkind":"oil","lat":1.0,"lng":2.0,"heading":10.0}""", create.body?.utf8())
        assertEquals("/v1/hazards", server.takeRequest().url.encodedPath)
        assertEquals(1, (list as ApiResult.Ok).value.size)
    }
}
