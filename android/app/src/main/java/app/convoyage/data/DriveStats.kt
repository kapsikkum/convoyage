package app.convoyage.data

import app.convoyage.api.RideSummary
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** The Home dashboard's numbers (Konvoi's Dashboard): lifetime, this month, streak, this week by day, bests. */
data class DriveStats(
    val lifetimeM: Double,
    val drives: Int,
    val lifetimeS: Long,
    val monthM: Double,
    /** This month against the whole of last month, as a percentage change; null when last month had nothing. */
    val monthChangePct: Int?,
    /** Days in a row with a drive, counting back from today (or yesterday, so the streak survives until tonight). */
    val streakDays: Int,
    /** Metres per day, Monday first, for the current week. */
    val week: List<Double>,
    /** Index of today in [week]. */
    val todayIndex: Int,
    val longestM: Double,
    val topSpeedMps: Double,
    val last: RideSummary?,
) {
    companion object {
        fun of(rides: List<RideSummary>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): DriveStats {
            val done = rides.filter { it.isFinished }.mapNotNull { r ->
                runCatching { Instant.parse(r.startedAt).atZone(zone).toLocalDate() }.getOrNull()?.let { r to it }
            }
            val monthStart = today.withDayOfMonth(1)
            val lastMonthStart = monthStart.minusMonths(1)
            val month = done.filter { !it.second.isBefore(monthStart) && !it.second.isAfter(today) }.sumOf { it.first.distanceM ?: 0.0 }
            val lastMonth = done.filter { !it.second.isBefore(lastMonthStart) && it.second.isBefore(monthStart) }.sumOf { it.first.distanceM ?: 0.0 }

            val days = done.map { it.second }.toHashSet()
            var cursor = if (today in days) today else today.minusDays(1)
            var streak = 0
            while (cursor in days) { streak++; cursor = cursor.minusDays(1) }

            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val week = (0 until 7).map { i -> val d = monday.plusDays(i.toLong()); done.filter { it.second == d }.sumOf { it.first.distanceM ?: 0.0 } }

            return DriveStats(
                lifetimeM = done.sumOf { it.first.distanceM ?: 0.0 },
                drives = done.size,
                lifetimeS = done.sumOf { it.first.durationS ?: 0L },
                monthM = month,
                monthChangePct = if (lastMonth > 0) Math.round((month - lastMonth) / lastMonth * 100).toInt() else null,
                streakDays = streak,
                week = week,
                todayIndex = (today.dayOfWeek.value - 1),
                longestM = done.maxOfOrNull { it.first.distanceM ?: 0.0 } ?: 0.0,
                topSpeedMps = done.maxOfOrNull { it.first.maxSpeedMps ?: 0.0 } ?: 0.0,
                last = done.maxByOrNull { it.first.startedAt }?.first,
            )
        }
    }
}
