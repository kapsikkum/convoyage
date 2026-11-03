package app.convoyage

import app.convoyage.api.ApiResult
import app.convoyage.api.AttachPhoto
import app.convoyage.api.CreateRide
import app.convoyage.api.FinishRide
import app.convoyage.api.Media
import app.convoyage.api.OkBody
import app.convoyage.api.Ride
import app.convoyage.api.RideApi
import app.convoyage.api.RidePhoto
import app.convoyage.api.UpdateRide
import app.convoyage.data.Fix
import app.convoyage.data.Polyline
import app.convoyage.data.RideRecorder
import app.convoyage.data.RideStage
import app.convoyage.data.RideStore
import app.convoyage.data.StartResult
import app.convoyage.data.TracePoint
import app.convoyage.data.UploadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A server that remembers what it was sent and can be told to fail. */
private class FakeRideApi : RideApi {
    val created = ArrayList<CreateRide>()
    val finished = ArrayList<Pair<String, FinishRide>>()
    val uploads = ArrayList<File>()
    val attached = ArrayList<AttachPhoto>()
    val deleted = ArrayList<String>()

    /** Errors handed out (one per call) before calls start to succeed. */
    val finishErrors = ArrayDeque<ApiResult.Err>()
    var createError: ApiResult.Err? = null

    private fun ride(id: String, status: String) = Ride(id = id, status = status)

    override suspend fun createRide(body: CreateRide): ApiResult<Ride> {
        createError?.let { return it }
        created += body
        return ApiResult.Ok(ride("ride-${created.size}", "active"))
    }

    override suspend fun finishRide(id: String, body: FinishRide): ApiResult<Ride> {
        finishErrors.removeFirstOrNull()?.let { return it }
        finished += id to body
        return ApiResult.Ok(ride(id, "finished"))
    }

    override suspend fun getRide(id: String): ApiResult<Ride> = ApiResult.Ok(ride(id, "finished"))
    override suspend fun updateRide(id: String, body: UpdateRide): ApiResult<Ride> = ApiResult.Ok(ride(id, "finished"))
    override suspend fun deleteRide(id: String): ApiResult<OkBody> {
        deleted += id
        return ApiResult.Ok(OkBody())
    }

    override suspend fun uploadMedia(file: File, mime: String): ApiResult<Media> {
        uploads += file
        return ApiResult.Ok(Media(id = "media-${uploads.size}", thumbUrl = "/v1/media/media-${uploads.size}/thumb"))
    }

    override suspend fun attachRidePhoto(rideId: String, body: AttachPhoto): ApiResult<RidePhoto> {
        attached += body
        return ApiResult.Ok(RidePhoto(id = "rp-${attached.size}", media = Media(id = body.mediaId)))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RideRecorderTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_700_000_000_000L
    private val fixes = MutableStateFlow<Fix?>(null)
    private val api = FakeRideApi()
    private var acquired = 0
    private var released = 0

    private fun recorder(scope: kotlinx.coroutines.CoroutineScope, dir: File = File(tmp.root, "ride")): RideRecorder =
        RideRecorder(
            scope = scope, store = RideStore(dir), fixes = fixes, api = api,
            acquireTracker = { acquired++ }, releaseTracker = { released++ },
            now = { clock }, io = UnconfinedTestDispatcher(),
            retryDelayMs = { 10_000L },
        )

    /** One fix per second, ~11 m further north each time, at [clock]. */
    private fun TestScope.ride(seconds: Int, startLat: Double = -33.0, accuracy: Float = 5f) {
        repeat(seconds) { i ->
            clock += 1000
            fixes.value = Fix(startLat + i * 0.0001, 150.0, 0.0, 11.0, accuracy, clock, 100.0)
            runCurrent()
        }
    }

    @Test
    fun recordsGoodFixesPersistsThemAndDropsBadOnes() = runTest {
        val rec = recorder(backgroundScope)
        assertEquals(StartResult.Started, rec.start("v1", "GR Yaris", null, null))
        runCurrent()
        ride(5)
        clock += 1000
        fixes.value = Fix(-34.0, 150.0, 0.0, 11.0, 80f, clock) // 80 m accuracy: dropped
        runCurrent()

        val s = rec.state.value
        assertEquals(RideStage.Recording, s.stage)
        assertEquals(5, s.pointCount)
        assertEquals(44.5, s.stats.distanceM, 1.0)
        assertEquals(1, acquired)
        // On disk too: points one per line, plus the meta.
        val store = RideStore(File(tmp.root, "ride"))
        assertEquals(5, store.readPoints().size)
        assertEquals("GR Yaris", store.readMeta()!!.vehicleName)
    }

    @Test
    fun aKilledProcessComesBackPausedWithItsTraceAndCanBeResumed() = runTest {
        val doomed = CoroutineScope(backgroundScope.coroutineContext + Job())
        val first = recorder(doomed)
        first.start("v1", "GR Yaris", "K7QXM2PR", null)
        runCurrent()
        ride(10)
        val before = first.state.value

        // The process dies here (its coroutines stop, nothing is saved on the way out): a new recorder reads the disk.
        doomed.cancel()
        clock += 60_000 // a minute passes
        val second = recorder(backgroundScope)
        second.restore()
        val after = second.state.value
        assertEquals(RideStage.Paused, after.stage)
        assertEquals(before.pointCount, after.pointCount)
        assertEquals(before.stats.distanceM, after.stats.distanceM, 0.001)
        assertEquals("K7QXM2PR", after.meta!!.convoyCode)
        // The minute while the app was dead does not count as ride time.
        assertEquals(10, after.elapsedS)

        second.resume()
        runCurrent()
        assertEquals(RideStage.Recording, second.state.value.stage)
        ride(5, startLat = -33.5)
        assertEquals(15, second.state.value.pointCount)
        // Resuming starts a new segment, so the 55 km jump to -33.5 adds no distance.
        assertTrue(second.state.value.stats.distanceM < 200)
        assertEquals(2, second.points().map { it.segment }.distinct().size)
    }

    @Test
    fun pauseStopsCollectingAndReleasesTheTracker() = runTest {
        val rec = recorder(backgroundScope)
        rec.start(null, null, null, null)
        runCurrent()
        ride(3)
        rec.pause()
        runCurrent()
        assertEquals(1, released)
        ride(3, startLat = -33.1)
        assertEquals(3, rec.state.value.pointCount) // nothing recorded while paused
        assertEquals(RideStage.Paused, rec.state.value.stage)
    }

    @Test
    fun stopCreatesFinishesAndUploadsTheEncodedTrace() = runTest {
        val rec = recorder(backgroundScope)
        rec.start("v1", "GR Yaris", null, null, title = "Sunday run")
        runCurrent()
        ride(12)
        rec.stop()
        runCurrent()

        assertEquals(RideStage.Uploaded, rec.state.value.stage)
        assertEquals(UploadState.Done, rec.state.value.upload)
        assertEquals(1, api.created.size) // created once, at the start
        assertEquals("v1", api.created[0].vehicleId)
        val (id, body) = api.finished.single()
        assertEquals("ride-1", id)
        assertEquals(12, Polyline.decode(body.polyline).size)
        assertEquals(12, body.durationS)
        assertEquals(122.3, body.distanceM, 1.0)
        assertEquals(11.0, body.maxSpeedMps, 0.01)
        assertEquals(0.0, body.elevationGainM!!, 0.001)
        assertNull(body.maxLeanDeg) // placeholder
        assertEquals("Sunday run", body.title)
        assertEquals(1, released)
    }

    @Test
    fun aFailedUploadKeepsTheRideAndRetriesByItself() = runTest {
        val rec = recorder(backgroundScope)
        rec.start(null, null, null, null)
        runCurrent()
        ride(8)
        api.finishErrors += ApiResult.Err(0, "Cannot reach the server")
        rec.stop()
        runCurrent()

        val failed = rec.state.value.upload
        assertTrue(failed is UploadState.Failed && failed.retryable)
        assertEquals(RideStage.Stopped, rec.state.value.stage)
        assertTrue(api.finished.isEmpty())
        // Still on disk, ready for another run of the app.
        assertEquals(RideStage.Stopped, RideStore(File(tmp.root, "ride")).readMeta()!!.stage)

        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(UploadState.Done, rec.state.value.upload)
        assertEquals(RideStage.Uploaded, rec.state.value.stage)
        assertEquals(1, api.finished.size)
        assertEquals(1, api.created.size) // the retry did not create a second ride
    }

    @Test
    fun aRideThatWasNeverCreatedOnTheServerIsCreatedAtFinish() = runTest {
        api.createError = ApiResult.Err(0, "offline")
        val rec = recorder(backgroundScope)
        rec.start("v1", null, null, null)
        runCurrent()
        ride(6)
        assertNull(rec.state.value.meta!!.serverRideId)
        api.createError = null
        rec.stop()
        runCurrent()
        assertEquals(RideStage.Uploaded, rec.state.value.stage)
        assertEquals(1, api.created.size)
        assertEquals(1, api.finished.size)
    }

    @Test
    fun aStoppedRideThatNeverUploadedIsUploadedWhenTheAppStartsAgain() = runTest {
        api.createError = ApiResult.Err(0, "offline")
        val rec = recorder(backgroundScope)
        rec.start(null, null, null, null)
        runCurrent()
        ride(6)
        rec.stop()
        runCurrent()
        assertTrue(rec.state.value.upload is UploadState.Failed)

        // App restarts, network is back.
        api.createError = null
        val again = recorder(backgroundScope)
        again.restore()
        runCurrent()
        assertEquals(RideStage.Uploaded, again.state.value.stage)
        assertEquals(1, api.finished.size)
        assertEquals(6, Polyline.decode(api.finished[0].second.polyline).size)
    }

    @Test
    fun photosAreMovedUploadedAndPinnedOnlyWhenLocationIsOn() = runTest {
        val rec = recorder(backgroundScope)
        rec.start(null, null, null, null)
        runCurrent()
        ride(3)
        val shot1 = tmp.newFile("a.jpg").also { it.writeText("jpeg-a") }
        val shot2 = tmp.newFile("b.jpg").also { it.writeText("jpeg-b") }
        val here = fixes.value!!
        rec.addPhoto(shot1, here)
        rec.addPhoto(shot2, null)
        runCurrent()

        assertFalse("the camera's cache file was moved into the ride's own folder", shot1.exists())
        assertEquals(2, rec.state.value.meta!!.photos.size)
        rec.stop()
        runCurrent()

        assertEquals(RideStage.Uploaded, rec.state.value.stage)
        assertEquals(2, api.attached.size)
        val withPin = api.attached.single { it.lat != null }
        assertEquals(here.lat, withPin.lat!!, 1e-9)
        val noPin = api.attached.single { it.lat == null }
        assertNull(noPin.lng)
        assertNotNull(noPin.takenAt)
        assertTrue(rec.state.value.meta!!.photos.all { it.attached && it.mediaId != null })
    }

    @Test
    fun aSecondRideCannotStartWhileOneIsOpenOrWaitingToUpload() = runTest {
        val rec = recorder(backgroundScope)
        assertEquals(StartResult.Started, rec.start(null, null, null, null))
        assertEquals(StartResult.Busy, rec.start(null, null, null, null))
        runCurrent()
        rec.stop()
        runCurrent()
        // Uploaded rides are safe on the server, so a new one may replace the recap.
        assertEquals(StartResult.Started, rec.start(null, null, null, null))
    }

    @Test
    fun discardClearsTheDiskAndDeletesTheServerRide() = runTest {
        val rec = recorder(backgroundScope)
        rec.start(null, null, null, null)
        runCurrent()
        ride(3)
        rec.discard()
        runCurrent()
        assertNull(rec.state.value.meta)
        assertNull(RideStore(File(tmp.root, "ride")).readMeta())
        assertEquals(listOf("ride-1"), api.deleted)
    }

    @Test
    fun aLineCutOffByACrashIsSkippedWhenReadingTheTrace() {
        val store = RideStore(File(tmp.root, "cut"))
        val p = TracePoint(1000, -33.0, 150.0, 12.5, null, 4f, 2)
        store.appendPoint(p)
        File(tmp.root, "cut/points.csv").appendText("1500,-33.00")
        assertEquals(listOf(p), store.readPoints())
    }

    @Test
    fun metaIsNeverHalfWritten() {
        val store = RideStore(File(tmp.root, "meta"))
        assertNull(store.readMeta())
        File(tmp.root, "meta").mkdirs()
        File(tmp.root, "meta/meta.json").writeText("{ not json")
        assertNull(store.readMeta()) // unreadable = no ride, not a crash
    }
}
