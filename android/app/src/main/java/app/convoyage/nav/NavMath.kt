package app.convoyage.nav

import app.convoyage.data.Geo
import app.convoyage.data.LatLngD
import kotlin.math.cos
import kotlin.math.hypot

/** Where a position lands on a route line. [along] is metres from the start of the line; [offM] is how far off the line the position is. */
data class Snap(val seg: Int, val along: Double, val offM: Double, val lat: Double, val lng: Double)

/** Pure geometry for navigation. Local flat-earth maths per segment: exact enough over the few hundred metres a segment spans. */
object NavMath {
    private const val M_PER_DEG = 111_320.0
    private const val WINDOW_SEGMENTS = 400
    private const val WINDOW_MISS_M = 120.0
    private const val TIE_M = 0.5

    fun dist(a: LatLngD, b: LatLngD): Double = Geo.distanceM(a.lat, a.lng, b.lat, b.lng)

    /**
     * Nearest point on [route] to ([lat], [lng]). Looks at segments near [hint] first (so a route that doubles back on
     * itself does not make the position jump to the other pass), and falls back to the whole line when that window is
     * more than [WINDOW_MISS_M] away.
     */
    fun snap(route: NavRoute, lat: Double, lng: Double, hint: Int = 0): Snap {
        val n = route.points.size
        require(n >= 2) { "a route needs two points" }
        val from = (hint - 3).coerceIn(0, n - 2)
        val to = (hint + WINDOW_SEGMENTS).coerceIn(from, n - 2)
        var best = snapRange(route, lat, lng, from, to, hint)
        if (best.offM > WINDOW_MISS_M && (from > 0 || to < n - 2)) {
            val all = snapRange(route, lat, lng, 0, n - 2, hint)
            if (all.offM < best.offM) best = all
        }
        return best
    }

    /** Nearest point on the whole line (no hint window). For things that are not where we are, like a hazard. */
    fun snapWholeLine(route: NavRoute, lat: Double, lng: Double): Snap = snapRange(route, lat, lng, 0, route.points.size - 2, 0)

    private fun snapRange(route: NavRoute, lat: Double, lng: Double, from: Int, to: Int, hint: Int): Snap {
        val pts = route.points
        var best: Snap? = null
        val cosLat = cos(Math.toRadians(lat))
        for (i in from..to) {
            val a = pts[i]
            val b = pts[i + 1]
            val ax = (a.lng - lng) * cosLat * M_PER_DEG
            val ay = (a.lat - lat) * M_PER_DEG
            val bx = (b.lng - lng) * cosLat * M_PER_DEG
            val by = (b.lat - lat) * M_PER_DEG
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 1e-9) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val off = hypot(ax + t * dx, ay + t * dy)
            // On a near tie (the line passes here twice) the segment closest to where we were wins.
            if (best == null || off < best.offM - TIE_M || (off <= best.offM + TIE_M && Math.abs(i - hint) < Math.abs(best.seg - hint))) {
                val segLen = route.cum[i + 1] - route.cum[i]
                best = Snap(i, route.cum[i] + t * segLen, off, a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
            }
        }
        return best!!
    }

    /** The point [along] metres from the start of the line. */
    fun pointAt(route: NavRoute, along: Double): LatLngD {
        val a = along.coerceIn(0.0, route.length)
        val cum = route.cum
        var lo = 0
        var hi = cum.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= a) lo = mid else hi = mid
        }
        val segLen = cum[hi] - cum[lo]
        val t = if (segLen <= 0.0) 0.0 else (a - cum[lo]) / segLen
        val p = route.points[lo]
        val q = route.points[hi]
        return LatLngD(p.lat + (q.lat - p.lat) * t, p.lng + (q.lng - p.lng) * t)
    }

    /** Direction of travel on the line at [snap]: toward the point [lookAheadM] further along (stable even at walking pace). */
    fun routeBearing(route: NavRoute, snap: Snap, lookAheadM: Double = 30.0): Double {
        val here = LatLngD(snap.lat, snap.lng)
        val ahead = pointAt(route, snap.along + lookAheadM)
        if (dist(here, ahead) >= 1.0) return Geo.bearingDeg(here.lat, here.lng, ahead.lat, ahead.lng)
        // At the very end of the line: use the way we came.
        val back = pointAt(route, snap.along - lookAheadM)
        return Geo.bearingDeg(back.lat, back.lng, here.lat, here.lng)
    }

    /**
     * At most [max] points from the line, both ends included, for `convoy.route` (25 stops at most). The points are
     * the vertices that matter: the one that strays furthest from the chord of the stops chosen so far is added until
     * the budget is spent or the line is within [TOL_M]. Points spaced evenly by distance cut every corner and
     * roundabout by up to 50 m, which drew the shared route diagonally across blocks.
     */
    fun sampleStops(route: NavRoute, max: Int = 25): List<List<Double>> {
        val pts = route.points
        val keep = java.util.TreeSet<Int>()
        keep.add(0)
        keep.add(pts.lastIndex)
        // Each span remembers its worst vertex, so a round is one scan of the span that was just split.
        class Worst(val from: Int, val to: Int, val at: Int, val offM: Double)
        fun worst(from: Int, to: Int): Worst? {
            var bi = -1
            var bd = 0.0
            for (i in from + 1 until to) {
                val d = offSegmentM(pts[i], pts[from], pts[to])
                if (d > bd) { bd = d; bi = i }
            }
            return if (bi < 0) null else Worst(from, to, bi, bd)
        }
        val open = java.util.PriorityQueue<Worst>(compareByDescending { it.offM })
        worst(0, pts.lastIndex)?.let { open += it }
        while (keep.size < max.coerceAtLeast(2) && open.isNotEmpty()) {
            val w = open.poll()
            if (w.offM < TOL_M) break
            keep.add(w.at)
            worst(w.from, w.at)?.let { open += it }
            worst(w.at, w.to)?.let { open += it }
        }
        return keep.map { listOf(pts[it].lng, pts[it].lat) }
    }

    /** Stops within this far of the line are not worth a slot. */
    const val TOL_M = 1.0

    /** Distance from [p] to the segment [a]-[b], metres. */
    fun offSegmentM(p: LatLngD, a: LatLngD, b: LatLngD): Double {
        val cosLat = cos(Math.toRadians(p.lat))
        val ax = (a.lng - p.lng) * cosLat * M_PER_DEG
        val ay = (a.lat - p.lat) * M_PER_DEG
        val bx = (b.lng - p.lng) * cosLat * M_PER_DEG
        val by = (b.lat - p.lat) * M_PER_DEG
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 1e-9) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }
}
