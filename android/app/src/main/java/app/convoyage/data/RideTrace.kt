package app.convoyage.data

/**
 * One accepted GPS sample of a ride. [segment] increments at every pause/resume and at every break in the
 * trace (a gap of 30 s or more, or a reset after a run of impossible jumps): distance and moving time are
 * never measured across a segment boundary, although the drawn route still bridges it.
 */
data class TracePoint(
    val timeMs: Long,
    val lat: Double,
    val lng: Double,
    val altM: Double? = null,
    val speedMps: Double? = null,
    val accuracyM: Float = 0f,
    val segment: Int = 0,
    /** True for points [TraceMath.fillGaps] interpolated; never persisted. */
    val filled: Boolean = false,
)

/** The numbers of a ride. [movingTimeS] counts only time spent above [TraceMath.MOVING_MPS]. */
data class RideStats(
    val distanceM: Double = 0.0,
    val durationS: Long = 0,
    val movingTimeS: Long = 0,
    val maxSpeedMps: Double = 0.0,
    /** Average over moving time, which is what the server stores as `avgSpeedMps`. */
    val avgSpeedMps: Double = 0.0,
    /** Null when the device never reported an altitude. */
    val elevationGainM: Double? = null,
    /** Largest lean while recording in Ride mode (see [LeanSensor]); null if never measured. Not from the trace. */
    val maxLeanDeg: Double? = null,
) {
    companion object {
        val EMPTY = RideStats()
    }
}

/**
 * Decides which fixes become trace points. A fix is dropped when
 *  - its accuracy is worse than [maxAccuracyM] (or unknown),
 *  - it is not newer than the last accepted point,
 *  - it would need more than [maxSpeedMps] to get there from the last accepted point.
 * After [resetAfterRejects] jump rejections in a row the filter assumes the LAST accepted point was the wrong one
 * and starts a new segment from the new fix, so one bad first fix cannot block the whole ride.
 */
class TraceFilter(
    private val maxAccuracyM: Float = TraceMath.MAX_ACCURACY_M,
    private val maxSpeedMps: Double = TraceMath.MAX_PLAUSIBLE_MPS,
    private val breakGapMs: Long = TraceMath.GAP_LIMIT_MS,
    private val resetAfterRejects: Int = 5,
) {
    var segment = 0
        private set
    private var last: TracePoint? = null
    private var jumpRejects = 0

    /** Call when the ride resumes after a pause: the next point starts a new segment. */
    fun startNewSegment() {
        segment++
        last = null
        jumpRejects = 0
    }

    /** Continues an earlier ride: [lastPoint] is its newest saved point. */
    fun restoreFrom(lastPoint: TracePoint?) {
        last = lastPoint
        segment = lastPoint?.segment ?: 0
        jumpRejects = 0
    }

    /** The accepted point for [fix], or null when it is filtered out. */
    fun offer(fix: Fix): TracePoint? {
        if (fix.accuracyM > maxAccuracyM) return null
        var p = TracePoint(fix.timeMs, fix.lat, fix.lng, fix.altitudeM, fix.speedMps, fix.accuracyM, segment)
        val prev = last
        if (prev != null) {
            val dt = p.timeMs - prev.timeMs
            if (dt <= 0) return null
            if (dt >= breakGapMs) {
                segment++
                p = p.copy(segment = segment)
                jumpRejects = 0
            } else {
                val implied = Geo.distanceM(prev.lat, prev.lng, p.lat, p.lng) / (dt / 1000.0)
                if (implied > maxSpeedMps) {
                    jumpRejects++
                    if (jumpRejects < resetAfterRejects) return null
                    segment++
                    p = p.copy(segment = segment)
                    jumpRejects = 0
                } else {
                    jumpRejects = 0
                }
            }
        }
        last = p
        return p
    }
}

/**
 * Stats one point at a time, so the live readout does not rescan the whole trace every second.
 * Pairs of points are only measured inside one segment.
 */
class StatsAccumulator {
    private var prev: TracePoint? = null
    private var distanceM = 0.0
    private var movingMs = 0L
    private var maxSpeed = 0.0

    private var altRef: Double? = null
    private var altSeen = 0
    private var gain = 0.0

    fun add(p: TracePoint) {
        p.altM?.let { addAltitude(it) }
        val a = prev
        prev = p
        if (a == null || a.segment != p.segment) return
        val dt = p.timeMs - a.timeMs
        if (dt <= 0) return
        val d = Geo.distanceM(a.lat, a.lng, p.lat, p.lng)
        val v = (p.speedMps ?: (d / (dt / 1000.0))).coerceIn(0.0, TraceMath.MAX_PLAUSIBLE_MPS)
        if (v < TraceMath.MOVING_MPS) return // standing still: GPS jitter must not add distance
        distanceM += d
        movingMs += dt
        if (v > maxSpeed) maxSpeed = v
    }

    /** Altitude with hysteresis: a rise only counts once it is [TraceMath.ELEVATION_STEP_M] above the last reference. */
    private fun addAltitude(alt: Double) {
        altSeen++
        val ref = altRef
        if (ref == null) {
            altRef = alt
            return
        }
        val delta = alt - ref
        if (delta >= TraceMath.ELEVATION_STEP_M) {
            gain += delta
            altRef = alt
        } else if (delta <= -TraceMath.ELEVATION_STEP_M) {
            altRef = alt
        }
    }

    /** [elapsedMs] is the ride's active time (pauses excluded); the accumulator cannot know it. */
    fun snapshot(elapsedMs: Long): RideStats {
        val movingS = movingMs / 1000
        return RideStats(
            distanceM = distanceM,
            durationS = elapsedMs / 1000,
            movingTimeS = movingS,
            maxSpeedMps = maxSpeed,
            avgSpeedMps = if (movingMs > 0) (distanceM / (movingMs / 1000.0)).coerceAtMost(TraceMath.MAX_PLAUSIBLE_MPS) else 0.0,
            elevationGainM = if (altSeen >= 2) gain else null,
        )
    }
}

object TraceMath {
    /** Fixes worse than this are dropped. */
    const val MAX_ACCURACY_M = 30f

    /** Faster than this between two fixes is a jump, not riding (324 km/h). The server caps speeds at 120 m/s. */
    const val MAX_PLAUSIBLE_MPS = 90.0

    /** A gap shorter than this is filled in; a gap this long or longer breaks the trace into segments. */
    const val GAP_LIMIT_MS = 30_000L

    /** Below this (3.6 km/h) the vehicle counts as standing still. */
    const val MOVING_MPS = 1.0

    const val ELEVATION_STEP_M = 3.0

    /**
     * Interpolates one point per second across every gap inside a segment that is longer than a second
     * and shorter than [maxGapMs]. Longer gaps are left alone (they are segment breaks).
     */
    fun fillGaps(points: List<TracePoint>, maxGapMs: Long = GAP_LIMIT_MS, stepMs: Long = 1_000L): List<TracePoint> {
        if (points.size < 2) return points
        val out = ArrayList<TracePoint>(points.size)
        out += points[0]
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dt = b.timeMs - a.timeMs
            if (a.segment == b.segment && dt > stepMs && dt < maxGapMs) {
                var t = a.timeMs + stepMs
                // Stop half a step before b, so a 1.05 s jitter does not add a point 50 ms from the real one.
                while (b.timeMs - t >= stepMs / 2) {
                    val f = (t - a.timeMs).toDouble() / dt
                    out += TracePoint(
                        timeMs = t,
                        lat = a.lat + (b.lat - a.lat) * f,
                        lng = a.lng + (b.lng - a.lng) * f,
                        altM = if (a.altM != null && b.altM != null) a.altM + (b.altM - a.altM) * f else null,
                        speedMps = if (a.speedMps != null && b.speedMps != null) a.speedMps + (b.speedMps - a.speedMps) * f else null,
                        accuracyM = maxOf(a.accuracyM, b.accuracyM),
                        segment = a.segment,
                        filled = true,
                    )
                    t += stepMs
                }
            }
            out += b
        }
        return out
    }

    fun stats(points: List<TracePoint>, elapsedMs: Long): RideStats {
        val acc = StatsAccumulator()
        points.forEach(acc::add)
        return acc.snapshot(elapsedMs)
    }

    /** The positions to draw/upload: filled trace, with consecutive points that round to the same spot dropped. */
    fun routeOf(points: List<TracePoint>, precision: Int = 5): List<LatLngD> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLngD>(points.size)
        var lastLat = Long.MIN_VALUE
        var lastLng = Long.MIN_VALUE
        for (p in points) {
            val la = Math.round(p.lat * factor)
            val ln = Math.round(p.lng * factor)
            if (la == lastLat && ln == lastLng) continue
            out += LatLngD(p.lat, p.lng)
            lastLat = la
            lastLng = ln
        }
        return out
    }

    /** Points per kilometre-ish thinning for Canvas previews: keeps at most [max] evenly spaced points, always the last. */
    fun thin(points: List<LatLngD>, max: Int): List<LatLngD> {
        if (points.size <= max || max < 2) return points
        val out = ArrayList<LatLngD>(max)
        val step = (points.size - 1).toDouble() / (max - 1)
        for (i in 0 until max) out += points[Math.round(i * step).toInt().coerceAtMost(points.size - 1)]
        return out
    }
}
