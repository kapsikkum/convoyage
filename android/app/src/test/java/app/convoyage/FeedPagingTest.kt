package app.convoyage

import app.convoyage.api.ApiResult
import app.convoyage.api.FeedPage
import app.convoyage.api.FeedScope
import app.convoyage.api.LikeState
import app.convoyage.api.Post
import app.convoyage.api.RideSummary
import app.convoyage.api.UserRef
import app.convoyage.api.Vehicle
import app.convoyage.api.Build
import app.convoyage.data.BuildText
import app.convoyage.data.FeedApi
import app.convoyage.data.FeedRepository
import app.convoyage.data.LatLngD
import app.convoyage.data.PageOf
import app.convoyage.data.Pager
import app.convoyage.data.RideTotals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun post(id: String, likes: Int = 0, liked: Boolean = false) = Post(
    id = id, kind = "text", author = UserRef("u1", "alex", "Alex"), body = "hi $id", likeCount = likes, likedByMe = liked,
)

/** A server with [total] posts, newest first, `pageSize` per page; the cursor is the id of the last post sent. */
private class FakeFeed(val total: Int, val log: MutableList<String> = ArrayList()) : FeedApi {
    var failNext: String? = null
    var gate: CompletableDeferred<Unit>? = null
    var overlap = 0

    private suspend fun page(label: String, before: String?, limit: Int): ApiResult<FeedPage> {
        log += "$label:${before ?: "-"}"
        gate?.await()
        failNext?.let { failNext = null; return ApiResult.Err(0, it) }
        val start = (before?.removePrefix("p")?.toInt()?.plus(1) ?: 0) - overlap
        val from = start.coerceAtLeast(0)
        val to = minOf(from + limit, total)
        val items = (from until to).map { post("p$it") }
        return ApiResult.Ok(FeedPage(items, if (to < total) "p${to - 1}" else null))
    }

    override suspend fun feed(scope: FeedScope, before: String?, limit: Int, lat: Double?, lng: Double?) =
        page("${scope.wire}@${lat ?: "-"},${lng ?: "-"}", before, limit)

    override suspend fun userPosts(handle: String, before: String?, limit: Int) = page("user/$handle", before, limit)
}

class FeedPagingTest {
    @Test
    fun refreshLoadsPageOneThenLoadMoreFollowsTheCursorUntilTheEnd() = runTest {
        val api = FakeFeed(total = 7)
        val feed = FeedRepository(backgroundScope, api, { null }, pageSize = 3)
        val p = feed.pager(FeedScope.Everyone)

        p.refresh(); runCurrent()
        assertEquals(listOf("p0", "p1", "p2"), p.state.value.items.map { it.id })
        assertTrue(p.state.value.loadedOnce)
        assertFalse(p.state.value.endReached)

        p.loadMore(); runCurrent()
        p.loadMore(); runCurrent()
        assertEquals(7, p.state.value.items.size)
        assertTrue(p.state.value.endReached)
        assertEquals(listOf("everyone@-,-:-", "everyone@-,-:p2", "everyone@-,-:p5"), api.log)

        p.loadMore(); runCurrent() // nothing left: no request
        assertEquals(3, api.log.size)
    }

    @Test
    fun loadMoreDoesNotRunTwiceAtOnceOrBeforeTheFirstPage() = runTest {
        val api = FakeFeed(total = 10)
        val feed = FeedRepository(backgroundScope, api, { null }, pageSize = 4)
        val p = feed.pager(FeedScope.Following)

        p.loadMore(); runCurrent() // first page has not arrived
        assertTrue(api.log.isEmpty())

        p.refresh(); runCurrent()
        api.gate = CompletableDeferred()
        p.loadMore(); runCurrent()
        p.loadMore(); p.loadMore(); runCurrent()
        assertEquals(2, api.log.size) // one refresh + one page, not three
        assertTrue(p.state.value.loadingMore)
        api.gate!!.complete(Unit); runCurrent()
        assertEquals(8, p.state.value.items.size)
        assertFalse(p.state.value.loadingMore)
    }

    @Test
    fun overlappingPagesAreDeduplicated() = runTest {
        val api = FakeFeed(total = 6).apply { overlap = 1 } // each page repeats the last post of the previous one
        val feed = FeedRepository(backgroundScope, api, { null }, pageSize = 3)
        val p = feed.pager(FeedScope.Everyone)
        p.refresh(); runCurrent()
        p.loadMore(); runCurrent()
        p.loadMore(); runCurrent()
        assertEquals((0 until 6).map { "p$it" }, p.state.value.items.map { it.id })
    }

    @Test
    fun aFailedPageKeepsWhatIsThereAndCanBeRetried() = runTest {
        val api = FakeFeed(total = 6)
        val feed = FeedRepository(backgroundScope, api, { null }, pageSize = 3)
        val p = feed.pager(FeedScope.Everyone)
        p.refresh(); runCurrent()

        api.failNext = "Cannot reach the server"
        p.loadMore(); runCurrent()
        assertEquals(3, p.state.value.items.size)
        assertEquals("Cannot reach the server", p.state.value.error)
        assertFalse(p.state.value.loadingMore)

        p.loadMore(); runCurrent()
        assertEquals(6, p.state.value.items.size)
        assertNull(p.state.value.error)
    }

    @Test
    fun refreshReplacesTheListAndDropsAPageThatWasStillLoading() = runTest {
        val api = FakeFeed(total = 9)
        val feed = FeedRepository(backgroundScope, api, { null }, pageSize = 3)
        val p = feed.pager(FeedScope.Everyone)
        p.refresh(); runCurrent()
        api.gate = CompletableDeferred()
        p.loadMore(); runCurrent() // stuck mid-flight
        api.gate = null
        p.refresh(); runCurrent()
        assertEquals(listOf("p0", "p1", "p2"), p.state.value.items.map { it.id })
        assertFalse(p.state.value.busy)
    }

    @Test
    fun nearbyNeedsALocationAndSendsItWhenThereIsOne() = runTest {
        val api = FakeFeed(total = 2)
        var here: LatLngD? = null
        val feed = FeedRepository(backgroundScope, api, { here }, pageSize = 3)
        val p = feed.pager(FeedScope.Nearby)

        p.refresh(); runCurrent()
        assertEquals("Waiting for your location", p.state.value.error)
        assertTrue(api.log.isEmpty())

        here = LatLngD(-33.4193, 149.5775)
        p.refresh(); runCurrent()
        assertEquals(listOf("nearby@-33.4193,149.5775:-"), api.log)
        assertEquals(2, p.state.value.items.size)
    }

    @Test
    fun likesAndCommentCountsChangeInEveryListThatHoldsThePost() = runTest {
        val api = FakeFeed(total = 3)
        val feed = FeedRepository(backgroundScope, api, { null }, pageSize = 3)
        feed.pager(FeedScope.Everyone).refresh()
        feed.pager(FeedScope.Following).refresh()
        runCurrent()

        feed.flipLike("p1")
        assertTrue(feed.pager(FeedScope.Everyone).state.value.items[1].likedByMe)
        assertEquals(1, feed.pager(FeedScope.Following).state.value.items[1].likeCount)
        feed.applyLike("p1", LikeState(likeCount = 5, likedByMe = true))
        assertEquals(5, feed.pager(FeedScope.Everyone).state.value.items[1].likeCount)
        feed.flipLike("p1") // unlike
        assertFalse(feed.pager(FeedScope.Everyone).state.value.items[1].likedByMe)
        assertEquals(4, feed.pager(FeedScope.Everyone).state.value.items[1].likeCount)
        feed.bumpComments("p0")
        assertEquals(1, feed.pager(FeedScope.Following).state.value.items[0].commentCount)
    }

    @Test
    fun aNewPostGoesToTheTopOnce() = runTest {
        val feed = FeedRepository(backgroundScope, FakeFeed(total = 2), { null }, pageSize = 3)
        feed.pager(FeedScope.Everyone).refresh(); runCurrent()
        feed.addMine(post("mine")); feed.addMine(post("mine"))
        assertEquals(listOf("mine", "p0", "p1"), feed.pager(FeedScope.Everyone).state.value.items.map { it.id })
    }

    @Test
    fun aGenericPagerWorksOnAnyItemType() = runTest {
        val pager = Pager<Int>(backgroundScope, { it.toString() }) { c ->
            ApiResult.Ok(PageOf(if (c == null) listOf(1, 2) else listOf(3), if (c == null) "x" else null))
        }
        pager.refresh(); runCurrent(); pager.loadMore(); runCurrent()
        assertEquals(listOf(1, 2, 3), pager.state.value.items)
        assertTrue(pager.state.value.endReached)
    }

    // ---- lifetime stats and build text (garage) -------------------------------------------------------

    @Test
    fun lifetimeStatsCountFinishedRidesOnThatVehicleOnly() {
        fun ride(id: String, vehicle: String?, km: Double, secs: Long, top: Double, status: String = "finished") = RideSummary(
            id = id, vehicleId = vehicle, status = status, distanceM = km * 1000, durationS = secs, maxSpeedMps = top,
        )
        val rides = listOf(
            ride("a", "v1", 10.0, 600, 30.0), ride("b", "v1", 25.5, 1800, 41.0), ride("c", "v2", 99.0, 5000, 50.0),
            ride("d", "v1", 3.0, 100, 99.0, status = "active"), ride("e", null, 1.0, 60, 10.0),
        )
        val v1 = RideTotals.of(rides, "v1")
        assertEquals(2, v1.rides)
        assertEquals(35_500.0, v1.distanceM, 1e-6)
        assertEquals(2400, v1.durationS)
        assertEquals(41.0, v1.topSpeedMps, 1e-9)
        assertEquals(25_500.0, v1.longestM, 1e-6)
        assertEquals(4, RideTotals.of(rides).rides)
        assertEquals(0, RideTotals.of(rides, "nope").rides)
    }

    @Test
    fun decalsAreFreeTextSplitOnCommas() {
        val decals = BuildText.decalsOf(" Racing stripes ,  #7 door,, Racing stripes ")
        assertEquals(listOf("Racing stripes", "#7 door"), decals.map { it.id })
        assertEquals("Racing stripes, #7 door", BuildText.textOf(Build(decals = decals)))
        assertEquals("", BuildText.textOf(Build()))
        assertEquals(64, BuildText.decalsOf("x".repeat(100)).single().id.length)
    }

    @Test
    fun vehicleNameIsTheNicknameElseMakeAndModel() {
        assertEquals("Yari", Vehicle(id = "1", make = "Toyota", model = "GR Yaris", nickname = "Yari").displayName)
        assertEquals("Toyota GR Yaris", Vehicle(id = "1", make = "Toyota", model = "GR Yaris").displayName)
    }
}
