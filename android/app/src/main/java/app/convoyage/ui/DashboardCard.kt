package app.convoyage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.convoyage.data.DriveStats
import app.convoyage.design.CutMShape
import app.convoyage.design.CutSShape
import app.convoyage.design.Cv
import app.convoyage.design.CvCard
import app.convoyage.design.CvText
import app.convoyage.design.Eyebrow
import app.convoyage.design.Readout
import app.convoyage.design.Space

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

/**
 * The dashboard at the top of Home (after Konvoi's): lifetime distance, drives, time, this month against last,
 * the streak, this week by day, and personal bests. Built from the rides list, so it needs no extra endpoint.
 */
@Composable
fun DashboardCard(modifier: Modifier = Modifier, onOpenRide: (String) -> Unit = {}) {
    val container = LocalContainer.current
    val rides by container.rides.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.rides.refresh() }
    val s = remember(rides.rides) { DriveStats.of(rides.rides) }
    val c = Cv.colors

    CvCard(modifier.fillMaxWidth(), edge = c.lineStrong, shape = CutMShape) {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("LIFETIME")
                if (s.streakDays > 0) Eyebrow("STREAK · ${s.streakDays} DAY${if (s.streakDays == 1) "" else "S"}", color = c.go)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CvText(Fmt.km(s.lifetimeM), Cv.type.telemetryL.copy(fontSize = 44.sp, lineHeight = 46.sp), c.signal, maxLines = 1)
                CvText("KM", Cv.type.label, c.inkMuted, Modifier.padding(bottom = 6.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                val (time, unit) = Fmt.hoursOrMinutes(s.lifetimeS)
                Readout("Drives", s.drives.toString(), Modifier.weight(1f), valueStyle = Cv.type.telemetryL.copy(fontSize = 20.sp))
                Readout("Time", time, Modifier.weight(1f), unit = unit, valueStyle = Cv.type.telemetryL.copy(fontSize = 20.sp))
                Readout(
                    "This month", Fmt.km(s.monthM), Modifier.weight(1f), unit = "km",
                    valueStyle = Cv.type.telemetryL.copy(fontSize = 20.sp),
                )
            }
            s.monthChangePct?.let { pct ->
                CvText(
                    (if (pct >= 0) "+$pct%" else "$pct%") + " on last month",
                    Cv.type.bodyS, if (pct >= 0) c.go else c.inkMuted,
                )
            }
            if (s.drives > 0) {
                Eyebrow("THIS WEEK")
                WeekBars(s)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Readout("Longest", Fmt.km(s.longestM), Modifier.weight(1f), unit = "km", valueStyle = Cv.type.telemetryL.copy(fontSize = 18.sp))
                    Readout("Top speed", Fmt.kmh(s.topSpeedMps), Modifier.weight(1f), unit = "km/h", valueStyle = Cv.type.telemetryL.copy(fontSize = 18.sp))
                }
                s.last?.let { r ->
                    CvCard(Modifier.fillMaxWidth(), edge = c.line, shape = CutSShape, onClick = { onOpenRide(r.id) }) {
                        Column(Modifier.padding(Space.s3)) {
                            Eyebrow("LAST DRIVE")
                            CvText(r.title.ifBlank { "Drive" }, Cv.type.bodyStrong, c.ink, maxLines = 1)
                            CvText(
                                "${Fmt.km(r.distanceM ?: 0.0)} km · ${Fmt.clock(r.durationS ?: 0L)}",
                                Cv.type.telemetry, c.inkMuted,
                            )
                        }
                    }
                }
            } else if (rides.loaded) {
                CvText("No drives yet. Track one from the Drive tab and it lands here.", Cv.type.bodyS, c.inkMuted)
            }
        }
    }
}

/** Seven bars, Monday first; today in signal, other days muted, a day with nothing as a thin baseline. */
@Composable
private fun WeekBars(s: DriveStats) {
    val c = Cv.colors
    val top = s.week.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val total = s.week.sum()
    Row(
        Modifier.fillMaxWidth().height(84.dp).semantics { contentDescription = "This week ${Fmt.km(total)} kilometres" },
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalAlignment = Alignment.Bottom,
    ) {
        s.week.forEachIndexed { i, m ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                val frac = (m / top).toFloat().coerceIn(0f, 1f)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(if (m > 0) frac.coerceAtLeast(0.06f) else 0.02f)
                            .background(if (i == s.todayIndex) c.signal else if (m > 0) c.inkMuted else c.line, CutSShape),
                    )
                }
                Spacer(Modifier.height(4.dp))
                CvText(DAY_LETTERS[i], Cv.type.label, if (i == s.todayIndex) c.signal else c.inkMuted)
            }
        }
    }
}
