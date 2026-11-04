package app.convoyage

import app.convoyage.data.LatLngD
import app.convoyage.nav.Rejoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RejoinTest {
    // A road running east along -33.44, then doubling back west along -33.45.
    private val line = listOf(LatLngD(-33.44, 149.50), LatLngD(-33.44, 149.52), LatLngD(-33.45, 149.52), LatLngD(-33.45, 149.50))

    @Test fun onTheRoadThereIsNoHint() {
        assertNull(Rejoin.target(line, LatLngD(-33.4401, 149.51)))
    }

    @Test fun offTrackPointsToTheNearestPointOnTheLine() {
        val t = assertNotNull(Rejoin.target(line, LatLngD(-33.437, 149.51))).let { Rejoin.target(line, LatLngD(-33.437, 149.51))!! }
        assertEquals(-33.44, t.lat, 1e-6)
        assertEquals(149.51, t.lng, 1e-6)
    }

    @Test fun pastTheTurnTheHintStaysAhead() {
        // Between the two legs, nearer the first: from segment 2 on, the target is on the way back.
        val t = Rejoin.target(line, LatLngD(-33.443, 149.51), fromIndex = 2)!!
        assertEquals(-33.45, t.lat, 1e-6)
    }
}
