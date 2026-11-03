package app.convoyage.data

/**
 * Crash detection as a pure state machine (no Android types, no clock of its own): feed it accelerometer and
 * GPS-speed samples with their timestamps and it says when a crash looks likely.
 *
 * Rule: an acceleration spike above [spikeG] (total acceleration including gravity, in g) while we were recently
 * moving, followed by [stillMs] of near-stationary GPS speed. Moving on after the spike resets it, so a pothole,
 * a dropped phone or a hard stop at a light does not count. Detection only suggests; the app asks the rider.
 */
class CrashDetector(
    private val spikeG: Double = SPIKE_G,
    private val stillMps: Double = STILL_MPS,
    private val stillMs: Long = STILL_MS,
    /** The rider must have been at least this fast within [movingWindowMs] before the spike. */
    private val minMovingMps: Double = MOVING_MPS,
    private val movingWindowMs: Long = MOVING_WINDOW_MS,
    /** A spike that is not confirmed by stillness in this long is forgotten. */
    private val suspectTimeoutMs: Long = SUSPECT_TIMEOUT_MS,
    /** Moving again this long after the spike means the rider rode on. */
    private val resumeGraceMs: Long = RESUME_GRACE_MS,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
    enum class Phase { Idle, Suspect, Cooldown }

    var phase: Phase = Phase.Idle
        private set

    private var lastMovingAt: Long = Long.MIN_VALUE
    private var spikeAt: Long = 0
    private var stillSince: Long? = null
    private var cooldownUntil: Long = 0

    /** Peak of the spike that started the current suspicion, for the alert text. */
    var peakG: Double = 0.0
        private set

    /** Feeds one accelerometer sample (total acceleration in g). Never completes a detection by itself. */
    fun onAccel(tMs: Long, g: Double) {
        if (phase == Phase.Cooldown && tMs >= cooldownUntil) phase = Phase.Idle
        if (g < spikeG) return
        when (phase) {
            Phase.Idle -> {
                val wasMoving = lastMovingAt != Long.MIN_VALUE && tMs - lastMovingAt <= movingWindowMs
                if (wasMoving) {
                    phase = Phase.Suspect
                    spikeAt = tMs
                    stillSince = null
                    peakG = g
                }
            }
            Phase.Suspect -> peakG = maxOf(peakG, g)
            Phase.Cooldown -> Unit
        }
    }

    /** [mps] null = no speed reading (ignored). Returns true when a crash is detected (once per event). */
    fun onSpeed(tMs: Long, mps: Double?): Boolean {
        if (mps == null) return false
        if (phase == Phase.Cooldown && tMs >= cooldownUntil) phase = Phase.Idle
        if (mps >= minMovingMps) lastMovingAt = tMs
        if (phase != Phase.Suspect) return false

        if (tMs - spikeAt > suspectTimeoutMs) {
            reset()
            return false
        }
        if (mps <= stillMps) {
            val since = stillSince ?: tMs.also { stillSince = it }
            if (tMs - since >= stillMs) {
                phase = Phase.Cooldown
                cooldownUntil = tMs + cooldownMs
                stillSince = null
                return true
            }
        } else {
            stillSince = null
            if (tMs - spikeAt > resumeGraceMs) reset()
        }
        return false
    }

    fun reset() {
        phase = Phase.Idle
        stillSince = null
        peakG = 0.0
    }

    companion object {
        const val SPIKE_G = 4.0

        /** About 5 km/h. */
        const val STILL_MPS = 1.5
        const val STILL_MS = 10_000L

        /** About 14 km/h. */
        const val MOVING_MPS = 4.0
        const val MOVING_WINDOW_MS = 10_000L
        const val SUSPECT_TIMEOUT_MS = 60_000L
        const val RESUME_GRACE_MS = 15_000L
        const val COOLDOWN_MS = 120_000L
        const val STANDARD_GRAVITY = 9.80665
    }
}

/** What the "Are you OK?" countdown is doing. Pure, so the timing is testable. */
class CrashCountdown(private val totalMs: Long = TOTAL_MS) {
    enum class Phase { Idle, Counting, Cancelled, Expired }

    var phase: Phase = Phase.Idle
        private set
    private var startedAt = 0L

    fun start(nowMs: Long) {
        if (phase == Phase.Counting) return
        phase = Phase.Counting
        startedAt = nowMs
    }

    fun remainingMs(nowMs: Long): Long = if (phase == Phase.Counting) (totalMs - (nowMs - startedAt)).coerceIn(0, totalMs) else 0

    fun remainingSeconds(nowMs: Long): Int = ((remainingMs(nowMs) + 999) / 1000).toInt()

    fun cancel() {
        if (phase == Phase.Counting) phase = Phase.Cancelled
    }

    /** True exactly once: when the time is up while still counting. */
    fun tick(nowMs: Long): Boolean {
        if (phase != Phase.Counting || nowMs - startedAt < totalMs) return false
        phase = Phase.Expired
        return true
    }

    fun reset() {
        phase = Phase.Idle
    }

    companion object {
        const val TOTAL_MS = 30_000L
    }
}

/** Chat text for an unanswered crash alert. The v1 chat has no tag field, so SOS is a fixed prefix. */
object SosMessage {
    const val PREFIX = "SOS"

    fun text(name: String, lat: Double?, lng: Double?): String {
        val where = if (lat != null && lng != null) {
            val a = "%.5f".format(java.util.Locale.ROOT, lat)
            val b = "%.5f".format(java.util.Locale.ROOT, lng)
            "Location $a, $b https://maps.google.com/?q=$a,$b"
        } else {
            "Location unknown"
        }
        return "$PREFIX: possible crash, $name did not respond. $where"
    }

    fun isSos(text: String) = text.startsWith(PREFIX)

    /** The `convoy.status` note (140 chars max). The server attaches our position to an sos itself. */
    fun note(name: String, lat: Double?, lng: Double?): String {
        val where = if (lat != null && lng != null) " At ${"%.5f".format(java.util.Locale.ROOT, lat)}, ${"%.5f".format(java.util.Locale.ROOT, lng)}." else ""
        return "Possible crash: $name did not respond.$where".take(140)
    }
}
