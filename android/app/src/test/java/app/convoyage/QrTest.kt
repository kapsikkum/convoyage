package app.convoyage

import app.convoyage.data.DeepLink
import app.convoyage.data.Qr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrTest {
    @Test fun inviteLinkEncodesToASquareGridWithFinderPatterns() {
        val m = Qr.modules(DeepLink.joinUrl("K7QXM2PR"))
        assertTrue(m.size >= 21)
        assertEquals("grid is square", m.size, m[0].size)
        // The three 7x7 finder squares: dark corner modules and a dark centre.
        val n = m.size
        for ((x, y) in listOf(0 to 0, n - 1 to 0, 0 to n - 1)) assertTrue("finder corner at $x,$y", m[y][x])
        assertTrue(m[3][3])
        assertTrue(m[3][n - 4])
        assertTrue(m[n - 4][3])
        // The ring inside the finder is light.
        assertTrue(!m[1][1])
    }

    @Test fun differentCodesGiveDifferentGrids() {
        val a = Qr.modules(DeepLink.joinUrl("K7QXM2PR"))
        val b = Qr.modules(DeepLink.joinUrl("K7QXM2PS"))
        assertEquals(a.size, b.size)
        assertTrue(a.indices.any { y -> !a[y].contentEquals(b[y]) })
    }
}
