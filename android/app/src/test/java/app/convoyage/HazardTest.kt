package app.convoyage

import app.convoyage.api.ApiJson
import app.convoyage.api.ApiResult
import app.convoyage.api.CreateHazard
import app.convoyage.api.HazardApi
import app.convoyage.api.gen.GenWsHazard
import app.convoyage.data.Bbox
import app.convoyage.data.HazardRepository
import app.convoyage.data.ReportResult
import app.convoyage.data.extractMagicToken
import app.convoyage.net.socketProtocolHeader
import app.convoyage.protocol.Hazard
import app.convoyage.protocol.ProtocolJson
import app.convoyage.protocol.ServerMessage
import app.convoyage.protocol.expiresAtMs
import app.convoyage.protocol.isPolice
import app.convoyage.protocol.toHazard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardDtoTest {
    // GET /v1/hazards element, exactly as server/src/api/routes/hazards.ts `hazardDto` builds it.
    private val rest = """
        {"id":"7d0e0f64-3a6c-4a43-8d0f-1f0b8a1d2c11","kind":"hazard","subkind":"gravel","lat":-37.8136,"lng":144.9631,
         "heading":92.5,"createdAt":"2026-11-03T04:10:00.000Z","expiresAt":"2026-11-03T04:40:00.000Z",
         "confirmations":2,"dismissals":0,"myAction":"confirm"}
    """.trimIndent()

    // `hazard` socket frame: the wire Hazard has no vote counts and no myAction (convoy.v1.schema.json).
    private val ws = """
        {"t":"hazard","room":"K7QXM2PR","hazard":{"id":"7d0e0f64-3a6c-4a43-8d0f-1f0b8a1d2c11","kind":"police","subkind":"speed_camera",
         "lat":-37.81,"lng":144.96,"heading":null,"createdAt":"2026-11-03T04:10:00.000Z","expiresAt":"2026-11-03T04:40:00.000Z"}}
    """.trimIndent()

    @Test
    fun parsesARestHazardWithVotes() {
        val h = ApiJson.decodeFromString(Hazard.serializer(), rest)
        assertEquals("hazard", h.kind)
        assertEquals("gravel", h.subkind)
        assertEquals(-37.8136, h.lat, 1e-9)
        assertEquals(92.5, h.heading!!, 1e-9)
        assertEquals(2, h.confirmations)
        assertEquals("confirm", h.myAction)
        assertFalse(h.isPolice)
        assertEquals(java.time.Instant.parse("2026-11-03T04:40:00Z").toEpochMilli(), h.expiresAtMs())
    }

    @Test
    fun parsesTheListResponseAndIgnoresFieldsAddedLater() {
        val body = "[$rest, ${rest.replace("\"myAction\":\"confirm\"", "\"myAction\":null, \"futureField\":{\"a\":1}")}]"
        val list = ApiJson.decodeFromString(ListSerializer(Hazard.serializer()), body)
        assertEquals(2, list.size)
        assertNull(list[1].myAction)
    }

    @Test
    fun parsesTheSocketHazardMessage() {
        val m = ProtocolJson.decodeServer(ws)
        assertTrue("got $m", m is ServerMessage.HazardReported)
        m as ServerMessage.HazardReported
        assertEquals("K7QXM2PR", m.room)
        // The socket payload has no votes: folding it into the REST DTO defaults them, not a parse failure.
        val h = m.hazard.toHazard()
        assertTrue(h.isPolice)
        assertNull(h.heading)
        assertEquals(0, h.confirmations)
        assertNull(h.myAction)
    }

    @Test
    fun anUnknownKindStillParsesAsAString() {
        val h = ApiJson.decodeFromString(Hazard.serializer(), rest.replace("\"hazard\"", "\"snow\""))
        assertEquals("snow", h.kind)
    }

    @Test
    fun theCreateBodyHasOnlyTheFieldsTheServerAccepts() {
        val json = ApiJson.encodeToString(CreateHazard.serializer(), CreateHazard("police", "speed_camera", -37.8, 144.9, 270.0))
        assertEquals("""{"kind":"police","subkind":"speed_camera","lat":-37.8,"lng":144.9,"heading":270.0}""", json)
        // additionalProperties:false on the server: nulls and defaults must be left out, not sent as null
        val bare = ApiJson.encodeToString(CreateHazard.serializer(), CreateHazard("crash", null, 1.0, 2.0, null))
        assertEquals("""{"kind":"crash","lat":1.0,"lng":2.0}""", bare)
    }

    @Test
    fun bboxIsSouthWestNorthEastAndNeverTooBig() {
        assertEquals("-37.90000,144.90000,-37.80000,145.00000", Bbox(-37.9, 144.9, -37.8, 145.0).toParam())
        val huge = Bbox(-60.0, 100.0, 10.0, 170.0).clamped()
        assertTrue(huge.north - huge.south <= 8.0 + 1e-9)
        assertTrue(huge.east - huge.west <= 8.0 + 1e-9)
        val near = Bbox.around(-37.8, 144.9, 2000.0)
        assertTrue(near.contains(-37.8, 144.9))
        assertFalse(near.contains(-37.5, 144.9))
    }

    @Test
    fun socketSubprotocolHeaderIsExactlyWhatTheServerParses() {
        // server/src/auth.ts socketToken(): split on ',', trim, take the entry starting with "bearer."
        assertEquals("convoyage.v1, bearer.aaa.bbb.ccc", socketProtocolHeader("aaa.bbb.ccc"))
    }

    @Test
    fun magicTokenIsPulledFromALinkOrAcceptedBare() {
        val token = "abcDEF0123456789abcDEF0123456789"
        assertEquals(token, extractMagicToken("convoyage://auth/magic?token=$token"))
        assertEquals(token, extractMagicToken("  https://x.example/auth?foo=1&token=$token&bar=2  "))
        assertEquals(token, extractMagicToken(token))
        assertEquals("a b", extractMagicToken("convoyage://auth/magic?token=a%20b"))
        assertNull(extractMagicToken(""))
        assertNull(extractMagicToken("hello"))
        assertNull(extractMagicToken("convoyage://auth/magic"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HazardRepositoryTest {
    private fun hz(id: String, kind: String = "hazard", lat: Double = -37.8, lng: Double = 144.9, expires: String = "2026-11-03T05:00:00Z", myAction: String? = null) =
        Hazard(id, kind, "", lat, lng, null, "2026-11-03T04:30:00Z", expires, 0, 0, myAction)

    /** The socket frame for [h]: the wire Hazard carries no votes (it is its own schema). */
    private fun reported(h: Hazard, room: String = "ROOM") =
        ServerMessage.HazardReported(room, GenWsHazard(h.id, JsonPrimitive(h.kind), h.subkind, h.lat, h.lng, h.heading, h.createdAt, h.expiresAt))

    private val now = java.time.Instant.parse("2026-11-03T04:35:00Z").toEpochMilli()

    private class FakeApi : HazardApi {
        var created: CompletableDeferred<ApiResult<Hazard>> = CompletableDeferred()
        var listed: ApiResult<List<Hazard>> = ApiResult.Ok(emptyList())
        var confirmed: ApiResult<Hazard> = ApiResult.Err(500, "unset")
        var dismissed: ApiResult<Hazard> = ApiResult.Err(500, "unset")
        var lastBbox: String? = null
        override suspend fun createHazard(body: CreateHazard) = created.await()
        override suspend fun listHazards(bbox: String): ApiResult<List<Hazard>> { lastBbox = bbox; return listed }
        override suspend fun confirmHazard(id: String) = confirmed
        override suspend fun dismissHazard(id: String) = dismissed
    }

    private fun TestScope.repo(api: FakeApi, incoming: MutableSharedFlow<ServerMessage.HazardReported>) =
        HazardRepository(
            kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler)), api, incoming, nowMs = { now },
        )

    @Test
    fun aMembersReportShowsOnTheMapAndRaisesTheBanner() = runTest {
        val incoming = MutableSharedFlow<ServerMessage.HazardReported>(extraBufferCapacity = 8)
        val repo = repo(FakeApi(), incoming)

        incoming.emit(reported(hz("h1", kind = "police")))

        assertEquals(listOf("h1"), repo.active.value.map { it.id })
        assertEquals("h1", repo.alert.value?.hazard?.id)
        assertEquals("ROOM", repo.alert.value?.room)
    }

    @Test
    fun myOwnReportEchoedByTheSocketBeforeThePostReturnsDoesNotRaiseTheBanner() = runTest {
        val api = FakeApi()
        val incoming = MutableSharedFlow<ServerMessage.HazardReported>(extraBufferCapacity = 8)
        val repo = repo(api, incoming)

        val job = launch(UnconfinedTestDispatcher(testScheduler)) { repo.report("hazard", "oil", -37.8, 144.9, 90.0) }
        // The server pushes to the room first and answers the POST second.
        incoming.emit(reported(hz("mine")))
        assertNull("no banner for my own report", repo.alert.value)
        api.created.complete(ApiResult.Ok(hz("mine")))
        job.join()

        assertNull(repo.alert.value)
        assertEquals(listOf("mine"), repo.active.value.map { it.id })
        // and a later echo of the same id is still mine
        incoming.emit(reported(hz("mine")))
        assertNull(repo.alert.value)
    }

    @Test
    fun reportFailuresAreExplainedNotThrown() = runTest {
        val api = FakeApi()
        val repo = repo(api, MutableSharedFlow())
        api.created.complete(ApiResult.Err(401, "Not signed in"))
        val r = repo.report("crash", "", 1.0, 2.0, null)
        assertTrue(r is ReportResult.Failed)
        assertTrue((r as ReportResult.Failed).message.contains("Sign in"))
    }

    @Test
    fun confirmUpdatesTheHazardAndClearsTheBanner() = runTest {
        val api = FakeApi()
        val incoming = MutableSharedFlow<ServerMessage.HazardReported>(extraBufferCapacity = 8)
        val repo = repo(api, incoming)
        incoming.emit(reported(hz("h1")))
        api.confirmed = ApiResult.Ok(hz("h1").copy(confirmations = 1, myAction = "confirm", expiresAt = "2026-11-03T05:05:00Z"))

        repo.confirm("h1")

        assertNull(repo.alert.value)
        assertEquals("confirm", repo.active.value.single().myAction)
        assertEquals(1, repo.active.value.single().confirmations)
    }

    @Test
    fun dismissHidesTheHazardForMe() = runTest {
        val api = FakeApi()
        val incoming = MutableSharedFlow<ServerMessage.HazardReported>(extraBufferCapacity = 8)
        val repo = repo(api, incoming)
        incoming.emit(reported(hz("h1")))
        api.dismissed = ApiResult.Ok(hz("h1").copy(dismissals = 1, myAction = "dismiss"))

        repo.dismiss("h1")

        assertNull(repo.alert.value)
        assertTrue(repo.active.value.isEmpty())
    }

    @Test
    fun aFailedVoteKeepsTheBannerAndShowsWhy() = runTest {
        val api = FakeApi()
        val incoming = MutableSharedFlow<ServerMessage.HazardReported>(extraBufferCapacity = 8)
        val repo = repo(api, incoming)
        incoming.emit(reported(hz("h1")))
        api.confirmed = ApiResult.Err(0, "Cannot reach the server")

        repo.confirm("h1")

        assertEquals("Cannot reach the server", repo.alert.value?.error)
    }

    @Test
    fun alreadyVotedIsNotAnError() = runTest {
        val api = FakeApi()
        val incoming = MutableSharedFlow<ServerMessage.HazardReported>(extraBufferCapacity = 8)
        val repo = repo(api, incoming)
        incoming.emit(reported(hz("h1")))
        api.confirmed = ApiResult.Err(409, "You already voted")
        repo.confirm("h1")
        assertNull(repo.alert.value)
    }

    @Test
    fun expiredHazardsNeverReachTheMap() = runTest {
        val api = FakeApi()
        val repo = repo(api, MutableSharedFlow())
        api.listed = ApiResult.Ok(listOf(hz("live"), hz("old", expires = "2026-11-03T04:00:00Z")))

        repo.refresh(Bbox(-38.0, 144.0, -37.0, 145.0))

        assertEquals(listOf("live"), repo.active.value.map { it.id })
        assertNotNull(api.lastBbox)
        assertEquals("-38.00000,144.00000,-37.00000,145.00000", api.lastBbox)
    }

    @Test
    fun refreshReplacesInsideTheBoxAndKeepsWhatIsOutsideIt() = runTest {
        val api = FakeApi()
        val repo = repo(api, MutableSharedFlow())
        val box = Bbox(-38.0, 144.0, -37.0, 145.0)
        api.listed = ApiResult.Ok(listOf(hz("a"), hz("b", lat = -37.5)))
        repo.refresh(box)
        repo.onSocketHazard("R", hz("far", lat = 10.0, lng = 10.0))

        api.listed = ApiResult.Ok(listOf(hz("a"))) // b was removed on the server
        repo.refresh(box)

        assertEquals(setOf("a", "far"), repo.active.value.map { it.id }.toSet())
    }

    @Test
    fun clearForgetsEverything() = runTest {
        val incoming = MutableSharedFlow<ServerMessage.HazardReported>(extraBufferCapacity = 8)
        val repo = repo(FakeApi(), incoming)
        incoming.emit(reported(hz("h1")))
        repo.clear()
        assertTrue(repo.active.value.isEmpty())
        assertNull(repo.alert.value)
    }
}
