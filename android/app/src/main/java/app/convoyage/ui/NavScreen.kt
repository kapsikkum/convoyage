package app.convoyage.ui

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.focusRequester
import app.convoyage.design.CvCard
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.convoyage.data.Bbox
import app.convoyage.data.Link
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.CutLShape
import app.convoyage.design.CutMShape
import app.convoyage.design.CutSShape
import app.convoyage.design.Cv
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvChipToggle
import app.convoyage.design.CvIcon
import app.convoyage.design.CvIconButton
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.CvTextField
import app.convoyage.design.Eyebrow
import app.convoyage.design.PinKind
import app.convoyage.design.Readout
import app.convoyage.design.Space
import app.convoyage.design.cutPanel
import app.convoyage.map.ConvoyMap
import app.convoyage.map.MapPinData
import app.convoyage.nav.ManeuverIcon
import app.convoyage.nav.Maneuvers
import app.convoyage.nav.NavPhase
import app.convoyage.nav.NavState
import app.convoyage.nav.Place
import org.maplibre.android.geometry.LatLng

/** The glyph for a maneuver. */
fun maneuverGlyph(icon: ManeuverIcon): ImageVector = when (icon) {
    ManeuverIcon.Depart, ManeuverIcon.Straight -> CvIcons.ArrowStraight
    ManeuverIcon.Arrive -> CvIcons.Pin
    ManeuverIcon.SlightLeft -> CvIcons.ArrowSlightLeft
    ManeuverIcon.Left -> CvIcons.ArrowLeft
    ManeuverIcon.SharpLeft -> CvIcons.ArrowSharpLeft
    ManeuverIcon.SlightRight -> CvIcons.ArrowSlightRight
    ManeuverIcon.Right -> CvIcons.ArrowRight
    ManeuverIcon.SharpRight -> CvIcons.ArrowSharpRight
    ManeuverIcon.UTurn -> CvIcons.ArrowUTurn
    ManeuverIcon.Roundabout -> CvIcons.ArrowRoundabout
    ManeuverIcon.ForkLeft -> CvIcons.ArrowForkLeft
    ManeuverIcon.ForkRight -> CvIcons.ArrowForkRight
    ManeuverIcon.Merge -> CvIcons.ArrowMerge
}

/** Zoom while following: closer when slow (junctions matter), further out when fast. */
fun followZoomFor(speedMps: Double): Double {
    // Smooth, not stepped: 18 when crawling, easing out to 16.4 by 30 m/s (108 km/h). Steps made the camera hop
    // in and out as speed dipped through a corner.
    val t = ((speedMps - 4.0) / 26.0).coerceIn(0.0, 1.0)
    return 18.0 - 1.6 * (t * t * (3 - 2 * t))
}

/**
 * Top map padding that puts the followed car about 72% of the way down the map above the sheet, so more of the road
 * ahead shows than behind. The camera centres in the padded area: centre = (top + visible) / 2.
 */
fun aheadPadding(mapHeight: androidx.compose.ui.unit.Dp, bottomPadding: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    ((mapHeight - bottomPadding) * 0.44f).coerceAtLeast(0.dp)

/** What the Drive tab adds around navigation: the dock's convoy, recording and route actions. */
class DriveHooks(
    val onStartConvoy: () -> Unit,
    val onJoinConvoy: () -> Unit,
    val onOpenRoutes: () -> Unit,
    val onBuildRoute: () -> Unit,
)

@Composable
fun NavScreen(onBack: () -> Unit, onReport: () -> Unit, drive: DriveHooks? = null) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val vm: NavViewModel = viewModel(factory = viewModelFactory { initializer { NavViewModel(container) } })
    val nav by container.nav.state.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val rawFix by container.tracker.fix.collectAsStateWithLifecycle()
    val fix = rememberSmoothedFix(rawFix)
    val headingUp = rememberHeadingUp(fix)
    val hazards by container.hazards.active.collectAsStateWithLifecycle()
    val convoy by container.convoy.state.collectAsStateWithLifecycle()
    val voiceOn by container.session.navVoice.collectAsStateWithLifecycle()
    val permission = rememberLocationPermission()
    TrackWhileVisible(permission)
    val c = Cv.colors
    val saved by container.savedPlaces.saved.collectAsStateWithLifecycle()
    val recent by container.savedPlaces.recent.collectAsStateWithLifecycle()
    val rec by container.recorder.state.collectAsStateWithLifecycle()
    val chrome = LocalBottomChrome.current
    var showRecord by remember { mutableStateOf(false) }
    val capture = rememberRidePhotoCapture()
    var dockDetent by rememberSaveable { mutableStateOf(Detent.Peek) }
    var boxH by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val navInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val idleDrive = drive != null && nav.phase == NavPhase.Idle
    val openCruiseOn by container.session.openCruise.collectAsStateWithLifecycle()

    // Warm up text-to-speech while the screen is up so the first prompt is not late.
    LaunchedEffect(Unit) { container.navVoice.prepare() }

    // "Invite friends": the share sheet opens as soon as the convoy exists (the server answers a moment after the request).
    val room = convoy.active
    LaunchedEffect(local.shareWhenReady, room?.code) {
        val r = room ?: return@LaunchedEffect
        if (local.shareWhenReady) {
            shareInvite(context, r.code, r.name)
            vm.shareHandled()
        }
    }

    // Hazards for the area: what the map shows, and the corridor of the route once there is one.
    var viewport by remember { mutableStateOf<Bbox?>(null) }
    LaunchedEffect(viewport) { viewport?.let { container.hazards.refresh(it) } }
    LaunchedEffect(nav.route) {
        val r = nav.route ?: return@LaunchedEffect
        val lats = r.points.map { it.lat }
        val lngs = r.points.map { it.lng }
        container.hazards.refresh(Bbox(lats.min() - 0.01, lngs.min() - 0.01, lats.max() + 0.01, lngs.max() + 0.01))
    }

    val active = nav.phase == NavPhase.Active || nav.phase == NavPhase.Arrived
    val line = remember(nav.route) { nav.route?.points.orEmpty() }
    val ahead = remember(line, nav.progress?.snap, nav.phase) { if (nav.phase == NavPhase.Active) remainingLine(line, nav.progress?.snap) else line }
    // Traffic, police, weather, road surface and stops for the route being previewed or driven.
    val routeGeo = remember(nav.route) { nav.route?.let { app.convoyage.data.Polyline.encode(it.points, 6) } }
    val navCond = rememberLineConditions(routeGeo, nav.route?.durationS)
    val routeStops = navCond.value?.stops.orEmpty()
    val atKm = nav.route?.let { (it.distanceM - nav.remainingM) / 1000.0 } ?: 0.0
    val upcomingFuel = if (nav.phase == NavPhase.Active) nextFuel(routeStops, atKm) else null
    // Off track while following: which way back to the route (from the part ahead of where we left it).
    val rejoin = if (nav.phase == NavPhase.Active && fix != null)
        app.convoyage.nav.Rejoin.target(line, app.convoyage.data.LatLngD(fix.lat, fix.lng), nav.progress?.snap?.seg ?: 0) else null
    val fit = remember(nav.route, nav.phase) {
        if (nav.phase == NavPhase.Preview) nav.route?.points.orEmpty().let { p -> p.filterIndexed { i, _ -> i % maxOf(1, p.size / 150) == 0 }.map { LatLng(it.lat, it.lng) } } else emptyList()
    }
    val pins = remember(nav.destination, nav.routeHazards, fix, nav.bearing, convoy.nearbyDrivers, convoy.nearbyConvoys, drive != null, routeStops, nav.phase) {
        buildList {
            // While driving only servos ahead stay on the map; the preview shows every stop.
            if (nav.phase == NavPhase.Preview) addAll(stopPins(routeStops))
            else if (nav.phase == NavPhase.Active) addAll(stopPins(routeStops.filter { it.kind == "fuel" && it.km >= atKm }))
            if (drive != null && nav.phase == NavPhase.Idle) addAll(nearbyPins(convoy.nearbyDrivers, convoy.nearbyConvoys))
            nav.routeHazards.forEach { add(it.hazard.toPin()) }
            nav.destination?.let { add(MapPinData("dest", PinKind.Destination, "", it.lat, it.lng, tag = it.name.take(12).uppercase().ifBlank { "DEST" })) }
            // Our own smoothed heading (from how we actually move), not the route's: the route bearing jumps between
            // segments and the raw GPS heading is often missing or 0.
            fix?.let { add(MapPinData("me", PinKind.Me, "", it.lat, it.lng, it.headingDeg ?: nav.bearing)) }
        }
    }
    val following = nav.phase == NavPhase.Active && local.follow
    val sheetHeight = when (nav.phase) { NavPhase.Idle -> if (drive != null) DriveDockPeek else 0.dp; NavPhase.Active, NavPhase.Arrived -> 296.dp; else -> 236.dp + (if (navCond.value != null) 44.dp else 0.dp) } + chrome

    Box(Modifier.fillMaxSize().onSizeChanged { boxH = it.height }) {
        ConvoyMap(
            modifier = Modifier.fillMaxSize(),
            pins = pins,
            me = fix?.let { LatLng(it.lat, it.lng) },
            route = emptyList(),
            routeLine = ahead,
            rejoinTo = rejoin,
            buildings3d = local.buildings3d,
            follow = if (nav.phase == NavPhase.Preview || nav.phase == NavPhase.Routing) false else local.follow,
            recenterTick = local.recenterTick,
            onUserMoved = vm::userMovedMap,
            bottomPadding = sheetHeight + 24.dp,
            topPadding = if (following || (nav.phase == NavPhase.Idle && headingUp != null && local.follow)) aheadPadding(with(density) { boxH.toDp() }, sheetHeight + 24.dp) else 0.dp,
            followBearing = if (following) (fix?.headingDeg ?: nav.bearing) else if (nav.phase == NavPhase.Idle && local.follow) headingUp else null,
            headingUpFallbackNorth = nav.phase == NavPhase.Idle,
            followZoom = if (nav.phase == NavPhase.Active) followZoomFor(nav.speedMps) else if (nav.phase == NavPhase.Idle && local.follow && fix != null) followZoomFor(fix.speedMps ?: 0.0) else null,
            fitTo = fit,
            onViewport = { b -> viewport = Bbox(b.south, b.west, b.north, b.east) },
            onLongPress = if (nav.phase != NavPhase.Active) { ll -> vm.pick(ll.latitude, ll.longitude) } else null,
        )

        // ---- top ----
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp).padding(top = Space.s2),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            if (drive != null) CruiseInviteBanner()
            if (!permission.granted) {
                Banner(BannerTone.Hazard, "Location is off", sub = "Allow location so navigation can follow you.", icon = CvIcons.Locate, actionLabel = "Allow", onAction = permission.request)
            }
            when (nav.phase) {
                NavPhase.Idle -> {
                    SearchPanel(
                        query = query, onQuery = { vm.query.value = it }, search = search, onPick = vm::select,
                        onBack = if (drive == null) onBack else null,
                        error = nav.error, onDismissError = vm::clearError,
                    )
                    if (drive != null && query.isBlank()) {
                        DriveChips(saved, recent, onPick = vm::select, onRoutes = drive.onOpenRoutes, onUnset = { slot ->
                            vm.hint("Search for ${slot.label.lowercase()}, then tap Set as ${slot.label}")
                        })
                    }
                }
                NavPhase.Routing -> DestinationBar(nav.destination, "FINDING A ROUTE", onBack = { vm.end() })
                NavPhase.Preview -> {
                    DestinationBar(nav.destination, "ROUTE", onBack = { vm.end() })
                    nav.destination?.let { d ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                            app.convoyage.data.SavedSlot.entries.forEach { slot ->
                                val isIt = saved[slot]?.let { it.lat == d.lat && it.lng == d.lng } == true
                                CvChipToggle(if (isIt) slot.label else "Set as ${slot.label}", isIt, {
                                    if (isIt) container.savedPlaces.clear(slot) else container.savedPlaces.save(slot, d)
                                })
                            }
                        }
                    }
                }
                NavPhase.Active, NavPhase.Arrived -> {
                    val step = nav.maneuver
                    val p = nav.progress
                    if (step != null) {
                        ManeuverCard(
                            step = step,
                            distanceM = if (nav.phase == NavPhase.Arrived) 0.0 else p?.distToManeuverM ?: 0.0,
                            then = nav.thenStep,
                            muted = !voiceOn,
                            arrived = nav.phase == NavPhase.Arrived,
                            arrivedLine = nav.destination?.name?.let { "Arrived at $it" } ?: "You have arrived",
                        )
                    }
                    DetourBanner(nav, onResume = vm::resume, onEnd = vm::end)
                    // Search stays on while driving: a pick is a stop on the way.
                    ExpandingSearch(query, { vm.query.value = it }, search, vm::select, "Add a stop")
                    if (nav.rerouting) Banner(BannerTone.Hazard, "Rerouting", icon = CvIcons.Route)
                    nav.error?.let { Banner(BannerTone.Pulse, it, icon = CvIcons.Alert, actionLabel = "OK", onAction = vm::clearError) }
                    nav.hazardAhead?.let { h ->
                        val camera = app.convoyage.nav.RouteHazards.isCamera(h.hazard)
                        Banner(
                            when { camera -> BannerTone.Pulse; h.hazard.isPolice -> BannerTone.Police; else -> BannerTone.Hazard },
                            (if (camera) "Speed camera ahead" else hazardLabel(h.hazard.kind, h.hazard.subkind)) + " · " + app.convoyage.data.Geo.formatDistance(h.aheadM),
                            sub = if (nav.alertsMuted) "On your route · alerts muted" else "On your route",
                            icon = CvIcons.Alert,
                            actionLabel = if (nav.alertsMuted) "Unmute" else "Mute",
                            onAction = container.nav::toggleAlertsMuted,
                            secondaryLabel = "Close",
                            onSecondary = { container.nav.dismissHazard(h.hazard.id) },
                        )
                    }
                }
            }
            local.hint?.let { Banner(BannerTone.Hazard, it, icon = CvIcons.Flag) }
            if (convoy.link != Link.Online && nav.phase != NavPhase.Idle && nav.phase != NavPhase.Routing) {
                // Navigation works offline from here; only the convoy features need the server.
                Eyebrow(if (convoy.link == Link.Connecting) "CONVOY SERVER: CONNECTING" else "CONVOY SERVER: OFFLINE", color = c.inkMuted)
            }
        }

        // ---- right rail ----
        if (idleDrive && dockDetent == Detent.Peek) {
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = sheetHeight + navInset + Space.s3),
                verticalArrangement = Arrangement.spacedBy(Space.s2),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Open Cruise: show me to drivers nearby and them to me. Lit when on, with how many it found.
                val found = convoy.nearbyDrivers.size + convoy.nearbyConvoys.size
                Box {
                    CvIconButton(
                        CvIcons.Car, if (openCruiseOn) "Open cruise on, turn off" else "Open cruise: see drivers nearby",
                        { container.setOpenCruise(!openCruiseOn) },
                        tint = if (openCruiseOn) c.go else c.ink, edge = if (openCruiseOn) c.go else c.lineStrong,
                    )
                    if (openCruiseOn && convoy.openCruise && found > 0) {
                        Box(
                            Modifier.align(Alignment.TopEnd).size(18.dp).cutPanel(c.go, c.go, CutSShape),
                            contentAlignment = Alignment.Center,
                        ) { CvText(found.coerceAtMost(9).toString(), Cv.type.label.copy(fontSize = 9.sp, lineHeight = 10.sp), c.surface100) }
                    }
                }
                CvChipToggle("3D", local.buildings3d, vm::toggle3d)
                CvIconButton(CvIcons.Alert, "Report hazard", onReport, tint = c.hazard, edge = c.hazard)
                CvIconButton(CvIcons.Locate, "Recentre", vm::recenter, tint = if (local.follow) c.signal else c.ink)
            }
        }
        if (active) {
            SpeedReadout(nav.speedMps, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = sheetHeight + navInset + Space.s3))
        }
        // Next servo on the route, with its price, above the speed.
        upcomingFuel?.let { (s, km) ->
            CvCard(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = sheetHeight + navInset + Space.s3 + 96.dp).widthIn(max = 220.dp), shape = CutSShape) {
                Column(Modifier.padding(horizontal = Space.s3, vertical = Space.s2)) {
                    Eyebrow("NEXT FUEL · " + Fmt.km(km * 1000) + " KM")
                    CvText(s.name.ifBlank { s.brand.ifBlank { "Servo" } }, Cv.type.bodyStrong, c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    priceLine(s, 2).takeIf { it.isNotBlank() }?.let { CvText(it, Cv.type.telemetry, c.signal, maxLines = 1) }
                }
            }
        }
        if (active) {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 12.dp, top = 60.dp),
                verticalArrangement = Arrangement.spacedBy(Space.s2),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CvIconButton(if (voiceOn) CvIcons.VolumeOn else CvIcons.VolumeOff, if (voiceOn) "Mute voice" else "Unmute voice", vm::toggleVoice, tint = if (voiceOn) c.ink else c.inkMuted)
                CvIconButton(CvIcons.Alert, "Report hazard", onReport, tint = c.hazard, edge = c.hazard)
                CvIconButton(CvIcons.Locate, "Recentre", vm::recenter, tint = if (local.follow) c.signal else c.ink)
                CvChipToggle("3D", local.buildings3d, vm::toggle3d)
            }
        }

        // ---- bottom sheet ----
        if (nav.phase != NavPhase.Idle) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .cutPanel(c.surface100, c.lineStrong, CutLShape)
                    .navigationBarsPadding()
                    .padding(bottom = chrome)
                    .padding(horizontal = Space.gutter, vertical = Space.s3),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).cutPanel(c.lineStrong, c.lineStrong, CutSShape))
                EtaSheet(
                    nav = nav,
                    conditions = navCond.value,
                    inConvoy = room != null,
                    onStart = vm::start,
                    onInvite = vm::inviteFriends,
                    onEnd = { vm.end(); if (drive == null) onBack() },
                )
                if (active) DriveRecordBar(rec, onRecord = { showRecord = true }, onPhoto = { capture.shoot(rec.isActive) })
            }
        }

        RecenterPill(
            visible = !local.follow && nav.phase != NavPhase.Preview && nav.phase != NavPhase.Routing && fix != null && (!idleDrive || dockDetent == Detent.Peek),
            onClick = vm::recenter,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = sheetHeight + navInset + Space.s3),
        )

        if (idleDrive && drive != null) {
            ConvoySheet(
                detent = dockDetent,
                onDetentChange = { dockDetent = it },
                containerHeightPx = boxH,
                topReservePx = WindowInsets.statusBars.getTop(density) + with(density) { 120.dp.roundToPx() },
                peekHeight = DriveDockPeek,
            ) { expanded ->
                DriveDock(
                    expanded = expanded,
                    drive = drive,
                    saved = saved,
                    recent = recent,
                    recording = rec.isActive,
                    onRecord = { showRecord = true },
                    onPick = vm::select,
                    onForget = container.savedPlaces::forget,
                    cruise = CruiseUi(openCruiseOn, convoy.openCruise, convoy.nearbyDrivers, convoy.nearbyConvoys, convoy.invited),
                    onCruise = container::setOpenCruise,
                    onJoinOpen = container.convoy::joinOpen,
                    onInvite = container.convoy::inviteNearby,
                )
            }
        }
        if (showRecord) RecordSheet(onDismiss = { showRecord = false }, bottomInset = chrome)
        PhotoConfirmSheet(capture)
    }
}

/** The part of the route still ahead: from where we are on it to the end. */
internal fun remainingLine(line: List<app.convoyage.data.LatLngD>, snap: app.convoyage.nav.Snap?): List<app.convoyage.data.LatLngD> {
    if (snap == null || line.size < 2) return line
    val rest = line.drop((snap.seg + 1).coerceAtMost(line.size - 1))
    return listOf(app.convoyage.data.LatLngD(snap.lat, snap.lng)) + rest
}

/** On a detour: where we carry on to afterwards, and the button to do it (now, or once we have arrived). */
@Composable
internal fun DetourBanner(nav: app.convoyage.nav.NavState, onResume: () -> Unit, onEnd: () -> Unit) {
    val back = nav.resumeTo ?: return
    val name = back.name.ifBlank { "the route" }
    if (nav.phase == NavPhase.Arrived) {
        Banner(BannerTone.Hazard, "Continue with the route?", sub = "On to $name", icon = CvIcons.Route, actionLabel = "Continue", onAction = onResume, secondaryLabel = "End", onSecondary = onEnd)
    } else if (nav.phase == NavPhase.Active) {
        Banner(BannerTone.Hazard, "Stop on the way", sub = "Then on to $name", icon = CvIcons.Route, actionLabel = "Skip", onAction = onResume)
    }
}

/** A search button that opens into the full search bar and its results; closes again after a pick or Back. */
@Composable
internal fun ExpandingSearch(
    query: String,
    onQuery: (String) -> Unit,
    search: SearchUi,
    onPick: (Place) -> Unit,
    label: String = "Search",
    modifier: Modifier = Modifier,
) {
    val c = Cv.colors
    var open by rememberSaveable { mutableStateOf(false) }
    androidx.compose.animation.AnimatedContent(open, modifier, label = "search") { isOpen ->
        if (isOpen) {
            SearchPanel(
                query = query, onQuery = onQuery, search = search,
                onPick = { p -> open = false; onPick(p) },
                onBack = { open = false; onQuery("") },
                error = null, onDismissError = {}, autoFocus = true,
            )
        } else {
            Row(
                Modifier
                    .cutPanel(c.surface100, c.lineStrong, CutMShape)
                    .clickable(role = Role.Button) { open = true }
                    .semantics { contentDescription = label }
                    .padding(horizontal = Space.s3, vertical = Space.s2),
                horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically,
            ) {
                CvIcon(CvIcons.Search, c.signal)
                CvText(label.uppercase(), Cv.type.label, c.ink, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun SearchPanel(
    query: String,
    onQuery: (String) -> Unit,
    search: SearchUi,
    onPick: (Place) -> Unit,
    onBack: (() -> Unit)?,
    error: String?,
    onDismissError: () -> Unit,
    autoFocus: Boolean = false,
) {
    val c = Cv.colors
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Say where to go: the phone's speech recogniser fills the search.
    val speak = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onQuery)
    }
    Column(
        Modifier.fillMaxWidth().imePadding().cutPanel(c.surface100, c.signal, CutMShape).padding(Space.s2),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        // One compact bar: back (when searching from somewhere), the field, and the mic.
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) CvIconButton(CvIcons.Back, "Back", onBack, fill = c.surface200)
            else CvIcon(CvIcons.Search, c.signal, Modifier.padding(start = Space.s1))
            CvTextField(
                query, onQuery, "", Modifier.weight(1f).focusRequester(focus), placeholder = "Where to?",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            CvIconButton(CvIcons.Mic, "Say where to", {
                val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Where to?")
                try { speak.launch(intent) } catch (_: android.content.ActivityNotFoundException) { /* no speech app on this phone */ }
            }, fill = c.surface200)
        }
        error?.let { Banner(BannerTone.Pulse, it, icon = CvIcons.Alert, actionLabel = "OK", onAction = onDismissError) }
        when {
            search.loading -> Eyebrow("SEARCHING")
            search.error != null -> CvText(search.error, Cv.type.bodyS, c.inkMuted)
            search.results.isEmpty() -> if (onBack != null || query.isNotBlank()) Eyebrow("OR LONG-PRESS THE MAP TO DROP A PIN")
            else -> LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                items(search.results, key = { "${it.lat},${it.lng},${it.name}" }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(p) }.padding(vertical = Space.s2),
                        horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CvIcon(CvIcons.Pin, c.signal)
                        Column(Modifier.weight(1f)) {
                            CvText(p.name, Cv.type.bodyStrong, c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.detail.isNotBlank()) CvText(p.detail, Cv.type.bodyS, c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationBar(place: Place?, caption: String, onBack: () -> Unit) {
    val c = Cv.colors
    Row(
        Modifier.fillMaxWidth().cutPanel(c.surface100, c.signal, CutMShape).padding(Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically,
    ) {
        CvIconButton(CvIcons.Back, "Cancel", onBack, fill = c.surface200)
        Column(Modifier.weight(1f)) {
            Eyebrow(caption, color = c.signal)
            CvText(place?.name.orEmpty(), Cv.type.title, c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!place?.detail.isNullOrBlank()) CvText(place!!.detail, Cv.type.bodyS, c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The maneuver card of the mockup: arrow, distance in mono, street, and the step after. */
@Composable
internal fun ManeuverCard(step: app.convoyage.nav.RouteStep, distanceM: Double, then: app.convoyage.nav.RouteStep?, muted: Boolean, arrived: Boolean, arrivedLine: String = "You have arrived") {
    val c = Cv.colors
    Column(
        Modifier.fillMaxWidth().cutPanel(c.surface200, c.signal, CutLShape).padding(horizontal = Space.s4, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CvIcon(maneuverGlyph(Maneuvers.icon(step)), c.signal, size = 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CvText(
                    if (arrived) "ARRIVED" else Fmt.navDistance(distanceM),
                    Cv.type.telemetryL.copy(fontSize = 34.sp, lineHeight = 38.sp), c.signal, maxLines = 1,
                )
                CvText(if (arrived) arrivedLine else Maneuvers.headline(step), Cv.type.title, c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (muted) CvIcon(CvIcons.VolumeOff, c.inkMuted, size = 20.dp)
        }
        if (then != null && !arrived) {
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically,
            ) {
                CvIcon(maneuverGlyph(Maneuvers.icon(then)), c.inkMuted, size = 20.dp)
                CvText(Maneuvers.then(then), Cv.type.bodyS, c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EtaSheet(nav: NavState, conditions: app.convoyage.api.RouteConditions?, inConvoy: Boolean, onStart: () -> Unit, onInvite: () -> Unit, onEnd: () -> Unit) {
    val c = Cv.colors
    val preview = nav.phase == NavPhase.Preview || nav.phase == NavPhase.Routing
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Readout("Arrive", nav.etaMs?.let { Fmt.clockOfDay(it) } ?: "--:--", valueColor = c.signal)
        Readout("Time", if (nav.route == null) "--" else Math.round(nav.remainingS / 60.0).toString(), unit = "min")
        Readout("Distance", if (nav.route == null) "--" else Fmt.km(nav.remainingM), unit = "km")
    }
    val dest = nav.destination
    val via = nav.route?.steps?.filter { it.name.isNotBlank() }?.maxByOrNull { it.distanceM }?.name
    CvText(
        buildString {
            append("To ${dest?.name ?: "destination"}")
            if (via != null) append(" · via $via")
            if (nav.routeHazards.isNotEmpty()) append(" · ${nav.routeHazards.size} report${if (nav.routeHazards.size == 1) "" else "s"} on route")
        },
        Cv.type.bodyS, c.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
    // The route at a glance before setting off: traffic, police, weather, surface, and the cheapest fuel on the way.
    if (preview && conditions != null) {
        val cheapest = conditions.stops.filter { it.prices["U91"] != null }.minByOrNull { it.prices["U91"]!! }
        CvText(
            listOfNotNull(
                "Traffic " + conditions.traffic.level,
                if (conditions.police.isEmpty()) "no police" else "${conditions.police.size} police",
                conditions.weather.points.firstOrNull()?.let { p -> p.label + (p.tempC?.let { t -> " ${Math.round(t)}°" } ?: "") },
                conditions.roads?.let { r -> if (r.unpavedKm > 0) "${r.unpavedKm} km unsealed" else "sealed" },
                conditions.stops.count { it.kind == "fuel" }.takeIf { it > 0 }?.let { "$it servos" },
            ).joinToString(" · ") + (cheapest?.let { s -> "\nCheapest U91 ${"%.1f".format(java.util.Locale.ROOT, s.prices["U91"])} at km ${Math.round(s.km)}" } ?: ""),
            Cv.type.bodyS, c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
    if (preview) {
        CvButton(if (nav.phase == NavPhase.Routing) "Finding route..." else "Start", onStart, Modifier.fillMaxWidth(), enabled = nav.phase == NavPhase.Preview, icon = CvIcons.Navigate)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        CvButton(
            if (inConvoy) "Share convoy" else "Invite friends", onInvite, Modifier.weight(1f), CvButtonKind.Ghost,
            icon = CvIcons.Convoy, enabled = nav.route != null, compact = true,
        )
        CvButton(if (preview) "Cancel" else "End", onEnd, Modifier.weight(1f), if (preview) CvButtonKind.Ghost else CvButtonKind.Alert, compact = true)
    }
}
