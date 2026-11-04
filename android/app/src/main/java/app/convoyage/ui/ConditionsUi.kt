package app.convoyage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.convoyage.api.ApiResult
import app.convoyage.api.ConditionItem
import app.convoyage.api.RouteConditions
import app.convoyage.data.LatLngD
import app.convoyage.data.Polyline
import app.convoyage.design.Cv
import app.convoyage.design.CvCard
import app.convoyage.design.CvIconButton
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.Eyebrow
import app.convoyage.map.MapPinData
import app.convoyage.design.PinKind
import app.convoyage.design.Space
import app.convoyage.map.MapNightStyle
import kotlinx.coroutines.delay

/** Route conditions as they load: the saved snapshot first (instant), then a fresh check. */
class ConditionsUi {
    var value by mutableStateOf<RouteConditions?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** Bump to ask for a fresh check. */
    var refreshTick by mutableIntStateOf(0)
}

/**
 * Loads a saved route's conditions: the last snapshot at once, then the live one. When the server says road quality
 * is still loading (Overpass is slow), asks again a little later, up to three times.
 */
@Composable
fun rememberRouteConditions(routeId: String): ConditionsUi {
    val container = LocalContainer.current
    val ui = remember(routeId) { ConditionsUi() }
    LaunchedEffect(routeId) {
        (container.api.routeConditions(routeId, cachedOnly = true) as? ApiResult.Ok)?.let { ui.value = it.value }
    }
    LaunchedEffect(routeId, ui.refreshTick) {
        ui.loading = true
        ui.error = null
        var tries = 0
        while (true) {
            when (val r = container.api.routeConditions(routeId, refresh = ui.refreshTick > 0 && tries == 0)) {
                is ApiResult.Ok -> ui.value = r.value
                is ApiResult.Err -> { ui.error = r.message; break }
            }
            if (ui.value?.sources?.roads != "pending" || ++tries > 3) break
            ui.loading = false
            delay(30_000)
            ui.loading = true
        }
        ui.loading = false
    }
    return ui
}

/** The same for a route that is not saved (the builder): checked when [geometry] settles. */
@Composable
fun rememberLineConditions(geometry: String?, durationS: Double?): ConditionsUi {
    val container = LocalContainer.current
    val ui = remember { ConditionsUi() }
    LaunchedEffect(geometry, ui.refreshTick) {
        if (geometry == null) { ui.value = null; return@LaunchedEffect }
        delay(1500) // let the person stop placing points
        ui.loading = true
        ui.error = null
        when (val r = container.api.conditions(geometry, durationS)) {
            is ApiResult.Ok -> ui.value = r.value
            is ApiResult.Err -> ui.error = r.message
        }
        ui.loading = false
    }
    return ui
}

/** The route coloured by road quality, when that is known. */
fun roadSegments(c: RouteConditions?): List<Pair<List<LatLngD>, String>> =
    c?.roads?.runs.orEmpty().mapNotNull { run ->
        val pts = runCatching { Polyline.decode(run.line, 6) }.getOrNull() ?: return@mapNotNull null
        if (pts.size < 2) null else pts to (MapNightStyle.GRADE_COLORS[run.grade] ?: MapNightStyle.ROUTE_COLOR)
    }

/** Police, cameras and hazards on the route as map pins. */
fun conditionPins(c: RouteConditions?): List<MapPinData> {
    if (c == null) return emptyList()
    val police = c.police.map { MapPinData("c-${it.id}", PinKind.Police, if (it.kind == "camera") "CAM" else "POL", it.lat, it.lng) }
    val other = (c.hazards + c.traffic.items.filter { it.kind == "crash" || it.kind == "closure" })
        .map { MapPinData("c-${it.id}", PinKind.Hazard, "!", it.lat, it.lng) }
    return police + other
}

@Composable
private fun levelColor(level: String): Color = when (level) {
    "clear" -> Cv.colors.go
    "light" -> Cv.colors.signal
    "moderate" -> Cv.colors.hazard
    else -> Cv.colors.pulse
}

private fun levelWord(level: String) = when (level) {
    "clear" -> "CLEAR"; "light" -> "LIGHT"; "moderate" -> "BUSY"; else -> "HEAVY"
}

private fun conditionIcon(kind: String) = when (kind) {
    "police", "camera" -> CvIcons.Alert
    "closure", "crash" -> CvIcons.Stop
    "roadwork" -> CvIcons.Flag
    else -> CvIcons.Alert
}

/**
 * Traffic, police, weather and road quality along a route, at a glance, with what was found listed by distance.
 * Sources that are not set up or did not answer are said in one line at the bottom.
 */
@Composable
fun ConditionsCard(ui: ConditionsUi, modifier: Modifier = Modifier) {
    val c = Cv.colors
    val v = ui.value
    CvCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("CONDITIONS", Modifier.weight(1f))
                CvText(
                    when {
                        ui.loading && v == null -> "Checking..."
                        ui.loading -> "Updating..."
                        v != null -> "CHECKED " + Fmt.timeAgo(v.computedAt)
                        else -> ""
                    },
                    Cv.type.label, c.inkMuted,
                )
                Spacer(Modifier.width(Space.s2))
                CvIconButton(CvIcons.Route, "Check again", { ui.refreshTick++ }, size = 36.dp)
            }
            if (v == null) {
                CvText(ui.error ?: "Looking up traffic, police, weather and road surfaces along the route.", Cv.type.bodyS, if (ui.error != null) c.pulse else c.inkMuted)
                return@Column
            }

            // Traffic
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                StatusTag(levelWord(v.traffic.level), levelColor(v.traffic.level))
                CvText(v.traffic.summary, Cv.type.bodyS, c.ink, Modifier.weight(1f))
            }
            // Police
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                StatusTag(if (v.police.isEmpty()) "NONE" else "${v.police.size} POLICE", if (v.police.isEmpty()) c.go else c.police)
                CvText(
                    if (v.police.isEmpty()) "No police or cameras reported on the route"
                    else v.police.take(3).joinToString(" · ") { "${it.title} at km ${fmtKm(it.km)}" },
                    Cv.type.bodyS, c.ink, Modifier.weight(1f),
                )
            }
            // Weather
            if (v.weather.points.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    StatusTag(if (v.weather.worst == 0) "FINE" else if (v.weather.worst == 1) "DAMP" else "CARE", if (v.weather.worst == 0) c.go else if (v.weather.worst == 1) c.signal else c.hazard)
                    CvText(v.weather.summary, Cv.type.bodyS, c.ink, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    v.weather.points.take(5).forEach { p ->
                        Column(Modifier.weight(1f)) {
                            CvText("KM ${fmtKm(p.km)}", Cv.type.label, c.inkMuted, maxLines = 1)
                            CvText(p.tempC?.let { "${Math.round(it)}°" } ?: "–", Cv.type.telemetry, c.ink, maxLines = 1)
                            CvText(p.label, Cv.type.label, c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            // Road quality
            val roads = v.roads
            if (roads != null) {
                val total = (roads.km.good + roads.km.fair + roads.km.poor + roads.km.unknown).coerceAtLeast(0.01)
                Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow("ROAD SURFACE", Modifier.weight(1f))
                        CvText(
                            if (roads.unpavedKm > 0) "${fmtKm(roads.unpavedKm)} km unsealed" else "Sealed all the way" + if (roads.km.unknown / total > 0.3) " (as far as we know)" else "",
                            Cv.type.label, if (roads.unpavedKm > 0) c.hazard else c.inkMuted,
                        )
                    }
                    Row(Modifier.fillMaxWidth().height(8.dp)) {
                        listOf(roads.km.good to c.go, roads.km.fair to c.hazard, roads.km.poor to c.pulse, roads.km.unknown to c.lineStrong).forEach { (km, col) ->
                            if (km > 0) Box(Modifier.weight((km / total).toFloat()).height(8.dp).background(col))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        Legend("Good", roads.km.good, c.go); Legend("Fair", roads.km.fair, c.hazard); Legend("Rough", roads.km.poor, c.pulse)
                        if (roads.km.unknown > 0) Legend("Unknown", roads.km.unknown, c.inkMuted)
                    }
                    roads.worst.firstOrNull()?.let { w ->
                        CvText("Roughest: ${w.name.ifBlank { "unnamed road" }}, ${fmtKm(w.km)} km" + (if (w.surface.isNotBlank()) " of ${w.surface.replace('_', ' ')}" else ""), Cv.type.bodyS, c.inkMuted)
                    }
                }
            } else if (v.sources.roads == "pending") {
                CvText("Road surfaces are still loading.", Cv.type.bodyS, c.inkMuted)
            }

            // What was found, by distance along the route
            val found = (v.traffic.items + v.police + v.hazards).distinctBy { it.id }.sortedBy { it.km }
            if (found.isNotEmpty()) {
                Eyebrow("ON THE ROUTE")
                found.take(12).forEach { FoundItem(it) }
                if (found.size > 12) CvText("and ${found.size - 12} more", Cv.type.bodyS, c.inkMuted)
            }

            val missing = buildList {
                if (v.sources.tfnsw == "error") add("Live Traffic NSW")
                if (v.sources.waze == "error") add("Waze")
                if (v.sources.weather == "error") add("weather")
                if (v.sources.roads == "error") add("road surfaces")
            }
            if (missing.isNotEmpty()) CvText("Not answering right now: ${missing.joinToString(", ")}.", Cv.type.bodyS, c.inkMuted)
            CvText(
                "From " + listOfNotNull(
                    "Live Traffic NSW".takeIf { v.sources.tfnsw == "ok" || v.sources.tfnsw == "partial" },
                    "Waze".takeIf { v.sources.waze == "ok" || v.sources.waze == "partial" },
                    "Convoyage reports", "Open-Meteo".takeIf { v.weather.points.isNotEmpty() }, "OpenStreetMap".takeIf { roads != null },
                ).joinToString(", ") + ".",
                Cv.type.label, c.inkMuted,
            )
        }
    }
}

private fun fmtKm(km: Double) = if (km < 10) "%.1f".format(java.util.Locale.ROOT, km) else Math.round(km).toString()

@Composable
private fun StatusTag(text: String, color: Color) {
    Box(Modifier.width(84.dp).background(color.copy(alpha = 0.16f)).padding(horizontal = Space.s2, vertical = Space.s1), contentAlignment = Alignment.Center) {
        CvText(text, Cv.type.label, color, maxLines = 1)
    }
}

@Composable
private fun Legend(label: String, km: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
        Box(Modifier.size(8.dp).background(color))
        CvText("$label ${fmtKm(km)}", Cv.type.label, Cv.colors.inkMuted)
    }
}

@Composable
private fun FoundItem(i: ConditionItem) {
    val c = Cv.colors
    val tint = when (i.kind) {
        "police", "camera" -> c.police
        "closure", "crash" -> c.pulse
        "jam", "roadwork", "event" -> c.hazard
        else -> c.hazard
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        CvText("KM ${fmtKm(i.km)}", Cv.type.telemetry, c.inkMuted, Modifier.width(64.dp), maxLines = 1)
        app.convoyage.design.CvIcon(conditionIcon(i.kind), tint, size = 16.dp)
        Column(Modifier.weight(1f)) {
            CvText(i.title, Cv.type.bodyStrong, c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                i.detail.takeIf { it.isNotBlank() },
                i.reportedAt?.let { "reported " + Fmt.timeAgo(it).lowercase() },
                when (i.source) { "tfnsw" -> "Live Traffic NSW"; "waze" -> "Waze"; else -> "Convoyage" },
            ).joinToString(" · ")
            CvText(sub, Cv.type.bodyS, c.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
