package app.convoyage.nav

import java.util.Locale
import kotlin.math.roundToInt

/** One announcement, in the order they happen as a maneuver gets closer. */
enum class PromptStage { Far, Mid, Now }

data class Prompt(val stepIndex: Int, val stage: PromptStage, val text: String)

/**
 * Decides when to speak. For each upcoming maneuver there are three moments: far (about 800 m at 70 km/h), mid
 * (about 200 m) and "now" (at the turn). The distances shrink with speed (a car at 20 km/h does not need 800 m of warning), and
 * each is said once. If several are already behind us when we first see a maneuver (a short leg, or navigation just
 * started), only the nearest one is said.
 */
class PromptScheduler {
    private var stepIndex = -1
    /** Highest stage already said or skipped for [stepIndex]; -1 = none. */
    private var lastStage = -1

    /** Forget everything (a new route). */
    fun reset() {
        stepIndex = -1
        lastStage = -1
    }

    /**
     * @param nextStep the maneuver ahead (its index in the route and the step itself), null on the last step.
     * @return what to say now, or null.
     */
    fun update(nextIndex: Int?, nextStep: RouteStep?, distM: Double, speedMps: Double): Prompt? {
        if (nextIndex == null || nextStep == null) return null
        if (nextIndex != stepIndex) {
            stepIndex = nextIndex
            lastStage = -1
        }
        val speed = speedMps.coerceAtLeast(0.0)
        val reached = when {
            distM <= nowM(speed) -> PromptStage.Now
            distM <= midM(speed) -> PromptStage.Mid
            distM <= farM(speed) -> PromptStage.Far
            else -> return null
        }
        if (reached.ordinal <= lastStage) return null
        lastStage = reached.ordinal
        val what = Maneuvers.spoken(nextStep)
        val text = when (reached) {
            PromptStage.Now -> what
            else -> "In ${spokenDistance(distM)}, " + what.replaceFirstChar { it.lowercase(Locale.ROOT) }
        }
        return Prompt(nextIndex, reached, text)
    }

    companion object {
        fun farM(speedMps: Double) = (speedMps * 40.0).coerceIn(150.0, 800.0)
        fun midM(speedMps: Double) = (speedMps * 10.0).coerceIn(60.0, 200.0)
        fun nowM(speedMps: Double) = (speedMps * 2.5).coerceIn(15.0, 40.0)

        /** "200 metres", "1.2 kilometres", "2 kilometres": rounded the way a person would say it. */
        fun spokenDistance(m: Double): String {
            if (m >= 950) {
                val km = Math.round(m / 100.0) / 10.0
                return if (km == km.toLong().toDouble()) "${km.toLong()} kilometre${if (km.toLong() == 1L) "" else "s"}"
                else String.format(Locale.US, "%.1f kilometres", km)
            }
            val step = if (m >= 300) 50 else 10
            val r = ((m / step).roundToInt() * step).coerceAtLeast(10)
            return "$r metres"
        }
    }
}
