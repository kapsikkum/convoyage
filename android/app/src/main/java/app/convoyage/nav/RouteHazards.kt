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

    /** The nearest hazard ahead of [along], if it is within [maxAheadM]. */
    fun nextAhead(onRoute: List<RouteHazard>, along: Double, maxAheadM: Double = WARN_M): HazardAhead? =
        onRoute.firstOrNull { it.along >= along - 5.0 && it.along - along <= maxAheadM }
            ?.let { HazardAhead(it.hazard, (it.along - along).coerceAtLeast(0.0)) }
}
