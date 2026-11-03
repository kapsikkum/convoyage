package app.convoyage

import app.convoyage.data.LatLngD
import app.convoyage.data.Polyline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The codec against vectors from Google's documentation (precision 5) and the same points at precision 6. */
class PolylineTest {
    private val google = listOf(LatLngD(38.5, -120.2), LatLngD(40.7, -120.95), LatLngD(43.252, -126.453))

    @Test
    fun encodesGooglesDocumentedExample() {
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Polyline.encode(google, 5))
    }

    @Test
    fun encodesPrecisionSix() {
        assertEquals("_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI", Polyline.encode(google, 6))
    }

    @Test
    fun decodesGooglesDocumentedExample() {
        val pts = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, pts.size)
        google.zip(pts).forEach { (want, got) ->
            assertEquals(want.lat, got.lat, 1e-9)
            assertEquals(want.lng, got.lng, 1e-9)
        }
    }

    @Test
    fun decodesPrecisionSix() {
        val pts = Polyline.decode("_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI", 6)
        assertEquals(43.252, pts[2].lat, 1e-9)
        assertEquals(-126.453, pts[2].lng, 1e-9)
    }

    @Test
    fun smallFirstPointAndZeroEncodeAsTheShortestForms() {
        assertEquals("??", Polyline.encode(listOf(LatLngD(0.0, 0.0)), 5))
        assertEquals("fawv~@wpnh|G~p@w|A", Polyline.encode(listOf(LatLngD(-33.4193, 149.5775), LatLngD(-33.4201, 149.5790)), 6))
    }

    @Test
    fun roundTripsTheExtremesAndAnEmptyTrace() {
        val extremes = listOf(LatLngD(-90.0, -180.0), LatLngD(90.0, 180.0), LatLngD(0.000001, -0.000001))
        val back = Polyline.decode(Polyline.encode(extremes, 6), 6)
        assertEquals(extremes, back)
        assertEquals("", Polyline.encode(emptyList()))
        assertTrue(Polyline.decode("").isEmpty())
    }

    @Test
    fun roundTripLosesNothingBeyondThePrecision() {
        val pts = (0 until 500).map { LatLngD(-33.4 + it * 0.00013, 149.5 + it * 0.00007) }
        val back = Polyline.decode(Polyline.encode(pts, 5), 5)
        assertEquals(pts.size, back.size)
        pts.zip(back).forEach { (a, b) ->
            assertEquals(a.lat, b.lat, 0.5e-5 + 1e-9)
            assertEquals(a.lng, b.lng, 0.5e-5 + 1e-9)
        }
    }

    @Test
    fun rejectsBrokenInput() {
        for (bad in listOf("_p~iF~ps|", " abc", "_p~iF~ps|U_")) {
            try {
                Polyline.decode(bad)
                fail("accepted: $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
