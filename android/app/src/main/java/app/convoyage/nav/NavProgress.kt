package app.convoyage.nav

/**
 * Where we are on a route. [stepIndex] is the step we are driving along; the next maneuver is the start of step
 * [nextIndex] (null once on the last step, which is the arrival).
 */
data class NavProgress(
    val snap: Snap,
    val stepIndex: Int,
    val nextIndex: Int?,
    val distToManeuverM: Double,
    val remainingM: Double,
    val remainingS: Double,
    val offRouteM: Double,
    val arrived: Boolean,
)

object NavEngine {
    /** Within this many metres of the end of the line, the trip is over. */
    const val ARRIVE_M = 30.0

    /**
     * Progress for a snapped position. [minStep] stops the step number from going backwards when GPS noise
     * puts us a few metres behind a maneuver we already passed.
     */
    fun progress(route: NavRoute, snap: Snap, minStep: Int = 0): NavProgress {
        val steps = route.steps
        val along = snap.along
        var idx = 0
        for (i in steps.indices) if (route.stepStart[i] <= along + 0.5) idx = i
        idx = maxOf(idx, minStep).coerceAtMost((steps.size - 1).coerceAtLeast(0))
        val next = if (idx + 1 < steps.size) idx + 1 else null
        val remaining = (route.length - along).coerceAtLeast(0.0)
        val toManeuver = if (next != null) (route.stepStart[next] - along).coerceAtLeast(0.0) else remaining
        var remainingS = 0.0
        if (steps.isNotEmpty()) {
            val start = route.stepStart[idx]
            val end = route.stepEnd(idx)
            val frac = if (end > start) ((end - along) / (end - start)).coerceIn(0.0, 1.0) else 0.0
            remainingS = steps[idx].durationS * frac
            for (j in idx + 1 until steps.size) remainingS += steps[j].durationS
        } else if (route.length > 0) {
            remainingS = route.durationS * remaining / route.length
        }
        return NavProgress(snap, idx, next, toManeuver, remaining, remainingS, snap.offM, arrived = remaining <= ARRIVE_M)
    }
}

/**
 * "More than [thresholdM] off the line for more than [holdMs]" starts a reroute; after one, the next cannot start
 * for [minIntervalMs] (a bad GPS patch or a failing server must not hammer the route endpoint).
 */
class OffRouteDetector(
    private val thresholdM: Double = DEFAULT_THRESHOLD_M,
    private val holdMs: Long = 8_000,
    private val minIntervalMs: Long = 15_000,
) {
    private var since: Long? = null
    private var lastTrigger: Long? = null

    /** True exactly when a reroute should be requested now. */
    fun update(offRouteM: Double, nowMs: Long): Boolean {
        if (offRouteM <= thresholdM) {
            since = null
            return false
        }
        val s = since ?: nowMs.also { since = it }
        if (nowMs - s <= holdMs) return false
        val last = lastTrigger
        if (last != null && nowMs - last < minIntervalMs) return false
        lastTrigger = nowMs
        return true
    }

    /** A new route is in: forget the old offence (the throttle stays). */
    fun reset() {
        since = null
    }

    companion object {
        /** Further than this from the line counts as off it. */
        const val DEFAULT_THRESHOLD_M = 50.0
    }
}
