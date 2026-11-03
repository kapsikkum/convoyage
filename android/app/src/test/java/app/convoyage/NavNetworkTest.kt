package app.convoyage

import app.convoyage.api.ApiResult
import app.convoyage.api.ConvoyApi
import app.convoyage.api.InMemoryTokenStore
import app.convoyage.api.Tokens
import app.convoyage.data.LatLngD
import app.convoyage.nav.NominatimSearch
import app.convoyage.protocol.ClientMessage
import app.convoyage.protocol.ProtocolJson
import app.convoyage.protocol.ServerMessage
import app.convoyage.voice.PttView
import app.convoyage.voice.Talker
import app.convoyage.voice.VoiceState
import app.convoyage.voice.VoiceStatus
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NavNetworkTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private fun base() = server.url("/").toString().trimEnd('/')

    private val nominatim = """
        [{"place_id":1,"lat":"-33.4470","lon":"149.5580","name":"Mount Panorama Circuit","display_name":"Mount Panorama Circuit, Panorama Avenue, Bathurst, Bathurst Regional, New South Wales, 2795, Australia"},
         {"lat":"-33.42","lon":"149.57","name":"","display_name":"Pit Straight, Bathurst, NSW"},
         {"lat":"oops","lon":"1","display_name":"Broken"}]
    """.trimIndent()

    @Test
    fun parsesNominatimResults() {
        val places = NominatimSearch.parseSearch(nominatim)
        assertEquals(2, places.size)
        assertEquals("Mount Panorama Circuit", places[0].name)
        assertEquals("Panorama Avenue, Bathurst, Bathurst Regional", places[0].detail)
        assertEquals(-33.4470, places[0].lat, 1e-9)
        assertEquals(149.5580, places[0].lng, 1e-9)
        assertEquals("Pit Straight", places[1].name)
    }

    @Test
    fun searchSendsTheAgentAndBiasesToTheUsersArea() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(nominatim).build())
        val search = NominatimSearch(baseUrl = base(), minGapMs = 0)
        val r = search.search("mount pan", LatLngD(-33.41, 149.58)) as ApiResult.Ok
        assertEquals(2, r.value.size)
        val req = server.takeRequest()
        assertEquals("Convoyage/0.1", req.headers["User-Agent"])
        assertEquals("/search", req.url.encodedPath)
        assertEquals("jsonv2", req.url.queryParameter("format"))
        assertEquals("mount pan", req.url.queryParameter("q"))
        // left,top,right,bottom as lon,lat around the user, not bounded.
        assertEquals("148.0800,-31.9100,151.0800,-34.9100", req.url.queryParameter("viewbox"))
        assertNull(req.url.queryParameter("bounded"))
    }

    @Test
    fun requestsAreSpacedOutToOneASecond() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("[]").build())
        server.enqueue(MockResponse.Builder().code(200).body("[]").build())
        val search = NominatimSearch(baseUrl = base(), minGapMs = 300)
        val t0 = System.currentTimeMillis()
        search.search("one", null)
        search.search("two", null)
        assertTrue("the second call waited for the gap", System.currentTimeMillis() - t0 >= 290)
    }

    @Test
    fun aShortQueryAsksNothing() = runBlocking {
        val r = NominatimSearch(baseUrl = base()).search("a", null) as ApiResult.Ok
        assertTrue(r.value.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aBusyServiceIsReportedNotCrashed() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).body("slow down").build())
        val r = NominatimSearch(baseUrl = base(), minGapMs = 0).search("bathurst", null)
        assertEquals(429, (r as ApiResult.Err).status)
    }

    // ---- /v1/route and /v1/voice/token ---------------------------------------------------------

    private fun api() = ConvoyApi({ base() }, InMemoryTokenStore(Tokens("tok", "ref")))

    private val routeBody = """
        {"profile":"motorcycle","geometry":"_izlhA~rlgdF_{geC~ywl@","geometryPrecision":6,"distanceM":1200.5,"durationS":90,
         "steps":[{"maneuver":{"type":"depart","modifier":"north","location":[149.5,-33.5]},"name":"Conrod Straight","distanceM":1200.5,"durationS":90,"instruction":"Head north"},
                  {"maneuver":{"type":"arrive","modifier":null,"location":[149.55,-33.45]},"name":"","distanceM":0,"durationS":0,"instruction":"You have arrived"}]}
    """.trimIndent()

    @Test
    fun routeRequestMatchesTheContract() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(routeBody).build())
        val r = api().route(LatLngD(-33.5, 149.5), LatLngD(-33.447, 149.558), "motorcycle", listOf(LatLngD(-33.48, 149.53))) as ApiResult.Ok
        assertEquals(2, r.value.steps.size)
        assertEquals(null, r.value.steps[1].maneuver.modifier)
        assertEquals(1200.5, r.value.distanceM, 1e-9)
        val req = server.takeRequest()
        assertEquals("/v1/route", req.url.encodedPath)
        assertEquals("149.500000,-33.500000", req.url.queryParameter("from"))
        assertEquals("149.558000,-33.447000", req.url.queryParameter("to"))
        assertEquals("149.530000,-33.480000", req.url.queryParameter("via"))
        assertEquals("motorcycle", req.url.queryParameter("profile"))
        assertEquals("Bearer tok", req.headers["Authorization"])
    }

    @Test
    fun voiceTokenIsPostedWithTheConvoyCode() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"token":"jwt","url":"ws://127.0.0.1:7880","room":"convoy-K7QXM2PR","identity":"u1","expiresIn":600}""").build())
        val r = api().voiceToken("K7QXM2PR") as ApiResult.Ok
        assertEquals("ws://127.0.0.1:7880", r.value.url)
        assertEquals("convoy-K7QXM2PR", r.value.room)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/voice/token", req.url.encodedPath)
        assertEquals("""{"convoyCode":"K7QXM2PR"}""", req.body?.utf8())
    }

    @Test
    fun aServerWithoutLiveKitAnswers501() = runBlocking {
        server.enqueue(MockResponse.Builder().code(501).body("""{"error":"not_configured","message":"Voice is not configured"}""").build())
        val r = api().voiceToken("K7QXM2PR") as ApiResult.Err
        assertEquals(501, r.status)
    }

    // ---- protocol additions ---------------------------------------------------------------------

    private fun room(extra: String) = """{"t":"room","room":{"messages":[],"code":"K7QXM2PR","name":"x","eventId":null,"leader":"u1","destination":null,"resting":false,"route":null,"automaticRegroup":{"enabled":false,"separationM":3000},"members":[]$extra}}"""

    @Test
    fun roomStateCarriesTheRouteGeometryWhenTheServerHasOne() {
        val withIt = ProtocolJson.decodeServer(room(""","routeGeometry":"_izlhA~rlgdF"""")) as ServerMessage.RoomUpdate
        assertEquals("_izlhA~rlgdF", withIt.room.routeGeometry)
        val without = ProtocolJson.decodeServer(room("")) as ServerMessage.RoomUpdate
        assertNull(without.room.routeGeometry)
        val newer = ProtocolJson.decodeServer(room(""","routeGeometry":null,"somethingNew":1""")) as ServerMessage.RoomUpdate
        assertNull(newer.room.routeGeometry)
    }

    @Test
    fun renameAndRegroupWireShapes() {
        assertEquals("""{"t":"convoy.rename","room":"K7QXM2PR","name":"Sunday Run"}""", ProtocolJson.encode(ClientMessage.ConvoyRename("K7QXM2PR", "Sunday Run")))
        assertEquals(
            """{"t":"convoy.regroup","room":"K7QXM2PR","enabled":true,"separationM":3000.0}""",
            ProtocolJson.encode(ClientMessage.ConvoyRegroup("K7QXM2PR", true, 3000.0)),
        )
        assertEquals(ClientMessage.ConvoyRename("A", "B"), ProtocolJson.decodeClient("""{"t":"convoy.rename","room":"A","name":"B"}"""))
    }

    // ---- push-to-talk state -----------------------------------------------------------------------

    @Test
    fun pttButtonStates() {
        assertEquals(PttView.Unavailable, VoiceState().view)
        assertEquals(PttView.Connecting, VoiceState(VoiceStatus.Connecting).view)
        assertEquals(PttView.Idle, VoiceState(VoiceStatus.Connected).view)
        assertEquals(PttView.Live, VoiceState(VoiceStatus.Connected, transmitting = true).view)
        assertEquals(PttView.Receiving, VoiceState(VoiceStatus.Connected, talkers = listOf(Talker("u2", "Mia"))).view)
        assertEquals("transmitting wins over receiving", PttView.Live, VoiceState(VoiceStatus.Connected, true, listOf(Talker("u2", "Mia"))).view)
        assertEquals(PttView.Unavailable, VoiceState(VoiceStatus.Unavailable, error = "501").view)
    }
}
