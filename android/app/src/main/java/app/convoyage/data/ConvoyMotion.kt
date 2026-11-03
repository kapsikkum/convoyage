package app.convoyage.data

import app.convoyage.protocol.Member
import app.convoyage.protocol.Position
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Where to draw a member right now. [predicted] = moved on from the last fix by dead reckoning. */
data class MemberPose(val lat: Double, val lng: Double, val heading: Double?, val predicted: Boolean)

/**
 * Smooth member pins between position fixes (port of PhotoScout's convoy motion): a new fix is blended in over
 * [BLEND_MS] instead of jumping, and a moving member is carried forward along their heading for up to
 * [MAX_PREDICTION_MS] / [MAX_PREDICTION_M] until the next fix. Display only: never sent or used for regroup.
 */
class ConvoyMotion {
    companion object {
        const val BLEND_MS = 600L
        const val MAX_PREDICTION_MS = 3_000L
        const val MAX_PREDICTION_M = 90.0
        private const val MAX_SPEED_MPS = 70.0
        private const val MAX_BLEND_M = 150.0
        private const val MAX_FIX_GAP_MS = 10_000L
    }

    private class Track(val fix: Position, val receivedAt: Long, val speed: Double, val heading: Double?, val from: MemberPose?)

    private val tracks = HashMap<String, Track>()

    fun isAnimating(now: Long): Boolean = tracks.values.any { t ->
        (t.from != null && now - t.receivedAt < BLEND_MS) ||
            (t.heading != null && t.speed >= 1 && now - t.fix.ts < MAX_PREDICTION_MS && t.speed * max(0L, now - t.fix.ts) / 1000.0 < MAX_PREDICTION_M)
    }

    fun update(members: List<Member>, now: Long) {
        val alive = HashSet<String>()
        for (m in members) {
            val fix = m.position ?: continue
            if (!m.online) continue
            alive += m.id
            val prev = tracks[m.id]
            // Repaints, room metadata and old packets must not restart the blend or the prediction.
            if (prev != null && prev.fix.ts >= fix.ts) continue
            val gap = if (prev != null) fix.ts - prev.fix.ts else 0L
            val metres = if (prev != null) Geo.distanceM(prev.fix.lat, prev.fix.lng, fix.lat, fix.lng) else 0.0
            val inferred = prev != null && gap in 500..MAX_FIX_GAP_MS && metres >= 3 && metres / (gap / 1000.0) <= MAX_SPEED_MPS
            val heading = fix.heading ?: if (inferred) Geo.bearingDeg(prev!!.fix.lat, prev.fix.lng, fix.lat, fix.lng) else null
            val speed = fix.speed ?: if (inferred) metres / (gap / 1000.0) else 0.0
            val drawn = pose(m.id, now)
            val canBlend = drawn != null && gap <= MAX_FIX_GAP_MS && Geo.distanceM(drawn.lat, drawn.lng, fix.lat, fix.lng) <= MAX_BLEND_M
            val fresh = abs(now - fix.ts) <= MAX_PREDICTION_MS
            tracks[m.id] = Track(fix, now, if (fresh) speed.coerceIn(0.0, MAX_SPEED_MPS) else 0.0, heading, if (canBlend) drawn else null)
        }
        tracks.keys.retainAll(alive)
    }

    fun pose(id: String, now: Long): MemberPose? {
        val t = tracks[id] ?: return null
        val elapsed = max(0L, now - t.receivedAt)
        val age = max(0L, now - t.fix.ts)
        val predictionMs = min(MAX_PREDICTION_MS, age)
        val distance = if (t.heading == null || t.speed < 1) 0.0 else min(MAX_PREDICTION_M, t.speed * predictionMs / 1000.0)
        val (lat, lng) = destination(t.fix.lat, t.fix.lng, t.heading ?: 0.0, distance)
        val target = MemberPose(lat, lng, t.heading, distance > 1)
        val from = t.from
        if (from == null || elapsed >= BLEND_MS) return target
        val x = elapsed.toDouble() / BLEND_MS
        val blend = x * x * (3 - 2 * x)
        val heading = if (from.heading != null && target.heading != null) {
            val delta = ((target.heading - from.heading + 540) % 360) - 180
            (from.heading + delta * blend + 360) % 360
        } else target.heading
        return MemberPose(
            lat = from.lat + (target.lat - from.lat) * blend,
            lng = from.lng + ((((target.lng - from.lng) + 540) % 360) - 180) * blend,
            heading = heading,
            predicted = target.predicted,
        )
    }

    /** The point [metres] from (lat, lng) along [bearingDeg]. */
    private fun destination(lat: Double, lng: Double, bearingDeg: Double, metres: Double): Pair<Double, Double> {
        if (metres <= 0) return lat to lng
        val r = 6_371_000.0
        val d = metres / r
        val b = bearingDeg * PI / 180
        val p1 = lat * PI / 180
        val l1 = lng * PI / 180
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        val outLng = ((l2 * 180 / PI + 540) % 360) - 180
        return p2 * 180 / PI to outLng
    }
}
