package app.convoyage

import app.convoyage.data.CrashCountdown
import app.convoyage.data.CrashDetector
import app.convoyage.data.SosMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashDetectorTest {
    /** Rides at 20 m/s up to t=5000. Returns the detector. */
    private fun riding(): CrashDetector {
        val d = CrashDetector()
        for (t in 0..5000 step 1000) d.onSpeed(t.toLong(), 20.0)
        return d
    }

    @Test fun spikeThenTenSecondsStillTriggers() {
        val d = riding()
        d.onAccel(5200, 6.5)
        assertEquals(CrashDetector.Phase.Suspect, d.phase)
        assertEquals(6.5, d.peakG, 0.0)
        // Speed falls away, then 10 s of near-stationary readings.
        assertFalse(d.onSpeed(6000, 8.0))
        assertFalse(d.onSpeed(7000, 0.4))
        var fired = false
        for (t in 8000..17000 step 1000) fired = fired || d.onSpeed(t.toLong(), 0.2)
        assertTrue("fires once 10 s of stillness has passed", fired)
        assertEquals(CrashDetector.Phase.Cooldown, d.phase)
    }

    @Test fun doesNotFireBeforeTenSeconds() {
        val d = riding()
        d.onAccel(5200, 5.0)
        var fired = false
        for (t in 6000..15000 step 1000) fired = fired || d.onSpeed(t.toLong(), 0.0) // 9 s of stillness
        assertFalse(fired)
        assertEquals(CrashDetector.Phase.Suspect, d.phase)
    }

    @Test fun softBumpBelowFourGIsIgnored() {
        val d = riding()
        d.onAccel(5200, 3.9)
        assertEquals(CrashDetector.Phase.Idle, d.phase)
        var fired = false
        for (t in 6000..30000 step 1000) fired = fired || d.onSpeed(t.toLong(), 0.0)
        assertFalse(fired)
    }

    @Test fun ridingOnAfterTheSpikeResets() {
        val d = riding()
        d.onAccel(5200, 7.0)
        // Pothole: the speed never drops.
        var fired = false
        for (t in 6000..40000 step 1000) fired = fired || d.onSpeed(t.toLong(), 18.0)
        assertFalse(fired)
        assertEquals(CrashDetector.Phase.Idle, d.phase)
    }

    @Test fun movingAgainBeforeTenSecondsRestartsTheStillnessTimer() {
        val d = riding()
        d.onAccel(5200, 6.0)
        for (t in 6000..11000 step 1000) assertFalse(d.onSpeed(t.toLong(), 0.0)) // 5 s still
        assertFalse(d.onSpeed(12000, 3.0)) // creeping away resets the timer
        var fired = false
        for (t in 13000..21000 step 1000) fired = fired || d.onSpeed(t.toLong(), 0.0) // only 8 s again
        assertFalse(fired)
        assertTrue(d.onSpeed(23000, 0.0))
    }

    @Test fun aSpikeWhileParkedDoesNotCountAsACrash() {
        val d = CrashDetector()
        for (t in 0..20000 step 1000) d.onSpeed(t.toLong(), 0.0)
        d.onAccel(21000, 9.0) // phone dropped on the floor
        assertEquals(CrashDetector.Phase.Idle, d.phase)
    }

    @Test fun unconfirmedSpikeTimesOut() {
        val d = riding()
        d.onAccel(5200, 6.0)
        // Slow rolling for over a minute: neither still nor fast.
        for (t in 6000..80000 step 1000) assertFalse(d.onSpeed(t.toLong(), 3.0))
        assertEquals(CrashDetector.Phase.Idle, d.phase)
    }

    @Test fun missingSpeedReadingsAreIgnored() {
        val d = riding()
        d.onAccel(5200, 6.0)
        for (t in 6000..30000 step 1000) assertFalse(d.onSpeed(t.toLong(), null))
        assertEquals(CrashDetector.Phase.Suspect, d.phase)
    }

    @Test fun cooldownStopsASecondAlertForTheSameEvent() {
        val d = riding()
        d.onAccel(5200, 6.0)
        var count = 0
        for (t in 6000..60000 step 1000) {
            if (d.onSpeed(t.toLong(), 0.0)) count++
            if (t == 30000) d.onAccel(t.toLong(), 8.0)
        }
        assertEquals(1, count)
    }

    @Test fun countdownRunsThirtySecondsThenExpiresOnce() {
        val c = CrashCountdown()
        c.start(1_000)
        assertEquals(CrashCountdown.Phase.Counting, c.phase)
        assertEquals(30, c.remainingSeconds(1_000))
        assertEquals(15, c.remainingSeconds(16_000))
        assertFalse(c.tick(30_999))
        assertTrue(c.tick(31_000))
        assertEquals(CrashCountdown.Phase.Expired, c.phase)
        assertFalse("expires only once", c.tick(32_000))
        assertEquals(0, c.remainingSeconds(32_000))
    }

    @Test fun imOkCancelsTheCountdown() {
        val c = CrashCountdown()
        c.start(0)
        c.cancel()
        assertEquals(CrashCountdown.Phase.Cancelled, c.phase)
        assertFalse(c.tick(60_000))
    }

    @Test fun sosMessageCarriesTheLocationAndTheTag() {
        val text = SosMessage.text("Sam", -33.4193, 149.5775)
        assertTrue(SosMessage.isSos(text))
        assertTrue(text.contains("-33.41930, 149.57750"))
        assertTrue(text.contains("maps.google.com/?q=-33.41930,149.57750"))
        assertTrue(text.length <= 500)
        assertTrue(SosMessage.text("Sam", null, null).contains("Location unknown"))
        assertFalse(SosMessage.isSos("All good"))
    }
}
