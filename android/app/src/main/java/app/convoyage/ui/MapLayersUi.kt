package app.convoyage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.convoyage.api.PsEvent
import app.convoyage.api.PsSpot
import app.convoyage.data.Bbox
import app.convoyage.design.Cv
import app.convoyage.design.CvChipToggle
import app.convoyage.design.CvIconButton
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.Eyebrow
import app.convoyage.design.PinKind
import app.convoyage.design.Space
import app.convoyage.design.cutPanel
import app.convoyage.map.MapPinData

/**
 * PhotoScout spots and events on a map, shared by the Drive (nav) and convoy maps: the pins, what a tap on one opens,
 * the rail button and the sheets. Use [pins], [tappable] and [onPinTap] on the ConvoyMap, [RailButton] in the rail,
 * and [Sheets] last in the screen's Box.
 */
class MapLayersUi internal constructor(
    private val viewport: Bbox?,
    val pins: List<MapPinData>,
    val tappable: Set<PinKind>,
    val onPinTap: (MapPinData) -> Unit,
    /** The server has PhotoScout: the rail button is shown. */
    val offered: Boolean,
    private val anyOn: Boolean,
    private val openPanel: () -> Unit,
    private val sheets: @Composable (Dp) -> Unit,
) {
    @Composable
    fun RailButton() {
        if (!offered) return
        val c = Cv.colors
        if (anyOn) CvIconButton(CvIcons.Layers, "Map layers: photo spots and events", openPanel, tint = c.signal, edge = c.signal)
        else CvIconButton(CvIcons.Layers, "Map layers: photo spots and events", openPanel)
    }

    @Composable
    fun Sheets(bottomInset: Dp = 0.dp) = sheets(bottomInset)

    /**
     * Layers are on but nothing they loaded is in view (the map follows you at street level): say what is nearby and
     * offer to show it. [onShow] gets the points to frame.
     */
    @Composable
    fun Hint(modifier: Modifier = Modifier, onShow: (List<org.maplibre.android.geometry.LatLng>) -> Unit) {
        val box = viewport ?: return
        val pins = pins.filter { it.kind == PinKind.Spot || it.kind == PinKind.Event }
        if (pins.isEmpty() || pins.any { box.contains(it.lat, it.lng) }) return
        val c = Cv.colors
        val spots = pins.count { it.kind == PinKind.Spot }
        val events = pins.count { it.kind == PinKind.Event }
        val what = listOfNotNull(
            spots.takeIf { it > 0 }?.let { "$it SPOT${if (it == 1) "" else "S"}" },
            events.takeIf { it > 0 }?.let { "$it EVENT${if (it == 1) "" else "S"}" },
        ).joinToString(" · ")
        androidx.compose.foundation.layout.Box(
            modifier
                .cutPanel(c.surface100, c.signal, app.convoyage.design.CutSShape)
                .clickableNoRipple { onShow(pins.map { org.maplibre.android.geometry.LatLng(it.lat, it.lng) }) }
                .padding(horizontal = Space.s3, vertical = Space.s2),
        ) {
            CvText("$what NEARBY · SHOW", Cv.type.label, c.signal)
        }
    }
}

@Composable
fun rememberMapLayers(viewport: Bbox?, onNavigate: (app.convoyage.nav.Place) -> Unit, onJoinCruise: ((PsEvent) -> Unit)?): MapLayersUi {
    val container = LocalContainer.current
    val session = container.session
    val layers by container.layers.state.collectAsStateWithLifecycle()
    val spotsOn by session.layerSpots.collectAsStateWithLifecycle()
    val eventsOn by session.layerEvents.collectAsStateWithLifecycle()
    var openSpot by remember { mutableStateOf<PsSpot?>(null) }
    var openEvent by remember { mutableStateOf<PsEvent?>(null) }
    var panel by remember { mutableStateOf(false) }
    var connect by remember { mutableStateOf(false) }
    val trafficOn by session.layerTraffic.collectAsStateWithLifecycle()
    val area by container.area.state.collectAsStateWithLifecycle()
    var openItem by remember { mutableStateOf<app.convoyage.api.ConditionItem?>(null) }
    LaunchedEffect(viewport, trafficOn) { viewport?.let { container.area.onViewport(it, trafficOn) } }

    LaunchedEffect(Unit) { container.layers.refreshStatus() }
    LaunchedEffect(viewport, spotsOn, eventsOn, layers.available) {
        viewport?.let { container.layers.onViewport(it, spotsOn && layers.available, eventsOn && layers.available) }
    }
    val showSpots = spotsOn && layers.available
    val showEvents = eventsOn && layers.available
    val pins = remember(layers, showSpots, showEvents, area, trafficOn) {
        (if (trafficOn) areaPins(area.items) else emptyList()) +
        (if (showSpots) layers.spots.map { MapPinData("spot-${it.id}", PinKind.Spot, "", it.lat, it.lng) } else emptyList()) +
            (if (showEvents) layers.events.map { MapPinData("event-${it.id}", PinKind.Event, "", it.lat!!, it.lng!!, tag = Fmt.whenLine(it.startTime).uppercase().ifBlank { null }) } else emptyList())
    }
    return MapLayersUi(
        viewport = viewport,
        pins = pins,
        tappable = setOf(PinKind.Spot, PinKind.Event, PinKind.Police, PinKind.Hazard),
        onPinTap = { pin ->
            if (pin.id.startsWith(AREA_PREFIX)) openItem = area.items.firstOrNull { AREA_PREFIX + it.id == pin.id }
            else when (pin.kind) {
                PinKind.Spot -> openSpot = layers.spots.firstOrNull { "spot-${it.id}" == pin.id }
                PinKind.Event -> openEvent = layers.events.firstOrNull { "event-${it.id}" == pin.id }
                else -> Unit
            }
        },
        offered = true,
        anyOn = showSpots || showEvents || trafficOn,
        openPanel = { panel = true },
        sheets = { inset ->
            if (panel) LayersPanel(
                trafficOn, area, layers.configured, layers.linked, spotsOn, eventsOn, layers.error,
                onConnect = { panel = false; connect = true }, onDismiss = { panel = false }, bottomInset = inset,
            )
            if (connect) ConnectPhotoScoutSheet(onDismiss = { connect = false }, bottomInset = inset)
            openItem?.let { i -> ConditionSheet(i, onDismiss = { openItem = null }, bottomInset = inset) }
            openSpot?.let { s -> SpotSheet(s, onNavigate = { p -> openSpot = null; onNavigate(p) }, onDismiss = { openSpot = null }, bottomInset = inset) }
            openEvent?.let { e ->
                EventSheet(
                    e, onNavigate = { p -> openEvent = null; onNavigate(p) },
                    onJoinCruise = onJoinCruise?.let { join -> { openEvent = null; join(e) } },
                    onDismiss = { openEvent = null }, bottomInset = inset,
                )
            }
        },
    )
}

/** The layer switches: police and traffic always, PhotoScout's once linked (or a way to link it). */
@Composable
private fun LayersPanel(
    trafficOn: Boolean, area: app.convoyage.data.AreaState, psConfigured: Boolean, psLinked: Boolean,
    spotsOn: Boolean, eventsOn: Boolean, error: String?, onConnect: () -> Unit, onDismiss: () -> Unit, bottomInset: Dp,
) {
    val c = Cv.colors
    val session = LocalContainer.current.session
    LayerScrim(onDismiss, bottomInset) {
        Eyebrow("MAP LAYERS")
        val from = if (area.waze) "Waze and Live Traffic NSW" else "Live Traffic NSW"
        LayerRow("Police and traffic", "Police, speed cameras, crashes, closures, roadworks and jams around you, from $from. Tap one for details.", trafficOn) { session.setLayerTraffic(!trafficOn) }
        if (trafficOn && area.scanning) Eyebrow("WAZE IS SCANNING THIS AREA", color = c.inkMuted)
        if (psLinked) {
            LayerRow("Photo spots", "PhotoScout places to shoot, with their best times. Tap one for its photo and directions.", spotsOn) { session.setLayerSpots(!spotsOn) }
            LayerRow("Events", "Meets and cruises coming up nearby. Tap one to RSVP, drive there or join its convoy.", eventsOn) { session.setLayerEvents(!eventsOn) }
            error?.let { Eyebrow(it.uppercase(), color = c.hazard) }
        } else if (psConfigured) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = Space.s3)) {
                    CvText("Photo spots and events", Cv.type.bodyStrong, c.ink)
                    CvText("From PhotoScout: link your account to show them.", Cv.type.bodyS, c.inkMuted)
                }
                CvChipToggle("Link", false, onConnect)
            }
        }
    }
}

private const val AREA_PREFIX = "area-"

/** Police and cameras as police pins; crashes, closures, hazards, roadworks and jams as hazard pins, with a short word. */
internal fun areaPins(items: List<app.convoyage.api.ConditionItem>): List<MapPinData> = items.map { i ->
    when (i.kind) {
        "police" -> MapPinData(AREA_PREFIX + i.id, PinKind.Police, "POL", i.lat, i.lng)
        "camera" -> MapPinData(AREA_PREFIX + i.id, PinKind.Police, "CAM", i.lat, i.lng)
        else -> MapPinData(AREA_PREFIX + i.id, PinKind.Hazard, areaWord(i.kind), i.lat, i.lng)
    }
}

private fun areaWord(kind: String) = when (kind) {
    "crash" -> "!"; "closure" -> "X"; "jam" -> "JAM"; "roadwork" -> "WRK"; "flood" -> "H2O"; "fire" -> "FIRE"; "event" -> "EVT"; else -> "!"
}

/** A police report, camera or traffic item tapped on the map. */
@Composable
private fun ConditionSheet(item: app.convoyage.api.ConditionItem, onDismiss: () -> Unit, bottomInset: Dp) {
    val c = Cv.colors
    LayerScrim(onDismiss, bottomInset) {
        val source = when (item.source) { "waze" -> "WAZE"; "tfnsw" -> "LIVE TRAFFIC NSW"; else -> "CONVOYAGE" }
        val age = item.reportedAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }?.let { at ->
            val min = java.time.Duration.between(at, java.time.Instant.now()).toMinutes().coerceAtLeast(0)
            when { min < 1 -> "JUST NOW"; min < 60 -> "$min MIN AGO"; min < 48 * 60 -> "${min / 60} H AGO"; else -> "${min / 1440} DAYS AGO" }
        }
        Eyebrow(listOfNotNull(source, age).joinToString(" · "), color = if (item.kind == "police" || item.kind == "camera") c.police else c.hazard)
        CvText(item.title, Cv.type.title, c.ink)
        if (item.detail.isNotBlank()) CvText(item.detail, Cv.type.body, c.inkMuted)
    }
}

@Composable
private fun LayerRow(title: String, sub: String, on: Boolean, onToggle: () -> Unit) {
    val c = Cv.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Space.s3)) {
            CvText(title, Cv.type.bodyStrong, c.ink)
            CvText(sub, Cv.type.bodyS, c.inkMuted)
        }
        CvChipToggle(if (on) "On" else "Off", on, onToggle)
    }
}
