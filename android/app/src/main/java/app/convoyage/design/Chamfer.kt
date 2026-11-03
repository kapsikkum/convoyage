package app.convoyage.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Signature chamfer sizes: chips/inputs, buttons/cards, sheets/ride tiles. */
val CutS = 6.dp
val CutM = 12.dp
val CutL = 20.dp

/**
 * Polygon of a chamfered rectangle, clockwise from the end of the top-left cut:
 * (c,0) (w,0) (w,h-c) (w-c,h) (0,h) (0,c). The cut is clamped so it never exceeds half the short side.
 */
fun chamferPoints(width: Float, height: Float, cut: Float): List<Offset> {
    val c = cut.coerceIn(0f, minOf(width, height) / 2f)
    return listOf(
        Offset(c, 0f),
        Offset(width, 0f),
        Offset(width, height - c),
        Offset(width - c, height),
        Offset(0f, height),
        Offset(0f, c),
    )
}

/** A rectangle with the top-left and bottom-right corners cut diagonally by [cut]. Never rounded. */
class ChamferShape(val cut: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        GenericShape { s, _ ->
            val pts = chamferPoints(s.width, s.height, with(density) { cut.toPx() })
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            close()
        }.createOutline(size, layoutDirection, density)

    override fun equals(other: Any?) = other is ChamferShape && other.cut == cut
    override fun hashCode() = cut.hashCode()
}

val CutSShape = ChamferShape(CutS)
val CutMShape = ChamferShape(CutM)
val CutLShape = ChamferShape(CutL)

/** Filled chamfered panel with a 1dp edge (default line-strong). */
@Composable
fun Modifier.cutPanel(
    fill: Color = Cv.colors.surface200,
    edge: Color = Cv.colors.lineStrong,
    shape: Shape = CutMShape,
): Modifier = this
    .clip(shape)
    .background(fill, shape)
    .border(1.dp, edge, shape)

/** One glow per region: a coloured shadow behind a shape (signal on the primary button / own pin). */
fun Modifier.glow(color: Color, shape: Shape, radius: Dp = 14.dp): Modifier =
    shadow(radius, shape, clip = false, ambientColor = color, spotColor = color)

/** Engineering grid backdrop used on hero panels. */
fun Modifier.gridBackdrop(color: Color, step: Dp = 24.dp): Modifier = drawBehind {
    val s = step.toPx()
    var x = 0f
    while (x < size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += s
    }
    var y = 0f
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += s
    }
}
