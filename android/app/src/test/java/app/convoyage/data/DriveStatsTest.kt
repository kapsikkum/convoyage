package app.convoyage.data

import app.convoyage.api.RideSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class DriveStatsTest {
    private fun ride(id: String, day: String, km: Double, status: String = "finished") =
        RideSummary(id = id, status = status, startedAt = "${day}T08:00:00Z", distanceM = km * 1000, durationS = 1800, maxSpeedMps = km)

    // Wednesday 4 Nov 2026
    private val today = LocalDate.of(2026, 11, 4)

    @Test fun lifetimeMonthStreakAndWeek() {
        val rides = listOf(
            ride("a", "2026-11-04", 10.0),
            ride("b", "2026-11-03", 20.0),
            ride("c", "2026-11-02", 5.0),
            ride("d", "2026-10-30", 15.0),
            ride("e", "2026-10-01", 50.0),
            ride("f", "2026-11-04", 99.0, status = "active"),
        )
        val s = DriveStats.of(rides, today, ZoneOffset.UTC)
        assertEquals(5, s.drives)
        assertEquals(100_000.0, s.lifetimeM, 0.1)
        assertEquals(35_000.0, s.monthM, 0.1)
        assertEquals(-46, s.monthChangePct) // 35 vs 65
        assertEquals(3, s.streakDays)
        assertEquals(listOf(5000.0, 20000.0, 10000.0, 0.0, 0.0, 0.0, 0.0), s.week)
        assertEquals(2, s.todayIndex)
        assertEquals(50_000.0, s.longestM, 0.1)
        assertEquals("a", s.last?.id)
    }

    @Test fun streakSurvivesUntilTonightAndNothingIsEmpty() {
        val s = DriveStats.of(listOf(ride("y", "2026-11-03", 1.0), ride("z", "2026-11-02", 1.0)), today, ZoneOffset.UTC)
        assertEquals(2, s.streakDays)
        assertNull(s.monthChangePct)
        val none = DriveStats.of(emptyList(), today, ZoneOffset.UTC)
        assertEquals(0, none.drives)
        assertEquals(0, none.streakDays)
        assertNull(none.last)
    }
}
