package app.convoyage

import app.convoyage.api.ConvoyApi
import app.convoyage.api.InMemoryTokenStore
import app.convoyage.api.PsGoodTimes
import app.convoyage.api.PsSpot
import app.convoyage.api.Tokens
import app.convoyage.data.Bbox
import app.convoyage.data.PhotoScoutLayers
import app.convoyage.ui.goodTimesLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoScoutLayersTest {
    private lateinit var server: MockWebServer

    /**
     * Answers by path, not by arrival order: the repository fires the spots and events requests at the same time, and
     * a positional queue would hand each one the other's body. Tests put a body in as they would enqueue it, and
     * change it when the same path must answer differently a second time.
     */
    private lateinit var route: ConcurrentHashMap<String, String>

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = route[request.url.encodedPath] ?: return MockResponse.Builder().code(404).build()
            return MockResponse.Builder().code(200).body(body).build()
        }
    }

    @Before
    fun setUp() {
        route = ConcurrentHashMap()
        server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
    }

    @After fun tearDown() { server.close() }

    private val spotJson = """{"id":"s1","name":"Mount Panorama lookout","notes":"","lat":-33.44,"lng":149.56,"tags":["sunset"],"facingDeg":null,"fovDeg":null,
        "goodTimes":{"phases":["golden"],"months":[5,6,7,8],"days":"","conditions":[],"avoid":"","notes":""},"photoCount":3,"photo":null}"""
    private val eventJson = """{"id":"0b6f0d6e-1111-4c1b-9e4b-2d5c1a7e9f00","source":"community","title":"Sunday cruise","description":"","startTime":"2026-11-08T21:00:00.000Z",
        "endTime":null,"venue":"Bathurst","address":"","lat":-33.42,"lng":149.58,"imageUrl":"","url":"","going":4,"interested":0,"myRsvp":null,
        "cruise":{"routeName":"Panorama loop","meet":{"lat":-33.41,"lng":149.57,"note":"Servo car park"},"rollOut":"9:30"},"distanceKm":2.1}"""

    private fun layers(scope: TestScope, clock: () -> Long) =
        PhotoScoutLayers(scope, ConvoyApi({ server.url("/").toString() }, InMemoryTokenStore(Tokens("tok", "ref"))), now = clock)

    /** The repository launches on Dispatchers.IO underneath; wait for the state rather than for coroutines. */
    private fun waitFor(cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue("timed out", cond())
    }

    @Test
    fun loadsOnlyWhenLinkedThenRefetchesOnlyWhenTheViewLeavesTheLoadedBox() {
        val scope = TestScope(UnconfinedTestDispatcher())
        var now = 0L
        val l = layers(scope) { now }
        val view = Bbox(-33.45, 149.55, -33.40, 149.60)

        // Not linked yet: nothing is asked for.
        l.onViewport(view, spots = true, events = true)
        route["/v1/photoscout/status"] = """{"configured":true,"linked":true,"username":"sam","scope":"read","linkedAt":null}"""
        l.refreshStatus()
        waitFor { l.state.value.available }
        assertEquals("/v1/photoscout/status", server.takeRequest(1, TimeUnit.SECONDS)!!.url.encodedPath)

        route["/v1/photoscout/spots"] = "[$spotJson]"
        route["/v1/photoscout/events"] = "[$eventJson]"
        l.onViewport(view, spots = true, events = true)
        waitFor { l.state.value.spots.size == 1 && l.state.value.events.size == 1 }
        // Both requests are in flight at once, so only the set of paths they hit is fixed.
        val paths = listOf(server.takeRequest(1, TimeUnit.SECONDS)!!, server.takeRequest(1, TimeUnit.SECONDS)!!).associateBy { it.url.encodedPath }
        assertEquals(setOf("/v1/photoscout/spots", "/v1/photoscout/events"), paths.keys)
        assertTrue(paths["/v1/photoscout/spots"]!!.url.queryParameter("bbox")!!.split(",").size == 4)
        assertTrue(paths["/v1/photoscout/events"]!!.url.queryParameter("radiusKm")!!.toDouble() >= 50.0)

        // A small pan inside the margin: no new requests.
        l.onViewport(Bbox(-33.44, 149.56, -33.39, 149.61), spots = true, events = true)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))

        // Five minutes later the same view is stale again (spots only on now).
        now += 6 * 60_000
        route["/v1/photoscout/spots"] = "[]"
        l.onViewport(view, spots = true, events = false)
        waitFor { l.state.value.spots.isEmpty() }
        assertEquals("/v1/photoscout/spots", server.takeRequest(1, TimeUnit.SECONDS)!!.url.encodedPath)
    }

    @Test
    fun notLinkedHidesTheLayers() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val l = layers(scope) { 0L }
        route["/v1/photoscout/status"] = """{"configured":true,"linked":false,"username":null,"scope":"","linkedAt":null}"""
        l.refreshStatus()
        server.takeRequest(1, TimeUnit.SECONDS)
        Thread.sleep(100)
        assertEquals(false, l.state.value.available)
    }

    @Test
    fun rsvpPostsAndUpdatesTheEventInPlace() = runBlocking {
        val scope = TestScope(UnconfinedTestDispatcher())
        val l = layers(scope) { 0L }
        route["/v1/photoscout/status"] = """{"configured":true,"linked":true,"username":null,"scope":"","linkedAt":null}"""
        l.refreshStatus(); waitFor { l.state.value.available }
        route["/v1/photoscout/events"] = "[$eventJson]"
        l.onViewport(Bbox(-33.45, 149.55, -33.40, 149.60), spots = false, events = true)
        waitFor { l.state.value.events.isNotEmpty() }
        server.takeRequest(1, TimeUnit.SECONDS); server.takeRequest(1, TimeUnit.SECONDS)

        val rsvpPath = "/v1/photoscout/events/0b6f0d6e-1111-4c1b-9e4b-2d5c1a7e9f00/rsvp"
        route[rsvpPath] = """{"eventId":"x","source":"community","going":5,"interested":0,"myRsvp":"going"}"""
        assertNull(l.rsvp(l.state.value.events[0], "going"))
        val r = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals(rsvpPath, r.url.encodedPath)
        assertEquals("""{"status":"going"}""", r.body?.utf8())
        assertEquals(5, l.state.value.events[0].going)
        assertEquals("going", l.state.value.events[0].myRsvp)

        route[rsvpPath] = """{"eventId":"x","source":"community","going":4,"interested":0,"myRsvp":null}"""
        l.rsvp(l.state.value.events[0], null)
        assertEquals("""{"going":false}""", server.takeRequest(1, TimeUnit.SECONDS)!!.body?.utf8())
        assertNull(l.state.value.events[0].myRsvp)
    }

    @Test
    fun goodTimesSummary() {
        fun spot(g: PsGoodTimes) = PsSpot("s", "S", lat = 0.0, lng = 0.0, goodTimes = g)
        assertEquals("GOLDEN HOUR · MAY-AUG", goodTimesLine(spot(PsGoodTimes(phases = listOf("golden"), months = listOf(8, 5, 6, 7)))))
        assertEquals("SUNSET · JAN DEC · FOG", goodTimesLine(spot(PsGoodTimes(phases = listOf("sunset"), months = listOf(12, 1), conditions = listOf("fog")))))
        assertNull(goodTimesLine(spot(PsGoodTimes(months = (1..12).toList()))))
    }
}
