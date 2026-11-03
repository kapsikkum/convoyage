package app.convoyage.nav

import app.convoyage.protocol.Hazard

/** A hazard that sits on the route, [along] metres from the start of the line. */
data class RouteHazard(val hazard: Hazard, val along: Double)

/** A [RouteHazard] ahead of us, [aheadM] metres away along the route. */
data class HazardAhead(val hazard: Hazard, val aheadM: Double)

object RouteHazards {
    /** How far from the line a hazard may be and still count as "on the route". */
    const val LATERAL_M = 60.0

    /** Warn when one is this close ahead. */
    const val WARN_M = 1_000.0

    /** The hazards within [LATERAL_M] of the line, with where along it they are. Cheap to keep until the route or the hazards change. */
    fun onRoute(route: NavRoute, hazards: List<Hazard>): List<RouteHazard> =
        hazards.mapNotNull { h ->
            val s = NavMath.snapWholeLine(route, h.lat, h.lng)
            if (s.offM <= LATERAL_M) RouteHazard(h, s.along) else null
        }.sortedBy { it.along }

    /** What the voice says for a hazard ahead: "Speed camera ahead in 600 metres". */
    fun spoken(a: HazardAhead): String {
        val what = when {
            a.hazard.subkind.contains("camera") -> "Speed camera"
            a.hazard.subkind.isNotBlank() -> a.hazard.subkind.replace('_', ' ').replaceFirstChar { it.uppercase() }
            a.hazard.kind == "police" -> "Police"
            a.hazard.kind == "works" -> "Road works"
            a.hazard.kind == "crash" -> "Crash"
            else -> "Hazard"
        }
        val m = a.aheadM
        val dist = if (m < 100) "just ahead" else "ahead in ${(Math.round(m / 100.0) * 100).toInt()} metres"
        return "$what $dist"
    }

    /** A camera report (red banner, like Konvoi's). */
    fun isCamera(h: Hazard) = h.subkind.contains("camera")

    /** The nearest hazard ahead of [along], if it is within [maxAheadM]. */
    fun nextAhead(onRoute: List<RouteHazard>, along: Double, maxAheadM: Double = WARN_M): HazardAhead? =
        onRoute.firstOrNull { it.along >= along - 5.0 && it.along - along <= maxAheadM }
            ?.let { HazardAhead(it.hazard, (it.along - along).coerceAtLeast(0.0)) }
}
