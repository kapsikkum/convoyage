package app.convoyage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import app.convoyage.data.Geo
import app.convoyage.data.LatLngD
import app.convoyage.design.CutMShape
import app.convoyage.design.Cv
import app.convoyage.design.cutPanel
import app.convoyage.nav.RouteStep
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * The next ~180 m of route as a flat wireframe "road ahead": two edges that bend with the road, a dashed centreline, a
 * few cross ticks, and where the car is at the bottom. Deliberately not a second map: no tiles, no textures, no 3D.
 *
 * [ahead] is the remaining line, starting at (or near) where we are; [headingDeg] is where we face, so the first
 * metres sit straight up the strip; [step] only says whether a maneuver tick belongs on the road.
 */
@Composable
fun RoadAheadStrip(
    ahead: List<LatLngD>,
    headingDeg: Double?,
    distToManeuverM: Double?,
    step: RouteStep?,
    modifier: Modifier = Modifier,
) {
    // Nothing to draw before the first fix or on a line too short to have a direction.
    if (ahead.size < 2) return
    val c = Cv.colors
    // The projection is kept in metres, not pixels, so it survives reuse and only rebuilds when the route ahead (which
    // already changes with progress) or the heading does. The Canvas only turns those metres into pixels.
    val projected = remember(ahead, headingDeg) { RoadAhead.project(ahead, headingDeg) }
    Box(modifier.fillMaxWidth().height(96.dp).cutPanel(c.glass, c.lineStrong, CutMShape)) {
        Canvas(Modifier.fillMaxSize()) {
            clipRect { drawRoadAhead(projected, distToManeuverM?.takeIf { step != null }, c.signal) }
        }
    }
}

/** One route point in the road's own frame: metres to the right of the heading, and metres along the line. */
private data class RoadPoint(val lateralM: Double, val distM: Double)

/** Turns a lat/lng line into the flat "ahead / to the right" frame the strip draws in. */
private object RoadAhead {
    /** How much of the road the strip shows. */
    const val HORIZON_M = 180.0

    private const val M_PER_DEG = 111_320.0

    /**
     * Haversine length along [line] from its first point, and the sideways offset from the initial heading at every
     * vertex: local flat-earth (a few hundred metres is small enough) rotated so "along" is the heading direction.
     * Stops at the horizon, including the vertex that crosses it so the road reaches the top of the panel.
     */
    fun project(line: List<LatLngD>, headingDeg: Double?): List<RoadPoint> {
        if (line.size < 2) return emptyList()
        val o = line[0]
        val first: LatLngD = line[1]
        val deg: Double = headingDeg?.takeIf { it.isFinite() }
            ?: Geo.bearingDeg(o.lat, o.lng, first.lat, first.lng)
        val h = Math.toRadians(deg)
        val sinH = sin(h)
        val cosH = cos(h)
        val cosLat = cos(Math.toRadians(o.lat))
        val out = ArrayList<RoadPoint>(line.size)
        out += RoadPoint(0.0, 0.0)
        var along = 0.0
        for (i in 1 until line.size) {
            val a = line[i - 1]
            val b = line[i]
            along += Geo.distanceM(a.lat, a.lng, b.lat, b.lng)
            val east = (b.lng - o.lng) * cosLat * M_PER_DEG
            val north = (b.lat - o.lat) * M_PER_DEG
            // Rotation into the heading frame: +lateral is right of the heading, +along is ahead.
            out += RoadPoint(east * cosH - north * sinH, along)
            if (along >= HORIZON_M) break
        }
        return out
    }
}

/**
 * Draws the strip. Distance compresses nonlinearly up the panel (`t^0.72`, so the near road gets most of the height)
 * and the road narrows as it recedes; both edges follow the projected centreline with their own screen normals, so
 * bends stay square to the road.
 */
private fun DrawScope.drawRoadAhead(points: List<RoadPoint>, maneuverM: Double?, signal: Color) {
    if (points.size < 2) return
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    val cx = w / 2f
    val topPad = 15.dp.toPx()
    val bottom = h - 4.dp.toPx()
    // Pixels per metre across: one width-relative number, so narrow and wide panels read the same.
    val lateralScale = w / 330f
    val halfWide = 16.dp.toPx()
    val halfNarrow = 4.dp.toPx()
    val n = points.size

    fun tOf(distM: Double): Float = (distM / RoadAhead.HORIZON_M).coerceIn(0.0, 1.0).toFloat()

    fun xOf(rp: RoadPoint): Float {
        val t = tOf(rp.distM)
        return cx + (rp.lateralM * lateralScale * (1.0 - 0.55 * t)).toFloat()
    }

    fun yOf(rp: RoadPoint): Float {
        val t = tOf(rp.distM)
        return (bottom - (h - topPad) * t.toDouble().pow(0.72)).toFloat()
    }

    val xs = FloatArray(n)
    val ys = FloatArray(n)
    val half = FloatArray(n)
    val nx = FloatArray(n)
    val ny = FloatArray(n)
    for (i in 0 until n) {
        xs[i] = xOf(points[i])
        ys[i] = yOf(points[i])
        half[i] = halfWide + (halfNarrow - halfWide) * tOf(points[i].distM)
    }
    // Screen normal of each vertex, from the chord through its neighbours (the ends borrow their one neighbour).
    for (i in 0 until n) {
        val a = (i - 1).coerceAtLeast(0)
        val b = (i + 1).coerceAtMost(n - 1)
        var tx = xs[b] - xs[a]
        var ty = ys[b] - ys[a]
        val len = hypot(tx, ty)
        if (len < 1e-3f) {
            tx = 0f
            ty = -1f
        } else {
            tx /= len
            ty /= len
        }
        nx[i] = -ty
        ny[i] = tx
    }

    val left = Path()
    val right = Path()
    val area = Path()
    for (i in 0 until n) {
        val px = xs[i] - nx[i] * half[i]
        val py = ys[i] - ny[i] * half[i]
        if (i == 0) {
            left.moveTo(px, py)
            area.moveTo(px, py)
        } else {
            left.lineTo(px, py)
            area.lineTo(px, py)
        }
    }
    for (i in n - 1 downTo 0) {
        val px = xs[i] + nx[i] * half[i]
        val py = ys[i] + ny[i] * half[i]
        if (i == n - 1) right.moveTo(px, py) else right.lineTo(px, py)
        area.lineTo(px, py)
    }
    area.close()

    // Faint wash so the wireframe sits on glass rather than floating, then the two edges.
    drawPath(area, signal.copy(alpha = 0.05f))
    drawPath(left, signal.copy(alpha = 0.5f), style = Stroke(1.2f.dp.toPx()))
    drawPath(right, signal.copy(alpha = 0.5f), style = Stroke(1.2f.dp.toPx()))

    val centre = Path()
    for (i in 0 until n) {
        if (i == 0) centre.moveTo(xs[i], ys[i]) else centre.lineTo(xs[i], ys[i])
    }
    drawPath(
        centre,
        signal.copy(alpha = 0.9f),
        style = Stroke(
            width = 1.4f.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()), 0f),
        ),
    )

    /** The centreline at [metres] along, with its width and normal there, or null when it is off the strip. */
    fun at(metres: Double): FloatArray? {
        if (metres <= 0.0 || metres > RoadAhead.HORIZON_M) return null
        for (i in 0 until n - 1) {
            val d0 = points[i].distM
            val d1 = points[i + 1].distM
            if (metres < d0 || metres > d1 || d1 <= d0) continue
            val f = ((metres - d0) / (d1 - d0)).toFloat()
            var mx = nx[i] + (nx[i + 1] - nx[i]) * f
            var my = ny[i] + (ny[i + 1] - ny[i]) * f
            val len = hypot(mx, my)
            if (len > 1e-3f) {
                mx /= len
                my /= len
            }
            return floatArrayOf(
                xs[i] + (xs[i + 1] - xs[i]) * f,
                ys[i] + (ys[i + 1] - ys[i]) * f,
                mx,
                my,
                half[i] + (half[i + 1] - half[i]) * f,
            )
        }
        return null
    }

    // Cross ticks: the retro part of the wireframe, one every so many metres up the road.
    for (m in doubleArrayOf(28.0, 60.0, 100.0, 145.0)) {
        val q = at(m) ?: continue
        drawLine(
            signal.copy(alpha = 0.28f),
            Offset(q[0] - q[2] * q[4], q[1] - q[3] * q[4]),
            Offset(q[0] + q[2] * q[4], q[1] + q[3] * q[4]),
            strokeWidth = 1.dp.toPx(),
        )
    }
    // A brighter bar where the next maneuver is, so a turn ahead reads on the road itself.
    maneuverM?.let { at(it) }?.let { q ->
        drawLine(
            signal.copy(alpha = 0.65f),
            Offset(q[0] - q[2] * q[4], q[1] - q[3] * q[4]),
            Offset(q[0] + q[2] * q[4], q[1] + q[3] * q[4]),
            strokeWidth = 2.dp.toPx(),
        )
    }

    // The vehicle pointer: a chevron pointing up at the bottom centre, with a cheap second, dimmer one behind it.
    val baseY = h - 2.dp.toPx()
    val halfW = 6.dp.toPx()
    val notch = 3.5f.dp.toPx()
    val tipY = baseY - 12.dp.toPx()
    val glow = Path().apply {
        moveTo(cx, tipY - 2.dp.toPx())
        lineTo(cx + halfW * 1.7f, baseY)
        lineTo(cx, baseY - notch)
        lineTo(cx - halfW * 1.7f, baseY)
        close()
    }
    val arrow = Path().apply {
        moveTo(cx, tipY)
        lineTo(cx + halfW, baseY)
        lineTo(cx, baseY - notch)
        lineTo(cx - halfW, baseY)
        close()
    }
    drawPath(glow, signal.copy(alpha = 0.18f))
    drawPath(arrow, signal)
}
