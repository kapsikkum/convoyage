package app.convoyage

import app.convoyage.data.ConvoyMotion
import app.convoyage.data.Geo
import app.convoyage.protocol.Member
import app.convoyage.protocol.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvoyMotionTest {
    private fun member(lat: Double, lng: Double, ts: Long, heading: Double? = 90.0, speed: Double? = 20.0) =
        Member("sam", "Sam", online = true, position = Position(lat, lng, heading, speed, ts))

    @Test fun predictsAlongHeadingAndCapsAt90m() {
        val m = ConvoyMotion()
        m.update(listOf(member(-33.42, 149.57, 1_000)), 1_000)
        val one = m.pose("sam", 2_000)!!
        assertEquals(20.0, Geo.distanceM(-33.42, 149.57, one.lat, one.lng), 0.5)
        assertTrue(one.predicted)
        val late = m.pose("sam", 60_000)!!
        assertEquals(60.0, Geo.distanceM(-33.42, 149.57, late.lat, late.lng), 0.5) // 3 s at 20 m/s
        assertFalse(m.isAnimating(60_000))
    }

    @Test fun blendsToANewFixInsteadOfJumping() {
        val m = ConvoyMotion()
        m.update(listOf(member(-33.42, 149.57, 1_000, speed = 0.0)), 1_000)
        m.update(listOf(member(-33.42, 149.5705, 2_000, speed = 0.0)), 2_000)
        val start = m.pose("sam", 2_000)!!
        assertEquals(149.57, start.lng, 1e-7)
        val mid = m.pose("sam", 2_300)!!
        assertTrue(mid.lng > 149.57 && mid.lng < 149.5705)
        assertEquals(149.5705, m.pose("sam", 2_600)!!.lng, 1e-7)
    }

    @Test fun anOldOrRepeatedFixDoesNotRestart() {
        val m = ConvoyMotion()
        m.update(listOf(member(-33.42, 149.57, 5_000, speed = 0.0)), 5_000)
        m.update(listOf(member(-33.0, 149.0, 4_000, speed = 0.0)), 6_000)
        assertEquals(149.57, m.pose("sam", 6_000)!!.lng, 1e-7)
        m.update(emptyList(), 7_000)
        assertEquals(null, m.pose("sam", 7_000))
    }
}
