package app.convoyage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfMotionTest {
    private fun fix(lat: Double, lng: Double, t: Long, heading: Double? = null) = Fix(lat, lng, heading, null, 5f, t)

    @Test fun glidesBetweenFixesAndFillsHeadingAndSpeed() {
        val m = SelfMotion()
        m.update(fix(-33.4400, 149.5600, 0), 0)
        // 40 m south one second later.
        val filled = m.update(fix(-33.44036, 149.5600, 1000), 1000)
        assertEquals(180.0, filled.headingDeg!!, 1.0)
        assertEquals(40.0, filled.speedMps!!, 1.0)
        val half = m.poseAt(1500)!!
        assertEquals(-33.44018, half.lat, 0.00002)
        assertTrue(m.isAnimating(1500))
        assertEquals(-33.44036, m.poseAt(2500)!!.lat, 1e-9)
        assertFalse(m.isAnimating(2500))
    }

    @Test fun aReportedStandstillIsOverruledByMovement() {
        val m = SelfMotion()
        m.update(Fix(-33.4400, 149.5600, 0.0, 0.0, 5f, 0), 0)
        // Emulator-style fix: bearing 0, speed 0, but 40 m west in a second.
        val f = m.update(Fix(-33.4400, 149.55957, 0.0, 0.0, 5f, 1000), 1000)
        assertEquals(270.0, f.headingDeg!!, 1.0)
        assertEquals(40.0, f.speedMps!!, 1.5)
    }

    @Test fun bigJumpsAreNotAnimatedAndHeadingsBlendTheShortWay() {
        val m = SelfMotion()
        m.update(fix(-33.44, 149.56, 0), 0)
        m.update(fix(-33.50, 149.56, 1000), 1000)
        assertEquals(-33.50, m.poseAt(1001)!!.lat, 1e-9)
        assertEquals(0.0, SelfMotion.blendHeading(350.0, 10.0, 0.5)!!, 1e-9)
        assertNotNull(SelfMotion.blendHeading(null, 90.0, 0.5))
    }
}
