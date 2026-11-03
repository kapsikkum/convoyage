package app.convoyage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.convoyage.data.AlertKind
import app.convoyage.data.Geo
import app.convoyage.data.Link
import app.convoyage.data.RosterEntry
import app.convoyage.data.Side
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.Cv
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvChipToggle
import app.convoyage.design.CvIconButton
import app.convoyage.design.CvIcons
import app.convoyage.design.CvSheet
import app.convoyage.design.CvText
import app.convoyage.design.CvTextField
import app.convoyage.design.Eyebrow
import app.convoyage.design.MemberState
import app.convoyage.design.PinKind
import app.convoyage.design.PushToTalk
import app.convoyage.design.RosterChip
import app.convoyage.design.SegOption
import app.convoyage.design.Segmented
import app.convoyage.design.Space
import app.convoyage.map.ConvoyMap
import app.convoyage.map.MapPinData
import org.maplibre.android.geometry.LatLng

@Composable
fun ConvoyScreen(startWithJoin: Boolean, onRide: () -> Unit, onReport: () -> Unit, onClose: () -> Unit) {
    val container = LocalContainer.current
    val vm: ConvoyViewModel = viewModel(factory = viewModelFactory { initializer { ConvoyViewModel(container) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    val permission = rememberLocationPermission()
    TrackWhileVisible(permission)

    val c = Cv.colors
    val density = LocalDensity.current
    var sheetHeightPx by remember { mutableStateOf(0) }
    var sheetOpen by rememberSaveable { mutableStateOf(true) }
    val room = ui.room

    val pins = remember(ui.roster, ui.fix) {
        ui.roster.mapNotNull { e ->
            if (e.isMe || e.lat == null || e.lng == null) return@mapNotNull null
            MapPinData(
                id = e.id,
                kind = when (e.state) {
                    MemberState.Lead -> PinKind.Lead
                    MemberState.Stopped -> PinKind.Stopped
                    MemberState.Offline -> PinKind.Offline
                    MemberState.Online -> PinKind.Member
                },
                label = e.initials, lat = e.lat, lng = e.lng, headingDeg = e.headingDeg,
                tag = if (e.state == MemberState.Lead) e.distanceM?.let { Geo.formatDistance(it).uppercase() } else null,
            )
        } + listOfNotNull(
            ui.fix?.let { MapPinData("me", PinKind.Me, "", it.lat, it.lng, it.headingDeg) },
            room?.destination?.let { MapPinData("dest", PinKind.Destination, "", it.lat, it.lng, tag = it.label.take(12).uppercase().ifBlank { null }) },
        )
    }

    Box(Modifier.fillMaxSize()) {
        ConvoyMap(
            modifier = Modifier.fillMaxSize(),
            pins = pins,
            me = ui.fix?.let { LatLng(it.lat, it.lng) },
            route = room?.route?.stops.orEmpty(),
            buildings3d = local.buildings3d,
            follow = local.follow,
            recenterTick = local.recenterTick,
            onUserMoved = vm::userMovedMap,
            bottomPadding = with(density) { sheetHeightPx.toDp() },
        )

        // Top: banners, then the toggle row.
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp).padding(top = Space.s2),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            if (!permission.granted) {
                Banner(
                    BannerTone.Hazard, "Location is off", sub = "Allow location so the convoy can see you.",
                    icon = CvIcons.Locate, actionLabel = "Allow", onAction = permission.request,
                )
            }
            if (ui.link != Link.Online) {
                Banner(
                    if (ui.link == Link.Rejected) BannerTone.Pulse else BannerTone.Hazard,
                    when (ui.link) {
                        Link.Rejected -> "Server refused sign-in"
                        Link.Connecting -> "Connecting to the convoy server"
                        else -> "Offline"
                    },
                    sub = ui.error, icon = CvIcons.Alert,
                )
            } else if (ui.error != null) {
                Banner(BannerTone.Pulse, ui.error!!, icon = CvIcons.Alert, actionLabel = "OK", onAction = vm::dismissError)
            }
            if (ui.removed) Banner(BannerTone.Pulse, "The leader removed you from the convoy", icon = CvIcons.Alert, actionLabel = "OK", onAction = vm::dismissError)
            ui.alert?.let { a ->
                Banner(
                    tone = if (a.kind == AlertKind.Stopped) BannerTone.Pulse else BannerTone.Hazard,
                    title = a.title, sub = a.sub, icon = if (a.kind == AlertKind.Stopped) CvIcons.Stop else CvIcons.Route,
                    actionLabel = if (a.canRegroup) "Regroup" else null,
                    onAction = if (a.canRegroup) vm::findRestStop else null,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                CvChipToggle("3D", local.buildings3d, vm::toggle3d)
                // TODO(layers): SPOTS / EVENTS / WAZE overlays are not in this version.
            }
        }

        // Right rail: recentre + report.
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).padding(bottom = with(density) { sheetHeightPx.toDp() }),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            CvIconButton(CvIcons.Locate, "Recentre", vm::recenter, tint = if (local.follow) c.signal else c.ink)
            CvIconButton(CvIcons.Alert, "Report hazard", onReport, tint = c.hazard, edge = c.hazard)
        }

        if (room != null) {
            PushToTalk(
                state = local.ptt,
                onPress = vm::pttDown, onRelease = vm::pttUp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp).padding(bottom = with(density) { sheetHeightPx.toDp() } + Space.s4),
            )
        }

        CvSheet(
            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { sheetHeightPx = it.height },
            expanded = sheetOpen,
            onToggle = { sheetOpen = !sheetOpen },
        ) {
            if (room == null) {
                StartOrJoin(startWithJoin, ui.link == Link.Online, vm::create, vm::join, onClose)
            } else {
                RosterSheet(ui, sheetOpen, onRide, vm::leave)
            }
        }
    }
}

@Composable
private fun StartOrJoin(startWithJoin: Boolean, online: Boolean, onCreate: (String) -> Unit, onJoin: (String) -> Unit, onClose: () -> Unit) {
    val c = Cv.colors
    var tab by rememberSaveable { mutableStateOf(if (startWithJoin) 1 else 0) }
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(bottom = Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                CvText("Convoy", Cv.type.title, c.ink)
                Eyebrow(if (online) "CONNECTED" else "WAITING FOR THE SERVER")
            }
            Segmented(listOf(SegOption("Start"), SegOption("Join")), tab, { tab = it }, Modifier.width(160.dp))
        }
        if (tab == 0) {
            CvTextField(name, { name = it.take(40) }, "Convoy name", placeholder = "Sunday Run")
            CvButton("Start convoy", { onCreate(name) }, Modifier.fillMaxWidth(), enabled = online)
        } else {
            CvTextField(
                code, { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(12) }, "Invite code",
                placeholder = "K7QXM2PR", mono = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            CvButton("Join convoy", { onJoin(code) }, Modifier.fillMaxWidth(), enabled = online && code.length >= 8)
        }
        CvButton("Back", onClose, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
    }
}

@Composable
private fun RosterSheet(ui: ConvoyUi, open: Boolean, onRide: () -> Unit, onLeave: () -> Unit) {
    val c = Cv.colors
    val room = ui.room ?: return
    Column(Modifier.fillMaxWidth().padding(bottom = Space.s2)) {
        Row(Modifier.fillMaxWidth().padding(bottom = Space.s3), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CvText(room.name.ifBlank { "Convoy" }, Cv.type.title, c.ink, maxLines = 1)
                Eyebrow("${ui.onlineCount} OF ${ui.roster.size} ONLINE · SPREAD ${Geo.formatDistance(ui.spreadM).uppercase()}")
                CvText("CODE ${room.code}", Cv.type.telemetry, c.signal)
            }
            // Drive / Ride toggle: Ride opens the HUD.
            Segmented(
                options = listOf(SegOption("Drive", CvIcons.Car), SegOption("Ride", CvIcons.Bike)),
                selected = 0, onSelect = { if (it == 1) onRide() },
                modifier = Modifier.width(132.dp), iconsOnly = true,
            )
        }
        if (open) {
            LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                items(ui.roster, key = { it.id }) { e -> RosterRow(e, ui) }
            }
            CvButton("Leave convoy", onLeave, Modifier.fillMaxWidth().padding(top = Space.s3), CvButtonKind.Ghost)
        }
    }
}

@Composable
private fun RosterRow(e: RosterEntry, ui: ConvoyUi) {
    val c = Cv.colors
    val meta = when {
        e.state == MemberState.Offline -> "Offline"
        e.state == MemberState.Lead -> "Leading" + (e.speedKmh?.let { " · $it km/h" } ?: "")
        e.state == MemberState.Stopped -> "Stopped"
        else -> e.speedKmh?.let { "$it km/h" } ?: "Online"
    }
    RosterChip(
        initials = e.initials,
        name = e.name,
        meta = meta,
        state = e.state,
        distance = e.distanceM?.let { Geo.formatDistance(it).uppercase() },
        distanceCaption = when (e.side) { Side.Ahead -> "ahead"; Side.Behind -> "behind"; Side.Away -> null },
        metaColor = if (e.state == MemberState.Stopped) c.pulse else c.inkMuted,
    )
}
