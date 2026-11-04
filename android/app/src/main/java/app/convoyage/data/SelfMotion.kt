package app.convoyage.data

import kotlin.math.atan2
import kotlin.math.cos

/**
 * Our own position as drawn on the map: it glides from where it was drawn to each new fix over about one fix
 * interval, instead of jumping 30 to 40 m a second at highway speed. Heading and speed are worked out from the
 * last two fixes when the device does not report them (the emulator never does; some phones drop them at low speed).
 * Display only: the raw fix still drives navigation, recording and what the convoy is sent. Pure, so it is tested.
 */
class SelfMotion {
    private var from: Fix? = null
    private var to: Fix? = null
    private var startMs = 0L
    private var durationMs = 1000L
    private var lastRaw: Fix? = null

    /** A new fix arrived at [nowMs]. Returns it with heading and speed filled in when they were missing. */
    fun update(raw: Fix, nowMs: Long): Fix {
        val prev = lastRaw
        lastRaw = raw
        var fix = raw
        if (prev != null) {
            val dt = (raw.timeMs - prev.timeMs).coerceAtLeast(1) / 1000.0
            val d = Geo.distanceM(prev.lat, prev.lng, raw.lat, raw.lng)
            val moved = d / dt
            // Some providers (the emulator always, phones at walking pace) report speed 0 and bearing 0 while the fixes
            // clearly move: trust the movement then.
            val reportedStill = (fix.speedMps ?: 0.0) < 0.5 && moved > 3.0
            if (fix.speedMps == null || reportedStill) fix = fix.copy(speedMps = moved)
            // Below ~2 m between fixes the bearing is noise: keep the one we had.
            if (fix.headingDeg == null || reportedStill) fix = fix.copy(headingDeg = if (d >= 2.0) bearing(prev, raw) else to?.headingDeg)
        }
        val shown = poseAt(nowMs)
        // A big jump (first fix, a tunnel, a teleport in testing) is not animated.
        val far = shown == null || Geo.distanceM(shown.lat, shown.lng, fix.lat, fix.lng) > 300
        from = if (far) fix else shown
        to = fix
        startMs = nowMs
        durationMs = if (prev == null) 1000L else (raw.timeMs - prev.timeMs).coerceIn(300L, 2000L)
        return fix
    }

    /** Where to draw us at [nowMs]. */
    fun poseAt(nowMs: Long): Fix? {
        val a = from ?: return to
        val b = to ?: return null
        val t = ((nowMs - startMs).toDouble() / durationMs).coerceIn(0.0, 1.0)
        if (t >= 1.0) return b
        return b.copy(
            lat = a.lat + (b.lat - a.lat) * t,
            lng = a.lng + (b.lng - a.lng) * t,
            headingDeg = blendHeading(a.headingDeg, b.headingDeg, t),
        )
    }

    fun isAnimating(nowMs: Long): Boolean = from != null && to != null && nowMs - startMs < durationMs

    companion object {
        fun bearing(a: Fix, b: Fix): Double {
            val k = cos(Math.toRadians((a.lat + b.lat) / 2))
            val deg = Math.toDegrees(atan2((b.lng - a.lng) * k, b.lat - a.lat))
            return (deg + 360) % 360
        }

        /** The short way round between two headings. */
        fun blendHeading(a: Double?, b: Double?, t: Double): Double? {
            if (a == null || b == null) return b ?: a
            var d = (b - a) % 360
            if (d > 180) d -= 360
            if (d < -180) d += 360
            return (a + d * t + 360) % 360
        }
    }
}
