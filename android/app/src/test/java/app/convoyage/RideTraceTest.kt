package app.convoyage

import app.convoyage.data.Fix
import app.convoyage.data.Geo
import app.convoyage.data.LatLngD
import app.convoyage.data.TraceFilter
import app.convoyage.data.TraceMath
import app.convoyage.data.TracePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trace filtering, gap filling and the ride numbers. 0.0001 degrees of latitude is about 11.12 m. */
class RideTraceTest {
    private val t0 = 1_700_000_000_000L

    private fun fix(sec: Int, lat: Double = -33.0, lng: Double = 150.0, acc: Float = 5f, speed: Double? = null, alt: Double? = null) =
        Fix(lat, lng, null, speed, acc, t0 + sec * 1000L, alt)

    private fun pt(sec: Int, lat: Double, lng: Double = 150.0, speed: Double? = null, alt: Double? = null, seg: Int = 0) =
        TracePoint(t0 + sec * 1000L, lat, lng, alt, speed, 5f, seg)

    // ---- filtering ----------------------------------------------------------------------------------

    @Test
    fun dropsFixesWithPoorOrUnknownAccuracy() {
        val f = TraceFilter()
        assertNotNull(f.offer(fix(0, acc = 30f))) // exactly at the limit is fine
        assertNull(f.offer(fix(1, lat = -33.0001, acc = 30.5f)))
        assertNull(f.offer(fix(2, lat = -33.0002, acc = Float.MAX_VALUE))) // unknown accuracy
        assertNotNull(f.offer(fix(3, lat = -33.0003, acc = 8f)))
    }

    @Test
    fun dropsFixesThatAreNotNewerThanTheLastOne() {
        val f = TraceFilter()
        assertNotNull(f.offer(fix(5)))
        assertNull(f.offer(fix(5, lat = -33.0001)))
        assertNull(f.offer(fix(4, lat = -33.0001)))
    }

    @Test
    fun dropsAnImpossibleJump() {
        val f = TraceFilter()
        f.offer(fix(0))
        // 0.01 degrees is 1.1 km in one second.
        assertNull(f.offer(fix(1, lat = -33.01)))
        // The next sensible fix, near the last GOOD point, is accepted and stays in the same segment.
        val ok = f.offer(fix(2, lat = -33.0002))
        assertNotNull(ok)
        assertEquals(0, ok!!.segment)
    }

    @Test
    fun aRunOfJumpsMeansTheFirstPointWasWrongSoItStartsANewSegment() {
        val f = TraceFilter(resetAfterRejects = 3)
        f.offer(fix(0, lat = -30.0)) // a wrong first fix, far from where we really are
        assertNull(f.offer(fix(1, lat = -33.0)))
        assertNull(f.offer(fix(2, lat = -33.0001)))
        val reset = f.offer(fix(3, lat = -33.0002))
        assertNotNull(reset)
        assertEquals(1, reset!!.segment)
        assertNotNull(f.offer(fix(4, lat = -33.0003)))
    }

    @Test
    fun aGapOfThirtySecondsOrMoreBreaksTheSegmentWithoutAJumpCheck() {
        val f = TraceFilter()
        f.offer(fix(0))
        val after = f.offer(fix(30, lat = -33.05)) // 5.5 km in 30 s is 185 m/s, but a gap is not a jump
        assertEquals(1, after!!.segment)
    }

    @Test
    fun resumingStartsANewSegmentAndForgetsThePositionBeforeThePause() {
        val f = TraceFilter()
        f.offer(fix(0))
        f.startNewSegment()
        val p = f.offer(fix(5, lat = -33.5)) // far away: we were carried somewhere while paused
        assertEquals(1, p!!.segment)
    }

    // ---- gap filling --------------------------------------------------------------------------------

    @Test
    fun fillsAShortGapWithOnePointPerSecond() {
        val a = pt(0, -33.0, speed = 10.0, alt = 100.0)
        val b = pt(10, -33.001, speed = 20.0, alt = 110.0)
        val out = TraceMath.fillGaps(listOf(a, b))
        assertEquals(11, out.size) // a, 9 filled, b
        assertEquals(listOf(a), out.take(1))
        assertEquals(b, out.last())
        val mid = out[5]
        assertTrue(mid.filled)
        assertEquals(t0 + 5000, mid.timeMs)
        assertEquals(-33.0005, mid.lat, 1e-9)
        assertEquals(15.0, mid.speedMps!!, 1e-9)
        assertEquals(105.0, mid.altM!!, 1e-9)
        assertEquals(9, out.count { it.filled })
    }

    @Test
    fun doesNotFillAGapOfThirtySecondsOrMore() {
        val out = TraceMath.fillGaps(listOf(pt(0, -33.0), pt(30, -33.001)))
        assertEquals(2, out.size)
        assertEquals(2, TraceMath.fillGaps(listOf(pt(0, -33.0), pt(31, -33.001))).size)
        assertEquals(30, TraceMath.fillGaps(listOf(pt(0, -33.0), pt(29, -33.001))).size) // 28 filled + 2
    }

    @Test
    fun doesNotFillAcrossSegmentsOrNormalOneSecondSteps() {
        assertEquals(2, TraceMath.fillGaps(listOf(pt(0, -33.0, seg = 0), pt(10, -33.001, seg = 1))).size)
        assertEquals(3, TraceMath.fillGaps(listOf(pt(0, -33.0), pt(1, -33.0001), pt(2, -33.0002))).size)
        // 1.4 s apart: closer than half a step to the next real fix, so nothing is inserted
        val jittery = listOf(pt(0, -33.0), TracePoint(t0 + 1400, -33.0001, 150.0, accuracyM = 5f))
        assertEquals(2, TraceMath.fillGaps(jittery).size)
    }

    // ---- distance, moving time, speed ---------------------------------------------------------------

    @Test
    fun distanceIsTheSumOfTheLegsOfMovingPoints() {
        val pts = (0..10).map { pt(it, -33.0 + it * 0.0001, speed = 11.0) } // 10 legs of ~11.12 m
        val s = TraceMath.stats(pts, 10_000)
        val expected = Geo.distanceM(-33.0, 150.0, -33.001, 150.0)
        assertEquals(expected, s.distanceM, 0.01)
        assertEquals(111.2, s.distanceM, 0.5)
        assertEquals(10, s.movingTimeS)
        assertEquals(10, s.durationS)
    }

    @Test
    fun speedIsDerivedFromDistanceWhenTheDeviceReportsNone() {
        val pts = (0..10).map { pt(it, -33.0 + it * 0.0001) } // ~11.12 m/s
        val s = TraceMath.stats(pts, 10_000)
        assertEquals(11.12, s.maxSpeedMps, 0.05)
        assertEquals(11.12, s.avgSpeedMps, 0.05)
    }

    @Test
    fun standingStillAddsNeitherDistanceNorMovingTime() {
        // 20 s at a red light with GPS wobble of a few metres and a reported speed of 0.
        val still = (0..20).map { pt(it, -33.0 + (it % 2) * 0.00002, speed = 0.1) }
        val moving = (21..30).map { pt(it, -33.0 + (it - 20) * 0.0001, speed = 11.0) }
        val s = TraceMath.stats(still + moving, 30_000)
        assertEquals(10, s.movingTimeS) // the 10 legs from t=20 to t=30; the wobble before that is standing still
        assertTrue("distance ${s.distanceM}", s.distanceM in 90.0..115.0)
        assertEquals(30, s.durationS)
        assertTrue(s.avgSpeedMps > 10.0 && s.avgSpeedMps < 12.0)
    }

    @Test
    fun movingTimeUsesActiveTimeNotWallTimeAndDoesNotCrossSegments() {
        val a = (0..5).map { pt(it, -33.0 + it * 0.0001, speed = 11.0, seg = 0) }
        // A pause of 100 s, then riding again from somewhere else.
        val b = (105..110).map { pt(it, -33.5 + (it - 105) * 0.0001, speed = 11.0, seg = 1) }
        val s = TraceMath.stats(a + b, 11_000) // 11 s of active time
        assertEquals(10, s.movingTimeS) // 5 legs + 5 legs; nothing for the jump between segments
        assertEquals(11, s.durationS)
        assertEquals(111.2, s.distanceM, 1.0) // 10 legs, not 55 km
    }

    @Test
    fun maxSpeedIsCappedAtWhatTheServerAccepts() {
        val pts = listOf(pt(0, -33.0, speed = 200.0), pt(1, -33.0001, speed = 200.0))
        assertEquals(TraceMath.MAX_PLAUSIBLE_MPS, TraceMath.stats(pts, 1000).maxSpeedMps, 1e-9)
    }

    // ---- elevation ----------------------------------------------------------------------------------

    @Test
    fun elevationGainIgnoresNoiseBelowTheThreshold() {
        // A flat road with +-1 m of GPS altitude noise.
        val noisy = (0..40).map { pt(it, -33.0 + it * 0.0001, speed = 11.0, alt = 100.0 + if (it % 2 == 0) 1.0 else -1.0) }
        assertEquals(0.0, TraceMath.stats(noisy, 40_000).elevationGainM!!, 1e-9)
    }

    @Test
    fun elevationGainCountsClimbsNotDescents() {
        // Up 20 m, down 20 m, up 10 m: gain 30.
        val alts = listOf(100.0, 105.0, 110.0, 120.0, 110.0, 100.0, 105.0, 110.0)
        val pts = alts.mapIndexed { i, a -> pt(i, -33.0 + i * 0.0001, speed = 11.0, alt = a) }
        assertEquals(30.0, TraceMath.stats(pts, 7_000).elevationGainM!!, 1e-9)
    }

    @Test
    fun elevationIsNullWithoutAltitudeAndNothingIsPositiveWithOne() {
        val none = (0..5).map { pt(it, -33.0 + it * 0.0001, speed = 11.0) }
        assertNull(TraceMath.stats(none, 5000).elevationGainM)
        assertNull(TraceMath.stats(listOf(pt(0, -33.0, alt = 100.0)), 0).elevationGainM)
    }

    @Test
    fun maxLeanIsAPlaceholder() {
        assertNull(TraceMath.stats(listOf(pt(0, -33.0), pt(1, -33.0001)), 1000).maxLeanDeg)
    }

    // ---- route ----------------------------------------------------------------------------------------

    @Test
    fun routeDropsConsecutivePointsOnTheSameSpot() {
        val pts = listOf(pt(0, -33.0), pt(1, -33.0), pt(2, -33.000001), pt(3, -33.0001))
        val route = TraceMath.routeOf(pts, 5)
        assertEquals(2, route.size)
        assertEquals(LatLngD(-33.0, 150.0), route[0])
    }

    @Test
    fun thinKeepsFirstAndLastAndTheRequestedCount() {
        val pts = (0 until 1000).map { LatLngD(it.toDouble() / 100, 0.0) }
        val thin = TraceMath.thin(pts, 100)
        assertEquals(100, thin.size)
        assertEquals(pts.first(), thin.first())
        assertEquals(pts.last(), thin.last())
        assertEquals(5, TraceMath.thin(pts.take(5), 100).size)
    }
}
