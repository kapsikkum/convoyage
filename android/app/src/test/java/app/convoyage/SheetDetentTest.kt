package app.convoyage

import app.convoyage.ui.Detent
import app.convoyage.ui.settleDetent
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetDetentTest {
    private val peek = 300f
    private val half = 1000f
    private val full = 2200f

    @Test fun slowReleaseSnapsToTheNearestDetent() {
        assertEquals(Detent.Peek, settleDetent(400f, 0f, peek, half, full))
        assertEquals(Detent.Half, settleDetent(800f, 0f, peek, half, full))
        assertEquals(Detent.Full, settleDetent(2000f, 0f, peek, half, full))
    }

    @Test fun aFlingCarriesPastTheNearestDetent() {
        // Just above half but thrown up hard: full. Thrown down hard from half: peek.
        assertEquals(Detent.Full, settleDetent(1100f, -6000f, peek, half, full))
        assertEquals(Detent.Peek, settleDetent(1000f, 4500f, peek, half, full))
    }
}
