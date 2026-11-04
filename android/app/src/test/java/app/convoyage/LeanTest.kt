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
import app.convoyage.data.LeanMath
import app.convoyage.data.RideRecorder
import app.convoyage.data.RideStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

class LeanMathTest {
    @Test
    fun steadyTurnGivesTheTextbookLean() {
        // 20 m/s round a 40 m radius: tan(lean) = v^2 / (r g) -> about 45.6 degrees.
        val v = 20.0
        val r = 40.0
        val truth = Math.toDegrees(atan(v * v / (r * LeanMath.G)))
        val omega = v / r
        // The gyro sees the turn about the bike's leaned up axis: omega * cos(lean).
        val measured = omega * cos(Math.toRadians(truth))
        assertEquals(truth, LeanMath.leanDeg(v, measured), 0.01)
    }

    @Test
    fun signFollowsTheTurnAndSlowSpeedsReadUpright() {
        assertEquals(true, LeanMath.leanDeg(15.0, 0.3) > 0) // counter-clockwise = left
        assertEquals(true, LeanMath.leanDeg(15.0, -0.3) < 0)
        assertEquals(0.0, LeanMath.leanDeg(2.0, 1.0), 0.0)
        assertEquals(LeanMath.MAX_DEG, LeanMath.leanDeg(60.0, 3.0), 0.0)
    }

    @Test
    fun yawRateIsTheGyroAlongWhicheverWayIsUp() {
        // Phone mounted tilted 30 degrees about its x axis: up is not the z axis, the turn is still found.
        val t = Math.toRadians(30.0)
        val up = floatArrayOf(0f, (9.8 * sin(t)).toFloat(), (9.8 * cos(t)).toFloat())
        val gyro = floatArrayOf(0f, (0.5 * sin(t)).toFloat(), (0.5 * cos(t)).toFloat())
        assertEquals(0.5, LeanMath.yawRate(gyro, up), 1e-4)
        assertEquals(0.0, LeanMath.yawRate(gyro, floatArrayOf(0f, 0f, 0f)), 0.0)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RideMaxLeanTest {
    @get:Rule val tmp = TemporaryFolder()

    private object NoApi : RideApi {
        override suspend fun createRide(body: CreateRide): ApiResult<Ride> = ApiResult.Ok(Ride(id = "r1", status = "active"))
        override suspend fun finishRide(id: String, body: FinishRide): ApiResult<Ride> = ApiResult.Ok(Ride(id = id, status = "finished"))
        override suspend fun getRide(id: String): ApiResult<Ride> = ApiResult.Ok(Ride(id = id, status = "finished"))
        override suspend fun updateRide(id: String, body: UpdateRide): ApiResult<Ride> = ApiResult.Ok(Ride(id = id, status = "finished"))
        override suspend fun deleteRide(id: String): ApiResult<OkBody> = ApiResult.Ok(OkBody())
        override suspend fun uploadMedia(file: File, mime: String): ApiResult<Media> = ApiResult.Ok(Media(id = "m"))
        override suspend fun attachRidePhoto(rideId: String, body: AttachPhoto): ApiResult<RidePhoto> = ApiResult.Ok(RidePhoto(id = "p", media = Media(id = "m")))
    }

    @Test
    fun maxLeanIsKeptWhileRecordingSavedAndRestored() = runTest {
        val lean = MutableStateFlow<Double?>(null)
        val fixes = MutableStateFlow<Fix?>(null)
        val dir = File(tmp.root, "ride")
        val rec = RideRecorder(backgroundScope, RideStore(dir), fixes, NoApi, lean = lean, io = UnconfinedTestDispatcher())
        rec.start(null, null, null, null)
        runCurrent()
        assertNull(rec.state.value.stats.maxLeanDeg)

        for (v in listOf(12.0, -38.4, 20.0, null, 30.0)) { lean.value = v; runCurrent() }
        assertEquals(38.0, rec.state.value.stats.maxLeanDeg!!, 0.0)

        // Paused: leaning over at the side of the road does not count.
        rec.pause(); runCurrent()
        lean.value = 55.0; runCurrent()
        assertEquals(38.0, rec.state.value.stats.maxLeanDeg!!, 0.0)

        // On disk, so it survives a restart.
        val again = RideRecorder(backgroundScope, RideStore(dir), fixes, NoApi, io = UnconfinedTestDispatcher())
        again.restore()
        assertEquals(38.0, again.state.value.stats.maxLeanDeg!!, 0.0)
    }
}
