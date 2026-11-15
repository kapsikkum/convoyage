package app.convoyage

import app.convoyage.api.ApiJson
import app.convoyage.api.ApiResult
import app.convoyage.api.ConvoyApi
import app.convoyage.api.InMemoryTokenStore
import app.convoyage.api.PhotoScoutStatus
import app.convoyage.api.PsSuggestion
import app.convoyage.api.PsSuggestionRequest
import app.convoyage.api.Tokens
import app.convoyage.data.PhotoScoutLayers
import app.convoyage.data.SPOT_SENT_PHOTO_FAILED
import app.convoyage.data.parseSpotTags
import app.convoyage.data.spotSuggestionError
import app.convoyage.data.suggestionBody
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Reporting a PhotoScout spot: the pure request bits, the lenient decoding, and the two calls it makes. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoScoutSuggestTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private val suggestionJson = """{"id":"sg1","name":"Lookout","notes":"","lat":-33.4,"lng":149.5,"tags":["sunset"],
        "photo":null,"status":"waiting","createdAt":"2026-11-16T01:00:00.000Z"}"""

    private fun api() = ConvoyApi({ server.url("/").toString() }, InMemoryTokenStore(Tokens("tok", "ref")))

    @Test
    fun tagsSplitOnCommasTrimDropBlanksAndDedupeIgnoringCase() {
        assertEquals(listOf("a", "B"), parseSpotTags("a, B,, a "))
        assertEquals(listOf("sunset", "LOOKOUT"), parseSpotTags(" sunset , LOOKOUT, Sunset "))
        assertEquals(30, parseSpotTags("z".repeat(40)).single().length)
        assertEquals((1..8).map { "t$it" }, parseSpotTags((1..12).joinToString(", ") { "t$it" }))
        assertTrue(parseSpotTags(" , ,, ").isEmpty())
    }

    @Test
    fun suggestionBodySerialisesOnlyTheFieldsTheServerNeeds() {
        val body = suggestionBody("  Mount Panorama lookout ", " Parking by the gate ", -33.44, 149.56, listOf("sunset", "lookout"))
        assertEquals(PsSuggestionRequest("Mount Panorama lookout", "Parking by the gate", -33.44, 149.56, listOf("sunset", "lookout")), body)
        assertEquals(
            """{"name":"Mount Panorama lookout","notes":"Parking by the gate","lat":-33.44,"lng":149.56,"tags":["sunset","lookout"]}""",
            ApiJson.encodeToString(PsSuggestionRequest.serializer(), body),
        )
        // Blank notes and no tags are left to the server's defaults instead of being sent as "" or [].
        assertEquals(
            """{"name":"Lookout","lat":1.5,"lng":2.5}""",
            ApiJson.encodeToString(PsSuggestionRequest.serializer(), suggestionBody("Lookout", "   ", 1.5, 2.5, emptyList())),
        )
    }

    @Test
    fun aForbiddenSuggestionMapsToARelinkMessageAndOthersKeepTheServerText() {
        val forbidden = spotSuggestionError(ApiResult.Err(403, "photoscout_insufficient_scope"))
        assertTrue(forbidden.contains("Relink PhotoScout"))
        assertEquals("PhotoScout is not linked", spotSuggestionError(ApiResult.Err(409, "PhotoScout is not linked")))
        assertEquals("You can suggest 10 spots a day", spotSuggestionError(ApiResult.Err(429, "You can suggest 10 spots a day")))
        assertTrue(SPOT_SENT_PHOTO_FAILED.startsWith("The spot was sent to PhotoScout, but the photo was not: "))
    }

    @Test
    fun statusDecodesCanSuggestAndDefaultsItToFalseOnOlderServers() {
        val new = ApiJson.decodeFromString(PhotoScoutStatus.serializer(), """{"configured":true,"linked":true,"username":"sam","canSuggest":true}""")
        assertTrue(new.canSuggest)
        val old = ApiJson.decodeFromString(PhotoScoutStatus.serializer(), """{"configured":true,"linked":true}""")
        assertFalse(old.canSuggest)
    }

    @Test
    fun aSuggestionDecodesWithItsPhotoAndWaitingStatus() {
        val s = ApiJson.decodeFromString(
            PsSuggestion.serializer(),
            """{"id":"sg1","name":"Lookout","notes":"","lat":-33.4,"lng":149.5,"tags":["sunset"],
                "photo":{"thumbUrl":"https://photoscout.example/t/1"},"status":"waiting","createdAt":"2026-11-16T01:00:00.000Z"}""",
        )
        assertEquals("sg1", s.id)
        assertEquals("waiting", s.status)
        assertEquals("https://photoscout.example/t/1", s.photo?.thumbUrl)
    }

    @Test
    fun reportSpotPostsTheBodyThenThePhoto() = runBlocking {
        server.enqueue(MockResponse.Builder().code(201).body(suggestionJson).build())
        server.enqueue(
            MockResponse.Builder().code(201)
                .body(suggestionJson.replace("\"photo\":null", "\"photo\":{\"thumbUrl\":\"https://photoscout.example/t/1\"}")).build(),
        )
        val layers = PhotoScoutLayers(TestScope(UnconfinedTestDispatcher()), api())

        assertNull(layers.reportSpot("Lookout", "Notes", -33.4, 149.5, listOf("sunset"), byteArrayOf(1, 2, 3)))
        val create = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/photoscout/suggestions", create.url.encodedPath)
        assertEquals("""{"name":"Lookout","notes":"Notes","lat":-33.4,"lng":149.5,"tags":["sunset"]}""", create.body?.utf8())
        val photo = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/photoscout/suggestions/sg1/photo", photo.url.encodedPath)
        assertTrue(photo.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        assertTrue(photo.body!!.utf8().contains("name=\"file\""))
    }

    @Test
    fun reportSpotSaysWhenTheSpotWentButThePhotoDidNot() = runBlocking {
        server.enqueue(MockResponse.Builder().code(201).body(suggestionJson).build())
        server.enqueue(MockResponse.Builder().code(500).body("""{"code":"internal","message":"PhotoScout is down"}""").build())
        val layers = PhotoScoutLayers(TestScope(UnconfinedTestDispatcher()), api())

        val message = layers.reportSpot("Lookout", "", -33.4, 149.5, emptyList(), byteArrayOf(1))
        assertTrue(message!!.startsWith(SPOT_SENT_PHOTO_FAILED))
        assertTrue(message.endsWith("PhotoScout is down"))
    }
}
