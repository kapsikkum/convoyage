package app.convoyage

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.convoyage.design.ChamferShape
import app.convoyage.design.chamferPoints
import app.convoyage.design.sheetPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChamferTest {
    @Test fun cutsTopLeftAndBottomRight() {
        assertEquals(
            listOf(
                Offset(12f, 0f), Offset(100f, 0f), Offset(100f, 38f),
                Offset(88f, 50f), Offset(0f, 50f), Offset(0f, 12f),
            ),
            chamferPoints(width = 100f, height = 50f, cut = 12f),
        )
    }

    @Test fun otherTwoCornersStayRight() {
        val p = chamferPoints(100f, 50f, 12f)
        assertTrue("top-right corner is kept", Offset(100f, 0f) in p)
        assertTrue("bottom-left corner is kept", Offset(0f, 50f) in p)
        assertTrue("top-left corner is cut", Offset(0f, 0f) !in p)
        assertTrue("bottom-right corner is cut", Offset(100f, 50f) !in p)
    }

    @Test fun cutIsClampedToHalfTheShortSide() {
        val p = chamferPoints(40f, 20f, 100f)
        assertEquals(Offset(10f, 0f), p.first())
        assertEquals(Offset(0f, 10f), p.last())
    }

    @Test fun cutDiagonalsAreFortyFiveDegrees() {
        val p = chamferPoints(200f, 80f, 20f)
        // Top-left cut runs (0,c) -> (c,0): equal run and rise.
        assertEquals(p[0].x, p[5].y, 0f)
        // Bottom-right cut runs (w,h-c) -> (w-c,h).
        assertEquals(200f - p[3].x, p[3].y - p[2].y, 0f)
    }

    @Test fun zeroCutIsAPlainRectangle() {
        val p = chamferPoints(10f, 10f, 0f)
        assertEquals(Offset(0f, 0f), p.first())
        assertEquals(Offset(0f, 0f), p.last())
    }

    @Test fun shapeProducesAGenericOutlineNeverRounded() {
        val outline = ChamferShape(12.dp).createOutline(Size(120f, 60f), LayoutDirection.Ltr, Density(1f))
        assertTrue(outline is Outline.Generic)
    }

    @Test fun shapesWithEqualCutsAreEqual() {
        assertEquals(ChamferShape(6.dp), ChamferShape(6.dp))
        assertTrue(ChamferShape(6.dp) != ChamferShape(12.dp))
    }

    @Test fun sheetOnlyCutsTheTopLeftAndIsFlushAtTheBottom() {
        val p = sheetPoints(100f, 300f, 20f)
        assertEquals(listOf(Offset(20f, 0f), Offset(100f, 0f), Offset(100f, 300f), Offset(0f, 300f), Offset(0f, 20f)), p)
        assertTrue("bottom-right is square", Offset(100f, 300f) in p)
        assertTrue("bottom-left is square", Offset(0f, 300f) in p)
    }
}
