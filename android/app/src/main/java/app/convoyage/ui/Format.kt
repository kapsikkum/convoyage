package app.convoyage.ui

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Number and time formatting for readouts. Pure, so it can be tested. Always `Locale.US`: mono readouts must not change shape. */
object Fmt {
    /** "12:34" under an hour, "1:02:03" from an hour. */
    fun clock(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }

    /** Distance to the next turn for the card: "300 M" (to 10 m, 5 m under 100), "1.2 KM". */
    fun navDistance(m: Double): String {
        val d = m.coerceAtLeast(0.0)
        return when {
            d >= 1000 -> String.format(Locale.US, "%.1f KM", d / 1000.0)
            d >= 100 -> String.format(Locale.US, "%d M", Math.round(d / 10.0) * 10)
            else -> String.format(Locale.US, "%d M", Math.round(d / 5.0) * 5)
        }
    }

    /** "18:42" in the phone's time zone. */
    fun clockOfDay(epochMs: Long): String =
        DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))

    /** "12 min", "1 h 05 min": a time left, for the ETA sheet. */
    fun duration(totalSeconds: Double): String {
        val min = Math.round(totalSeconds.coerceAtLeast(0.0) / 60.0)
        return if (min >= 60) String.format(Locale.US, "%d h %02d min", min / 60, min % 60) else "$min min"
    }

    /** Kilometres: one decimal under 100 km, none above. "0.0" for nothing. */
    fun km(metres: Double): String {
        val km = metres.coerceAtLeast(0.0) / 1000.0
        return if (km >= 100) String.format(Locale.US, "%.0f", km) else String.format(Locale.US, "%.1f", km)
    }

    fun kmh(mps: Double): String = String.format(Locale.US, "%.0f", mps.coerceAtLeast(0.0) * 3.6)

    fun metres(m: Double): String = String.format(Locale.US, "%.0f", m.coerceAtLeast(0.0))

    /** "H M" style total for lifetime seat time: "12 h" or "42 min". */
    fun hoursOrMinutes(totalSeconds: Long): Pair<String, String> =
        if (totalSeconds >= 3600) String.format(Locale.US, "%.1f", totalSeconds / 3600.0) to "h" else (totalSeconds / 60).toString() to "min"

    /** "JUST NOW", "5 MIN AGO", "2 H AGO", "3 D AGO", then the date. */
    fun timeAgo(iso: String, now: Instant = Instant.now()): String {
        val then = parse(iso) ?: return ""
        val d = Duration.between(then, now)
        val min = d.toMinutes()
        return when {
            min < 1 -> "JUST NOW"
            min < 60 -> "$min MIN AGO"
            d.toHours() < 24 -> "${d.toHours()} H AGO"
            d.toDays() < 7 -> "${d.toDays()} D AGO"
            else -> DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(then).uppercase()
        }
    }

    /** "Sun 3 Nov · 7:45 am" in the phone's time zone. */
    fun whenLine(epochMs: Long): String =
        DateTimeFormatter.ofPattern("EEE d MMM · h:mm a", Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))

    fun whenLine(iso: String): String = parse(iso)?.let { whenLine(it.toEpochMilli()) } ?: ""

    fun parse(iso: String): Instant? = try {
        Instant.parse(iso)
    } catch (_: Exception) {
        null
    }

    /** "Morning ride", "Afternoon ride", "Evening ride" from a local hour. */
    fun defaultTitle(hour: Int): String = when (hour) {
        in 5..11 -> "Morning ride"
        in 12..17 -> "Afternoon ride"
        in 18..21 -> "Evening ride"
        else -> "Night ride"
    }
}
