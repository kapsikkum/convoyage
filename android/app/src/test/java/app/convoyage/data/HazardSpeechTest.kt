package app.convoyage.data

import app.convoyage.nav.HazardAhead
import app.convoyage.nav.RouteHazards
import app.convoyage.protocol.Hazard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardSpeechTest {
    private fun h(kind: String, sub: String = "") = Hazard("h1", kind, sub, -33.4, 149.5)

    @Test fun camerasAndKindsAreSpokenWithRoundedDistance() {
        assertEquals("Speed camera ahead in 600 metres", RouteHazards.spoken(HazardAhead(h("police", "speed_camera"), 612.0)))
        assertEquals("Police ahead in 300 metres", RouteHazards.spoken(HazardAhead(h("police"), 280.0)))
        assertEquals("Road works just ahead", RouteHazards.spoken(HazardAhead(h("works"), 40.0)))
        assertTrue(RouteHazards.isCamera(h("police", "red_light_camera")))
    }
}
