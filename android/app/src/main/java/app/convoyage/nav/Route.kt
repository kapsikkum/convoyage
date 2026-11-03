package app.convoyage.nav

import app.convoyage.api.RouteResponse
import app.convoyage.data.LatLngD
import app.convoyage.data.Polyline

/** One OSRM step. The maneuver happens at ([lat], [lng]) and the step runs from there to the next step's maneuver. */
data class RouteStep(
    val type: String,
    val modifier: String?,
    val lat: Double,
    val lng: Double,
    val name: String,
    val distanceM: Double,
    val durationS: Double,
    val instruction: String,
)

/** A destination for navigation: a searched place or a dropped pin. */
data class Place(val name: String, val detail: String, val lat: Double, val lng: Double)

/**
 * A decoded route plus the distances the progress maths needs. [cum] is the distance along the line at each point,
 * [stepStart] the distance along the line at which each step begins. Immutable after construction.
 */
class NavRoute(
    val profile: String,
    val points: List<LatLngD>,
    val distanceM: Double,
    val durationS: Double,
    val steps: List<RouteStep>,
) {
    val cum: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + NavMath.dist(points[i - 1], points[i])
    }

    /** Length of the drawn line, metres. This (not the server's number) is what progress is measured against. */
    val length: Double get() = if (cum.isEmpty()) 0.0 else cum.last()

    /** Distance along the line where each step starts; the server's step lengths, scaled to the line's own length. */
    val stepStart: DoubleArray = run {
        val total = steps.sumOf { it.distanceM }
        val scale = if (total > 0.0 && length > 0.0) length / total else 1.0
        val out = DoubleArray(steps.size)
        var acc = 0.0
        for (i in steps.indices) {
            out[i] = acc
            acc += steps[i].distanceM * scale
        }
        out
    }

    /** Distance along the line at which step [i] ends (= where the next maneuver is). */
    fun stepEnd(i: Int): Double = if (i + 1 < steps.size) stepStart[i + 1] else length

    companion object {
        /** Null when the geometry cannot be decoded or has fewer than two points. */
        fun from(r: RouteResponse): NavRoute? {
            val pts = try {
                Polyline.decode(r.geometry, if (r.geometryPrecision == 5) 5 else 6)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (pts.size < 2) return null
            val steps = r.steps.mapNotNull { s ->
                val loc = s.maneuver.location
                if (loc.size < 2) null
                else RouteStep(s.maneuver.type, s.maneuver.modifier, loc[1], loc[0], s.name, s.distanceM, s.durationS, s.instruction)
            }
            return NavRoute(r.profile, pts, r.distanceM, r.durationS, steps)
        }
    }
}
