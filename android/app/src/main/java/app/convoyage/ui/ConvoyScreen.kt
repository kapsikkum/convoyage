package app.convoyage.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.convoyage.data.AlertKind
import app.convoyage.data.Bbox
import app.convoyage.data.Geo
import app.convoyage.data.Link
import app.convoyage.data.Names
import app.convoyage.data.RosterEntry
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
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
import app.convoyage.design.MemberState
import app.convoyage.design.PinKind
import app.convoyage.design.PushToTalk
import app.convoyage.design.SegOption
import app.convoyage.design.Segmented
import app.convoyage.design.Space
import app.convoyage.design.cutPanel
import app.convoyage.map.ConvoyMap
import app.convoyage.map.MapPinData
import kotlinx.coroutines.delay
import org.maplibre.android.geometry.LatLng

private fun Double.round2() = Math.round(this * 100) / 100.0

private enum class SheetTab { Roster, Chat, Lead }

@Composable
fun ConvoyScreen(startWithJoin: Boolean, onRide: () -> Unit, onReport: () -> Unit, onClose: () -> Unit, onBuildRoute: () -> Unit = {}, openPreStart: Boolean = false) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val vm: ConvoyViewModel = viewModel(factory = viewModelFactory { initializer { ConvoyViewModel(container) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    val permission = rememberLocationPermission()
    TrackWhileVisible(permission)

    val c = Cv.colors
    val density = LocalDensity.current
    val room = ui.room
    val hazards by container.hazards.active.collectAsStateWithLifecycle()
    val rec by container.recorder.state.collectAsStateWithLifecycle()
    val capture = rememberRidePhotoCapture()
    val sessionName by container.session.name.collectAsStateWithLifecycle()
    var showRecord by remember { mutableStateOf(false) }
    var pickRoute by remember { mutableStateOf(false) }
    // "Show everyone": a snapshot of positions the camera frames once; cleared when following again.
    var fitAll by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    LaunchedEffect(local.follow) { if (local.follow) fitAll = emptyList() }
    val sharedRoute by container.convoy.sharedRoute.collectAsStateWithLifecycle()
    val navState by container.nav.state.collectAsStateWithLifecycle()
    val garage by container.garage.state.collectAsStateWithLifecycle()
    val myVehicle = remember(garage) { garage.primary?.let { app.convoyage.api.VehicleBadge(it.kind, it.build) } }
    // Search is always on in the convoy view: a stop for you on the way, or (leader, nowhere set) where everyone goes.
    val navVm: NavViewModel = viewModel(factory = viewModelFactory { initializer { NavViewModel(container) } })
    val navSearch by navVm.search.collectAsStateWithLifecycle()
    val navQuery by navVm.query.collectAsStateWithLifecycle()
    val navLocal by navVm.local.collectAsStateWithLifecycle()
    val navVoiceOn by container.session.navVoice.collectAsStateWithLifecycle()

    // Sheet state: peek while in a convoy; a pre-start sheet opens to half when Start or Join is chosen.
    var detent by rememberSaveable { mutableStateOf(Detent.Peek) }
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    var startTab by rememberSaveable { mutableStateOf(if (startWithJoin) 1 else 0) }
    var showQr by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var confirmLead by remember { mutableStateOf<RosterEntry?>(null) }
    var confirmRemove by remember { mutableStateOf<RosterEntry?>(null) }
    var containerH by remember { mutableStateOf(0) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    LaunchedEffect(startWithJoin) { if ((startWithJoin || openPreStart) && room == null) detent = Detent.Half }
    // A convoy appearing (start/join answered) settles at peek; leaving it does too.
    LaunchedEffect(room?.code) { if (room != null || !(startWithJoin || openPreStart)) detent = Detent.Peek; tabIndex = 0 }

    // `convoyage://join/<CODE>`: join as soon as the connection is up.
    val pendingJoin by container.pendingJoin.collectAsStateWithLifecycle()
    LaunchedEffect(pendingJoin, ui.link, room?.code) {
        val code = pendingJoin ?: return@LaunchedEffect
        if (ui.link != Link.Online) return@LaunchedEffect
        if (room?.code != code) vm.join(code)
        container.setPendingJoin(null)
    }

    // Load the hazards for what the map shows now, and again every 30 s while the screen is on.
    var viewport by remember { mutableStateOf<Bbox?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewport) {
        val box = viewport ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                container.hazards.refresh(box)
                delay(30_000)
            }
        }
    }

    // Members glide between fixes and are carried forward along their heading (display only).
    val motion = remember { app.convoyage.data.ConvoyMotion() }
    var motionNow by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(room?.members) {
        motion.update(room?.members.orEmpty(), System.currentTimeMillis())
        do {
            motionNow = System.currentTimeMillis()
            kotlinx.coroutines.delay(50)
        } while (motion.isAnimating(System.currentTimeMillis()))
        motionNow = System.currentTimeMillis()
    }

    val myFix = rememberSmoothedFix(ui.fix)
    val headingUp = rememberHeadingUp(myFix)
    val pins = remember(ui.roster, myFix, hazards, room?.destination, local.pendingDest, motionNow) {
        ui.roster.mapNotNull { e ->
            if (e.isMe || e.lat == null || e.lng == null) return@mapNotNull null
            val pose = motion.pose(e.id, motionNow)
            MapPinData(
                id = e.id,
                kind = when (e.state) {
                    MemberState.Lead -> PinKind.Lead
                    MemberState.Stopped -> PinKind.Stopped
                    MemberState.Offline -> PinKind.Offline
                    MemberState.Online -> PinKind.Member
                },
                label = e.initials, lat = pose?.lat ?: e.lat, lng = pose?.lng ?: e.lng, headingDeg = pose?.heading ?: e.headingDeg, vehicle = e.vehicle,
                tag = if (e.state == MemberState.Lead) e.distanceM?.let { Geo.formatDistance(it).uppercase() } else null,
            )
        } + hazards.map { it.toPin() } + listOfNotNull(
            myFix?.let { MapPinData("me", PinKind.Me, "", it.lat, it.lng, it.headingDeg, vehicle = myVehicle) },
            room?.destination?.let { MapPinData("dest", PinKind.Destination, "", it.lat, it.lng, tag = it.label.take(12).uppercase().ifBlank { "DEST" }) },
            local.pendingDest?.let { MapPinData("pending", PinKind.Destination, "", it.lat, it.lng, tag = "CONFIRM") },
        )
    }

    val statusTop = WindowInsets.statusBars.getTop(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    val peekPad = SheetPeekHeight + with(density) { navBottom.toDp() } + LocalBottomChrome.current

    Box(Modifier.fillMaxSize().onSizeChanged { containerH = it.height }) {
        ConvoyMap(
            modifier = Modifier.fillMaxSize(),
            pins = pins,
            me = myFix?.let { LatLng(it.lat, it.lng) },
            route = room?.route?.stops.orEmpty(),
            // Guiding (a detour or our own navigation): the part of that route still ahead. Else the convoy's line.
            routeLine = if (navState.phase.guiding()) remainingLine(navState.route?.points.orEmpty(), navState.progress?.snap.takeIf { navState.phase == app.convoyage.nav.NavPhase.Active })
                else ui.routeLine,
            // Off the convoy's route (shared line or guidance): a pulsing hint of which way back.
            rejoinTo = myFix?.let { f ->
                val guide = navState.route?.points?.takeIf { navState.phase.guiding() }
                app.convoyage.nav.Rejoin.target(guide ?: ui.routeLine, app.convoyage.data.LatLngD(f.lat, f.lng), if (guide != null) navState.progress?.snap?.seg ?: 0 else 0)
            },
            buildings3d = local.buildings3d,
            follow = local.follow,
            recenterTick = local.recenterTick,
            onUserMoved = vm::userMovedMap,
            fitTo = fitAll,
            followBearing = if (local.follow && !navState.phase.guiding()) headingUp else if (local.follow) (myFix?.headingDeg ?: navState.bearing) else null,
            headingUpFallbackNorth = true,
            followZoom = if (local.follow && myFix != null) followZoomFor(myFix.speedMps ?: 0.0) else null,
            // The map keeps the same padding in every state, so the camera does not lurch as the sheet moves.
            bottomPadding = peekPad,
            // Following with the map turned to our heading: sit low so the road ahead fills the screen.
            topPadding = if (local.follow && myFix != null && (navState.phase.guiding() || headingUp != null)) aheadPadding(with(density) { containerH.toDp() }, peekPad) else 0.dp,
            // Rounded to ~1 km so small pans do not reload; the 30 s loop above keeps it fresh.
            onViewport = { b -> viewport = Bbox(b.south.round2(), b.west.round2(), b.north.round2(), b.east.round2()) },
            onLongPress = if (room != null) { ll -> vm.proposeDestination(ll.latitude, ll.longitude) } else null,
        )

        // Top: banners, then the toggle row.
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp).padding(top = Space.s2),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            CruiseInviteBanner()
            // Turn-by-turn keeps going inside the convoy view (Drive tab): its maneuver card leads the top.
            if (navState.phase.guiding()) {
                navState.maneuver?.let { step ->
                    ManeuverCard(
                        step = step,
                        distanceM = if (navState.phase == app.convoyage.nav.NavPhase.Arrived) 0.0 else navState.progress?.distToManeuverM ?: 0.0,
                        then = navState.thenStep,
                        muted = !navVoiceOn,
                        arrived = navState.phase == app.convoyage.nav.NavPhase.Arrived,
                        arrivedLine = navState.destination?.name?.let { "Arrived at $it" } ?: "You have arrived",
                    )
                }
            }
            DetourBanner(navState, onResume = navVm::resume, onEnd = navVm::end)
            ExpandingSearch(navQuery, { navVm.query.value = it }, navSearch, navVm::pickInConvoy, if (navState.phase.guiding() || room?.destination != null) "Add a stop" else "Where to?")
            navLocal.hint?.let { Banner(BannerTone.Hazard, it, icon = CvIcons.Flag) }
            if (room != null) MemberChips(ui.roster.filter { !it.isMe })
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
            ui.sos?.let { m ->
                Banner(
                    BannerTone.Pulse, "SOS from ${ui.sosName.ifBlank { "a rider" }}",
                    sub = m.note ?: "Possible crash",
                    icon = CvIcons.Alert,
                    actionLabel = "OK", onAction = vm::dismissSos,
                )
            }
            local.hint?.let { Banner(BannerTone.Hazard, it, icon = CvIcons.Flag) }
            HazardAlertBanner()
            PhotoHint(capture)
            ui.alert?.let { a ->
                val action = when {
                    a.canRegroup -> "Regroup" to vm::findRestStop
                    a.canContinue -> "Continue" to vm::continueFromRest
                    else -> null
                }
                Banner(
                    tone = if (a.kind == AlertKind.Stopped) BannerTone.Pulse else BannerTone.Hazard,
                    title = a.title, sub = a.sub, icon = if (a.kind == AlertKind.Stopped) CvIcons.Stop else CvIcons.Route,
                    actionLabel = action?.first, onAction = action?.second,
                )
            }
            local.pendingDest?.let { p -> PendingDestinationCard(p, onConfirm = vm::confirmDestination, onCancel = vm::cancelPendingDestination) }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                CvChipToggle("3D", local.buildings3d, vm::toggle3d)
                if (rec.isActive) CvChipToggle("REC ${Fmt.clock(rec.elapsedS)}${if (rec.isPaused) " II" else ""}", true, { showRecord = true })
                // TODO(layers): SPOTS / EVENTS / WAZE overlays are not in this version.
            }
        }

        // Right rail: one stable spot just above the sheet's peek height, in every state. It steps aside when the
        // sheet is pulled up over it.
        AnimatedVisibility(
            visible = detent == Detent.Peek,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = peekPad + Space.s3),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2), horizontalAlignment = Alignment.CenterHorizontally) {
                val everyone = ui.roster.mapNotNull { e -> if (!e.isMe && e.lat != null && e.lng != null) LatLng(e.lat, e.lng) else null } +
                    listOfNotNull(ui.fix?.let { LatLng(it.lat, it.lng) }, room?.destination?.let { LatLng(it.lat, it.lng) })
                if (room != null && everyone.size >= 2) {
                    CvIconButton(CvIcons.Convoy, "Show everyone", { vm.userMovedMap(); fitAll = everyone.toList() }, tint = if (fitAll.isNotEmpty()) c.signal else c.ink)
                }
                CvIconButton(CvIcons.Alert, "Report hazard", onReport, tint = c.hazard, edge = c.hazard)
                if (room == null) CvIconButton(CvIcons.Locate, "Recentre on me", vm::recenter, tint = if (local.follow) c.signal else c.ink)
                // Hold to talk: big and round, where a thumb rests while driving.
                if (room != null) {
                    val ptt = rememberPtt()
                    PushToTalk(state = ptt.state, size = 88.dp, caption = ptt.caption, onPress = ptt.onPress, onRelease = ptt.onRelease)
                }
            }
        }

        RecenterPill(
            visible = !local.follow && myFix != null && detent == Detent.Peek,
            onClick = vm::recenter,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = peekPad + Space.s3),
        )

        ConvoySheet(
            detent = detent,
            onDetentChange = { detent = it },
            containerHeightPx = containerH,
            topReservePx = statusTop + with(density) { 8.dp.toPx().toInt() },
        ) { expanded ->
            if (room == null) {
                PreStartSheet(
                    expanded = expanded,
                    tab = startTab,
                    onTab = { startTab = it; detent = Detent.Half },
                    online = ui.link == Link.Online,
                    defaultName = Names.defaultConvoyName(sessionName),
                    onCreate = vm::create,
                    onJoin = vm::join,
                    onClose = onClose,
                )
            } else {
                val tabs = buildList { add(SheetTab.Roster); add(SheetTab.Chat); if (ui.isLeader) add(SheetTab.Lead) }
                val tab = tabs.getOrElse(tabIndex) { SheetTab.Roster }
                ConvoyHeader(
                    ui = ui, copied = copied, expanded = expanded,
                    etaMs = navState.etaMs.takeIf { navState.phase.guiding() },
                    recActive = rec.isActive,
                    onRecord = { showRecord = true }, onPhoto = { capture.shoot(rec.isActive) }, onRide = onRide,
                    onCopy = { copyInvite(context, room.code); copied = true },
                    onShare = { shareInvite(context, room.code, room.name) },
                    onQr = { showQr = true },
                    onChat = { tabIndex = tabs.indexOf(SheetTab.Chat); detent = Detent.Half },
                )
                if (expanded) {
                    Segmented(
                        options = tabs.map { t ->
                            SegOption(
                                when (t) {
                                    SheetTab.Roster -> "Roster"
                                    SheetTab.Chat -> if (ui.unread > 0 && tab != SheetTab.Chat) "Chat ${ui.unread}" else "Chat"
                                    SheetTab.Lead -> "Lead"
                                },
                            )
                        },
                        selected = tabs.indexOf(tab).coerceAtLeast(0),
                        onSelect = { tabIndex = it },
                        modifier = Modifier.fillMaxWidth().padding(vertical = Space.s2),
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (tab) {
                            SheetTab.Roster -> RosterPanel(
                                ui,
                                onShare = { shareInvite(context, room.code, room.name) },
                                onCopy = { copyInvite(context, room.code); copied = true },
                                onQr = { showQr = true },
                                onLeave = vm::leave,
                            )
                            SheetTab.Chat -> ChatPanel(ui, onSend = vm::sendChat, onRead = vm::markChatRead)
                            SheetTab.Lead -> LeaderPanel(
                                ui,
                                onClearDestination = vm::clearDestination,
                                onRename = vm::rename,
                                onFindRest = vm::findRestStop,
                                onContinue = vm::continueFromRest,
                                onHandOver = { confirmLead = it },
                                onRemove = { confirmRemove = it },
                                sharedRoute = sharedRoute,
                                onPickRoute = { pickRoute = true },
                                onRollOut = container.convoy::rollOut,
                                onSetOpen = container.convoy::setConvoyOpen,
                                onSetRegroup = container.convoy::setAutoRegroup,
                                onJustCruise = container.convoy::justCruise,
                            )
                        }
                    }
                }
            }
        }

        if (showRecord) RecordSheet(onDismiss = { showRecord = false })
        if (pickRoute) SavedRoutePickerSheet(onDismiss = { pickRoute = false }, onBuild = { pickRoute = false; onBuildRoute() })
        PhotoConfirmSheet(capture)

        if (showQr && room != null) InviteDialog(room.code, room.name, onCopied = { copied = true }, onDismiss = { showQr = false })
        confirmLead?.let { e ->
            ConfirmDialog(
                "Hand over the lead?", "${e.name} becomes the convoy leader. You stay in the convoy.", "Hand over",
                onConfirm = { vm.handOver(e.id) }, onDismiss = { confirmLead = null },
            )
        }
        confirmRemove?.let { e ->
            ConfirmDialog(
                "Remove ${e.name}?", "They are put out of the convoy and cannot rejoin with the code.", "Remove",
                onConfirm = { vm.remove(e.id) }, onDismiss = { confirmRemove = null }, danger = true,
            )
        }
    }
}

/** A right-rail button with a short caption, so every action reads without guessing at the icon. */
@Composable
private fun RailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = Cv.colors.ink,
    edge: androidx.compose.ui.graphics.Color = Cv.colors.lineStrong,
) {
    val c = Cv.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CvIconButton(icon, description, onClick, tint = tint, edge = edge)
        Box(Modifier.cutPanel(c.glass, androidx.compose.ui.graphics.Color.Transparent, CutSShape).padding(horizontal = 4.dp, vertical = 1.dp)) {
            CvText(label, Cv.type.label.copy(fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 0.06.sp), c.ink)
        }
    }
}

@Composable
private fun PendingDestinationCard(p: PendingDest, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    val c = Cv.colors
    var label by rememberSaveable(p.lat, p.lng) { mutableStateOf("Dropped pin") }
    Column(
        Modifier.fillMaxWidth().cutPanel(c.glass, c.signal, CutMShape).padding(Space.s3),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Eyebrow("SET DESTINATION", color = c.signal)
        CvText("%.4f, %.4f".format(java.util.Locale.ROOT, p.lat, p.lng), Cv.type.telemetry, c.inkMuted)
        CvTextField(label, { label = it.take(80) }, "Name", placeholder = "Dropped pin")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            CvButton("Cancel", onCancel, Modifier.weight(1f), CvButtonKind.Ghost, compact = true)
            CvButton("Confirm", { onConfirm(label) }, Modifier.weight(1f), compact = true)
        }
    }
}

/**
 * The always-visible top of the in-convoy sheet, one compact line: name, ETA, who is online, the code, and chat with
 * its unread count. Pulled up, it adds sharing, QR, recording, a photo and the Ride HUD.
 */
@Composable
private fun ConvoyHeader(
    ui: ConvoyUi,
    copied: Boolean,
    expanded: Boolean,
    etaMs: Long?,
    recActive: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onQr: () -> Unit,
    onChat: () -> Unit,
    onRecord: () -> Unit,
    onPhoto: () -> Unit,
    onRide: () -> Unit,
) {
    val c = Cv.colors
    val room = ui.room ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CvText(room.name.ifBlank { "Convoy" }, Cv.type.title, c.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow(listOfNotNull(etaMs?.let { "ETA ${Fmt.clockOfDay(it)}" }, "${ui.onlineCount} ONLINE").joinToString(" · "))
                    Box(
                        Modifier
                            .cutPanel(c.surface200, if (copied) c.go else c.lineStrong, CutSShape)
                            .clickable(role = Role.Button, onClick = onCopy)
                            .semantics { contentDescription = "Copy invite code ${room.code}" }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) { CvText(if (copied) "COPIED" else room.code, Cv.type.telemetry, if (copied) c.go else c.signal, maxLines = 1) }
                }
            }
            Box {
                CvIconButton(CvIcons.Comment, "Open chat", onChat, tint = if (ui.unread > 0) c.signal else c.ink, edge = if (ui.unread > 0) c.signal else c.lineStrong)
                if (ui.unread > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).size(18.dp).cutPanel(c.pulse, c.pulse, CutSShape),
                        contentAlignment = Alignment.Center,
                    ) { CvText(ui.unread.coerceAtMost(9).toString() + if (ui.unread > 9) "+" else "", Cv.type.label.copy(fontSize = 9.sp, lineHeight = 10.sp), c.pulseInk) }
                }
            }
        }
        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                CvIconButton(CvIcons.Share, "Share invite", onShare, size = 40.dp)
                CvIconButton(CvIcons.Qr, "Show QR code", onQr, size = 40.dp)
                CvIconButton(CvIcons.Rec, if (recActive) "Recording controls" else "Record a ride", onRecord, size = 40.dp, tint = if (recActive) c.pulse else c.ink, edge = if (recActive) c.pulse else c.lineStrong)
                CvIconButton(CvIcons.Camera, "Take a photo", onPhoto, size = 40.dp)
                Spacer(Modifier.weight(1f))
                CvChipToggle("Ride HUD", false, onRide)
            }
        }
    }
}

/** Everyone else at a glance under the turn card: initials and how far, STOP when they have stopped. */
@Composable
private fun MemberChips(others: List<app.convoyage.data.RosterEntry>) {
    if (others.isEmpty()) return
    val c = Cv.colors
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        others.forEach { e ->
            val tone = when (e.state) {
                MemberState.Stopped -> c.pulse
                MemberState.Offline -> c.inkMuted
                MemberState.Lead -> c.lead
                MemberState.Online -> c.go
            }
            val what = when {
                e.state == MemberState.Stopped -> "STOP"
                e.state == MemberState.Offline -> "OFF"
                e.distanceM != null -> Geo.formatDistance(e.distanceM).uppercase()
                else -> "--"
            }
            Row(
                Modifier.cutPanel(c.surface100, tone, CutSShape).padding(horizontal = Space.s2, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                CvText(e.initials, Cv.type.label, tone, maxLines = 1)
                CvText(what, Cv.type.telemetry, c.ink, maxLines = 1)
            }
        }
    }
}

/** Sheet content before there is a convoy: choose Start or Join; the form opens at half height. */
@Composable
private fun PreStartSheet(
    expanded: Boolean,
    tab: Int,
    onTab: (Int) -> Unit,
    online: Boolean,
    defaultName: String,
    onCreate: (String, Double?) -> Unit,
    onJoin: (String) -> Unit,
    onClose: () -> Unit,
) {
    val c = Cv.colors
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var code by rememberSaveable { mutableStateOf("") }
    var regroup by rememberSaveable { mutableStateOf(false) }
    var separationKm by rememberSaveable { mutableStateOf(5) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
            CvIconButton(CvIcons.Back, "Back", onClose, size = 40.dp)
            Column(Modifier.weight(1f)) {
                CvText("Convoy", Cv.type.title, c.ink)
                Eyebrow(if (online) "CONNECTED" else "WAITING FOR THE SERVER")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            CvButton("Start", { onTab(0) }, Modifier.weight(1f), if (tab == 0 && expanded) CvButtonKind.Primary else CvButtonKind.Ghost, icon = CvIcons.Plus)
            CvButton("Join", { onTab(1) }, Modifier.weight(1f), if (tab == 1 && expanded) CvButtonKind.Primary else CvButtonKind.Ghost, icon = CvIcons.Convoy)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                if (tab == 0) {
                    CvTextField(
                        name, { name = it.take(Names.NAME_MAX) }, "Convoy name", placeholder = "Name your convoy",
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = Space.s3)) {
                            CvText("Automatic regroup", Cv.type.bodyStrong, c.ink)
                            CvText("Call a regroup stop when the convoy spreads out.", Cv.type.bodyS, c.inkMuted)
                        }
                        CvChipToggle(if (regroup) "On" else "Off", regroup, { regroup = !regroup })
                    }
                    if (regroup) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                            Eyebrow("WHEN APART")
                            listOf(2, 5, 10).forEach { km -> CvChipToggle("$km km", separationKm == km, { separationKm = km }) }
                        }
                    }
                    CvButton("Start convoy", { onCreate(name, if (regroup) separationKm * 1000.0 else null) }, Modifier.fillMaxWidth(), enabled = online)
                } else {
                    CvTextField(
                        code, { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(12) }, "Invite code",
                        placeholder = "K7QXM2PR", mono = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    )
                    CvButton("Join convoy", { onJoin(code) }, Modifier.fillMaxWidth(), enabled = online && code.length >= 8)
                }
            }
        }
    }
}
