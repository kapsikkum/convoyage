package app.convoyage.nav

import app.convoyage.data.LatLngD
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The way back to a route after leaving it: the nearest point on the line, preferring the part still ahead. Shown
 * as a pulsing line from the car, a hint of which way to head rather than a turn-by-turn order. Pure, so it is tested.
 */
object Rejoin {
    /** Further than this from the line counts as off track. */
    const val OFF_TRACK_M = 40.0

    /**
     * Where to rejoin [line] from [me], or null when on it (within [offTrackM]) or the line is unusable. [fromIndex]
     * (the segment we were last on) keeps the target ahead of us on a route that doubles back.
     */
    fun target(line: List<LatLngD>, me: LatLngD, fromIndex: Int = 0, offTrackM: Double = OFF_TRACK_M): LatLngD? {
        if (line.size < 2) return null
        val k = cos(Math.toRadians(me.lat)) * M_PER_DEG
        var best: LatLngD? = null
        var bestD = Double.MAX_VALUE
        val start = fromIndex.coerceIn(0, line.size - 2)
        for (i in start until line.size - 1) {
            val a = line[i]
            val b = line[i + 1]
            val ax = (a.lng - me.lng) * k; val ay = (a.lat - me.lat) * M_PER_DEG
            val bx = (b.lng - me.lng) * k; val by = (b.lat - me.lat) * M_PER_DEG
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 < 1e-9) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < bestD) {
                bestD = d
                best = LatLngD(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
            }
        }
        return if (bestD > offTrackM) best else null
    }

    private const val M_PER_DEG = 111_320.0
}
