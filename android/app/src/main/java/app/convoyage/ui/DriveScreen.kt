package app.convoyage.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.convoyage.data.SavedSlot
import app.convoyage.design.CutMShape
import app.convoyage.design.CutSShape
import app.convoyage.design.Cv
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvIcon
import app.convoyage.design.CvIconButton
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.Eyebrow
import app.convoyage.design.Space
import app.convoyage.design.cutPanel
import app.convoyage.nav.NavPhase
import app.convoyage.nav.Place
import androidx.compose.ui.graphics.vector.ImageVector

/** How much the floating tab bar covers above the navigation bar, on the Drive tab. */
val DriveTabBarChrome = 92.dp

/** The Drive dock at rest: Start / Join. (Open Cruise is a toggle on the map rail.) */
val DriveDockPeek = 92.dp

/** Open Cruise as the dock shows it: [wanted] is the setting, [live] what the server confirmed. */
data class CruiseUi(
    val wanted: Boolean,
    val live: Boolean,
    val drivers: List<app.convoyage.protocol.NearbyDriver>,
    val convoys: List<app.convoyage.protocol.NearbyConvoy>,
    val invited: Set<String>,
) {
    val summary: String get() = when {
        !wanted -> "See drivers and open convoys nearby"
        !live -> "Connecting..."
        drivers.isEmpty() && convoys.isEmpty() -> "Nobody else cruising within 5 km yet"
        else -> listOfNotNull(
            drivers.size.takeIf { it > 0 }?.let { "$it driver${if (it == 1) "" else "s"}" },
            convoys.size.takeIf { it > 0 }?.let { "$it convoy${if (it == 1) "" else "s"}" },
        ).joinToString(" and ") + " within 5 km"
    }
}

/** Map pins for Open Cruise: drivers by initials, open convoys tagged with their name. */
fun nearbyPins(drivers: List<app.convoyage.protocol.NearbyDriver>, convoys: List<app.convoyage.protocol.NearbyConvoy>): List<app.convoyage.map.MapPinData> =
    drivers.map { d ->
        app.convoyage.map.MapPinData("oc-" + d.id, app.convoyage.design.PinKind.Offline, app.convoyage.data.Roster.initials(app.convoyage.data.Names.friendly(d.username)), d.lat, d.lng, vehicle = d.vehicle)
    } + convoys.map { cv ->
        app.convoyage.map.MapPinData("ocv-" + cv.code, app.convoyage.design.PinKind.Start, cv.members.toString(), cv.lat, cv.lng, tag = "${cv.name.take(14).uppercase()} · ${cv.members}")
    }

/** "Kai invited you to Kai's open convoy": Join or not, until it expires. */
@Composable
fun CruiseInviteBanner() {
    val container = LocalContainer.current
    val state by container.convoy.state.collectAsStateWithLifecycle()
    val invite = state.invite ?: return
    var now by androidx.compose.runtime.remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(invite) {
        while (System.currentTimeMillis() < invite.expiresAt) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() }
        container.convoy.dismissInvite()
    }
    val left = ((invite.expiresAt - now) / 1000).coerceAtLeast(0)
    app.convoyage.design.Banner(
        app.convoyage.design.BannerTone.Hazard,
        "${app.convoyage.data.Names.friendly(invite.fromName)} invited you to cruise",
        sub = "${invite.name} · ${left}s · tap Join",
        icon = CvIcons.Convoy,
        actionLabel = "Join",
        onAction = { container.convoy.joinOpen(invite.code) },
    )
}

/**
 * The centre tab and the screen the app opens on. Navigation is the default state ("Where to?" at the top, a dock
 * below). Once you are in a convoy, or choose Start / Join, the same tab becomes the convoy view, so turn-by-turn and
 * the convoy share one place.
 */
@Composable
fun DriveScreen(
    onReport: () -> Unit,
    onRide: () -> Unit,
    onOpenRoutes: () -> Unit,
    onBuildRoute: () -> Unit,
) {
    val container = LocalContainer.current
    val convoy by container.convoy.state.collectAsStateWithLifecycle()
    val pendingJoin by container.pendingJoin.collectAsStateWithLifecycle()
    // null = navigation; "start" / "join" = the convoy pre-start sheet was asked for.
    var mode by rememberSaveable { mutableStateOf<String?>(null) }
    val inConvoy = convoy.active != null
    var wasInConvoy by rememberSaveable { mutableStateOf(inConvoy) }
    LaunchedEffect(inConvoy) {
        // Leaving (or being removed from) a convoy brings navigation back.
        if (wasInConvoy && !inConvoy) mode = null
        wasInConvoy = inConvoy
    }
    // A join link opens the convoy view, which does the joining.
    LaunchedEffect(pendingJoin) { if (pendingJoin != null && mode == null) mode = "start" }

    CompositionLocalProvider(LocalBottomChrome provides DriveTabBarChrome) {
        if (inConvoy || mode != null) {
            key(mode) {
                ConvoyScreen(
                    startWithJoin = mode == "join",
                    onRide = onRide,
                    onReport = onReport,
                    onClose = { mode = null },
                    onBuildRoute = onBuildRoute,
                    openPreStart = mode != null && pendingJoin == null,
                )
            }
        } else {
            NavScreen(
                onBack = {},
                onReport = onReport,
                drive = DriveHooks(
                    onStartConvoy = { mode = "start" },
                    onJoinConvoy = { mode = "join" },
                    onOpenRoutes = onOpenRoutes,
                    onBuildRoute = onBuildRoute,
                ),
            )
        }
    }
}

/** Shortcuts under "Where to?": saved routes, Home, Work and the last places. */
@Composable
internal fun DriveChips(
    saved: Map<SavedSlot, Place>,
    recent: List<Place>,
    onPick: (Place) -> Unit,
    onRoutes: () -> Unit,
    onUnset: (SavedSlot) -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        ShortcutChip(CvIcons.Route, "Saved routes", onRoutes)
        SavedSlot.entries.forEach { slot ->
            val p = saved[slot]
            ShortcutChip(slotIcon(slot), slot.label, { if (p != null) onPick(p) else onUnset(slot) }, dim = p == null)
        }
        recent.filterNot { r -> saved.values.any { it.lat == r.lat && it.lng == r.lng } }.take(3).forEach { r ->
            ShortcutChip(null, r.name, { onPick(r) })
        }
    }
}

private fun slotIcon(slot: SavedSlot): ImageVector = when (slot) { SavedSlot.Home -> CvIcons.Home; SavedSlot.Work -> CvIcons.Work }

@Composable
private fun ShortcutChip(icon: ImageVector?, text: String, onClick: () -> Unit, dim: Boolean = false) {
    val c = Cv.colors
    Row(
        Modifier.cutPanel(c.glass, c.lineStrong, CutSShape).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s3, vertical = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) CvIcon(icon, if (dim) c.inkMuted else c.signal, size = 16.dp)
        CvText(text, Cv.type.bodyStrong, if (dim) c.inkMuted else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The Drive dock. Peek: start or join a convoy. Pulled up: Konvoi's Track Drive / Route Builder pair, saved places
 * and recent destinations.
 */
@Composable
internal fun ColumnScope.DriveDock(
    expanded: Boolean,
    drive: DriveHooks,
    saved: Map<SavedSlot, Place>,
    recent: List<Place>,
    recording: Boolean,
    onRecord: () -> Unit,
    onPick: (Place) -> Unit,
    onForget: (Place) -> Unit,
    cruise: CruiseUi,
    onCruise: (Boolean) -> Unit,
    onJoinOpen: (String) -> Unit,
    onInvite: (String) -> Unit,
) {
    val c = Cv.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        CvButton("Start convoy", drive.onStartConvoy, Modifier.weight(1f), icon = CvIcons.Convoy, compact = true)
        CvButton("Join with code", drive.onJoinConvoy, Modifier.weight(1f), CvButtonKind.Ghost, compact = true)
    }
    if (!expanded) return
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = Space.s3, bottom = Space.s3),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        // Open Cruise: switched on the map rail; here, what it found and a way to turn it off.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Column(Modifier.weight(1f)) {
                Eyebrow(if (cruise.wanted) "OPEN CRUISE · ON" else "OPEN CRUISE · OFF", color = if (cruise.wanted) c.go else c.inkMuted)
                CvText(cruise.summary, Cv.type.bodyS, c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            app.convoyage.design.CvChipToggle(if (cruise.wanted) "On" else "Off", cruise.wanted, { onCruise(!cruise.wanted) })
        }
        if (cruise.wanted && cruise.convoys.isNotEmpty()) {
            Eyebrow("OPEN CONVOYS NEARBY")
            cruise.convoys.forEach { cv ->
                NearbyRow(
                    CvIcons.Convoy, cv.name,
                    "${cv.members} IN · ${app.convoyage.data.Geo.formatDistance(cv.distanceM).uppercase()} · ${cv.destination?.uppercase() ?: "JUST CRUISING"}",
                    action = "Join", onAction = { onJoinOpen(cv.code) },
                )
            }
        }
        if (cruise.wanted && cruise.drivers.isNotEmpty()) {
            Eyebrow("DRIVERS CRUISING NEARBY", Modifier.padding(top = Space.s2))
            cruise.drivers.forEach { d ->
                val sent = d.id in cruise.invited
                NearbyRow(
                    CvIcons.Car, app.convoyage.data.Names.friendly(d.username),
                    app.convoyage.data.Geo.formatDistance(d.distanceM).uppercase() + if (d.canInvite) "" else " · GET WITHIN 500 M TO INVITE",
                    action = if (!d.canInvite) null else if (sent) "Sent" else "Invite",
                    onAction = { if (!sent) onInvite(d.id) }, ghost = true,
                )
            }
        }
        Eyebrow("DRIVE")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            CvButton(if (recording) "Recording" else "Track drive", onRecord, Modifier.weight(1f), if (recording) CvButtonKind.Alert else CvButtonKind.Ghost, icon = CvIcons.Rec, compact = true)
            CvButton("Route builder", drive.onBuildRoute, Modifier.weight(1f), CvButtonKind.Ghost, icon = CvIcons.Route, compact = true)
        }
        Eyebrow("SAVED PLACES", Modifier.padding(top = Space.s2))
        SavedSlot.entries.forEach { slot ->
            val p = saved[slot]
            PlaceRow(slotIcon(slot), slot.label, p?.name ?: "Not set: search a place, then Set as ${slot.label}", enabled = p != null, onClick = { p?.let(onPick) })
        }
        PlaceRow(CvIcons.Route, "Saved routes", "Drive one solo or as a convoy", onClick = drive.onOpenRoutes)
        if (recent.isNotEmpty()) {
            Eyebrow("RECENT", Modifier.padding(top = Space.s2))
            recent.forEach { r -> PlaceRow(CvIcons.Clock, r.name, r.detail, onClick = { onPick(r) }, onRemove = { onForget(r) }) }
        }
    }
}

@Composable
private fun PlaceRow(icon: ImageVector, title: String, sub: String, enabled: Boolean = true, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    val c = Cv.colors
    Row(
        Modifier.fillMaxWidth().cutPanel(c.surface200, c.line, CutMShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s3, vertical = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically,
    ) {
        CvIcon(icon, if (enabled) c.signal else c.inkMuted)
        Column(Modifier.weight(1f)) {
            CvText(title, Cv.type.bodyStrong, c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) CvText(sub, Cv.type.bodyS, c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRemove != null) CvIconButton(CvIcons.Close, "Remove $title from recent", onRemove, tint = c.inkMuted, size = 36.dp)
    }
}

@Composable
private fun NearbyRow(icon: ImageVector, title: String, sub: String, action: String?, onAction: () -> Unit, ghost: Boolean = false) {
    val c = Cv.colors
    Row(
        Modifier.fillMaxWidth().cutPanel(c.surface200, c.line, CutMShape).padding(horizontal = Space.s3, vertical = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically,
    ) {
        CvIcon(icon, c.go)
        Column(Modifier.weight(1f)) {
            CvText(title, Cv.type.bodyStrong, c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CvText(sub, Cv.type.telemetry, c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) CvButton(action, onAction, kind = if (ghost) CvButtonKind.Ghost else CvButtonKind.Primary, compact = true)
    }
}

/** While navigating: record the drive, or Pause / Photo / Stop once it is recording (Konvoi's bottom row). */
@Composable
internal fun DriveRecordBar(rec: app.convoyage.data.RecorderState, onRecord: () -> Unit, onPhoto: () -> Unit) {
    val container = LocalContainer.current
    val openRecap = LocalOpenRecap.current
    val c = Cv.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
        if (!rec.isActive) {
            CvButton("Track drive", onRecord, Modifier.weight(1f), CvButtonKind.Ghost, icon = CvIcons.Rec, compact = true)
            CvButton("Photo", onPhoto, Modifier.weight(1f), CvButtonKind.Ghost, icon = CvIcons.Camera, compact = true)
        } else {
            CvText((if (rec.isPaused) "II " else "REC ") + Fmt.clock(rec.elapsedS), Cv.type.telemetry, if (rec.isPaused) c.hazard else c.pulse)
            if (rec.isPaused) CvButton("Resume", { container.recorder.resume() }, Modifier.weight(1f), icon = CvIcons.Play, compact = true)
            else CvButton("Pause", { container.recorder.pause() }, Modifier.weight(1f), CvButtonKind.Ghost, icon = CvIcons.Pause, compact = true)
            CvIconButton(CvIcons.Camera, "Take a photo", onPhoto, tint = c.signal, edge = c.signal)
            CvButton("Stop", { container.recorder.stop(); openRecap() }, Modifier.weight(1f), CvButtonKind.Alert, icon = CvIcons.Stop, compact = true)
        }
    }
}

/** Current speed, Konvoi-style, bottom-left while navigating. */
@Composable
internal fun SpeedReadout(speedMps: Double, modifier: Modifier = Modifier) {
    val c = Cv.colors
    Column(modifier.cutPanel(c.glass, c.lineStrong, CutMShape).padding(horizontal = Space.s3, vertical = Space.s2), horizontalAlignment = Alignment.CenterHorizontally) {
        CvText(Math.round(speedMps * 3.6).toString(), Cv.type.telemetryL, c.ink, maxLines = 1)
        Eyebrow("KM/H")
    }
}

/** True while turn-by-turn has a route on screen (used by the convoy view to show the maneuver card). */
internal fun NavPhase.guiding() = this == NavPhase.Active || this == NavPhase.Arrived
