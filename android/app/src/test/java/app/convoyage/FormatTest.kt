package app.convoyage

import app.convoyage.data.LatLngD
import app.convoyage.data.ServerUrl
import app.convoyage.ui.Fmt
import app.convoyage.ui.RouteProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FormatTest {
    @Test
    fun clockShowsHoursOnlyWhenThereAreSome() {
        assertEquals("0:00", Fmt.clock(0))
        assertEquals("12:34", Fmt.clock(12 * 60 + 34))
        assertEquals("1:02:03", Fmt.clock(3723))
        assertEquals("0:00", Fmt.clock(-5))
    }

    @Test
    fun distanceAndSpeedReadouts() {
        assertEquals("0.0", Fmt.km(0.0))
        assertEquals("12.4", Fmt.km(12_449.0))
        assertEquals("186", Fmt.km(185_800.0))
        assertEquals("64", Fmt.kmh(17.78))
        assertEquals("3.7", Fmt.hoursOrMinutes(13_320).first)
        assertEquals("42" to "min", Fmt.hoursOrMinutes(42 * 60))
    }

    @Test
    fun timeAgoUsesTheLargestSensibleUnit() {
        val now = Instant.parse("2026-11-03T12:00:00Z")
        assertEquals("JUST NOW", Fmt.timeAgo("2026-11-03T11:59:40.000Z", now))
        assertEquals("5 MIN AGO", Fmt.timeAgo("2026-11-03T11:55:00.000Z", now))
        assertEquals("2 H AGO", Fmt.timeAgo("2026-11-03T10:00:00.000Z", now))
        assertEquals("3 D AGO", Fmt.timeAgo("2026-10-31T12:00:00.000Z", now))
        assertEquals("", Fmt.timeAgo("not a date", now))
    }

    @Test
    fun serverAddressesAreNormalisedOrRefused() {
        assertEquals("http://192.168.1.20:8080", ServerUrl.normalize("192.168.1.20:8080"))
        assertEquals("http://192.168.1.20:8080", ServerUrl.normalize("  http://192.168.1.20:8080/ "))
        assertEquals("https://api.convoyage.app", ServerUrl.normalize("https://api.convoyage.app/"))
        assertNull(ServerUrl.normalize(""))
        assertNull(ServerUrl.normalize("ftp://example.com"))
        assertNull(ServerUrl.normalize("http://"))
        assertNull(ServerUrl.normalize("not a url"))
        assertTrue(ServerUrl.isCleartext("http://192.168.1.20:8080"))
        assertTrue(!ServerUrl.isCleartext("https://x.app"))
    }

    @Test
    fun routePreviewKeepsTheShapeAndStaysInsideTheBox() {
        // A route twice as tall as it is wide, in a square box: it should be centred and touch the top and bottom.
        val pts = listOf(LatLngD(0.0, 0.0), LatLngD(1.0, 0.0), LatLngD(2.0, 0.5))
        val out = RouteProjection.project(pts, 100f, 100f)
        assertEquals(3, out.size)
        assertEquals(0f, out.minOf { it.y }, 0.01f)
        assertEquals(100f, out.maxOf { it.y }, 0.01f)
        assertTrue(out.all { it.x in 0f..100f })
        // North is up: the northernmost point has the smallest y.
        assertEquals(out.minOf { it.y }, out[2].y, 0.01f)
        assertTrue(RouteProjection.project(emptyList(), 100f, 100f).isEmpty())
    }
}
