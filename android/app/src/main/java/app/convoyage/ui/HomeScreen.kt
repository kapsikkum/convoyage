package app.convoyage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.convoyage.AppContainer
import app.convoyage.api.PsEvent
import app.convoyage.data.Geo
import app.convoyage.data.Link
import app.convoyage.data.Roster
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.CutLShape
import app.convoyage.design.CutMShape
import app.convoyage.design.CutSShape
import app.convoyage.design.Cv
import app.convoyage.api.badge
import app.convoyage.design.CvAvatar
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvCard
import app.convoyage.design.CvIcon
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.Eyebrow
import app.convoyage.design.Readout
import app.convoyage.design.Space
import app.convoyage.design.cutPanel
import app.convoyage.design.gridBackdrop
import app.convoyage.protocol.Room
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class HomeUi(
    val name: String = "",
    val link: Link = Link.Offline,
    val serverUrl: String = "",
    val room: Room? = null,
    val onlineCount: Int = 0,
    val memberCount: Int = 0,
    val spread: String = "--",
    val leaderName: String? = null,
)

class HomeViewModel(container: AppContainer) : ViewModel() {
    val ui: StateFlow<HomeUi> = combine(
        container.session.name, container.session.serverUrl, container.convoy.state,
    ) { name, url, convoy ->
        val room = convoy.active
        HomeUi(
            name = name.orEmpty(),
            link = convoy.link,
            serverUrl = url,
            room = room,
            onlineCount = room?.members?.count { it.online } ?: 0,
            memberCount = room?.members?.size ?: 0,
            spread = room?.let { Geo.formatDistance(Roster.spreadM(it)) } ?: "--",
            leaderName = room?.members?.firstOrNull { it.id == room.leader }?.username,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())
}

@Composable
fun HomeScreen(onOpenConvoy: (join: Boolean) -> Unit, onOpenNavigation: () -> Unit, onOpenGarage: () -> Unit, onOpenRoutes: () -> Unit = {}, onOpenRide: (String) -> Unit = {}) {
    val container = LocalContainer.current
    val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val c = Cv.colors
    val navState by container.nav.state.collectAsStateWithLifecycle()

    var showRecord by remember { mutableStateOf(false) }
    var openEvent by remember { mutableStateOf<PsEvent?>(null) }
    val garage by container.garage.state.collectAsStateWithLifecycle()
    val primary = garage.primary
    val me by container.account.me.collectAsStateWithLifecycle()

    // PhotoScout events for the EVENTS section: linked first, then the five soonest around the last fix.
    val layers by container.layers.state.collectAsStateWithLifecycle()
    val fix by container.tracker.fix.collectAsStateWithLifecycle()
    LifecycleStartEffect(Unit) {
        container.tracker.acquire()
        onStopOrDispose { container.tracker.release() }
    }
    LaunchedEffect(Unit) { container.layers.refreshStatus() }
    LaunchedEffect(layers.available, fix != null) {
        if (!layers.available) return@LaunchedEffect
        val here = fix ?: container.tracker.lastKnown() ?: return@LaunchedEffect
        container.layers.loadHomeEvents(here.lat, here.lng)
    }

    val now = remember { LocalDateTime.now() }
    val greeting = when (now.hour) { in 5..11 -> "Morning"; in 12..17 -> "Afternoon"; in 18..21 -> "Evening"; else -> "Late one" }
    val stamp = now.format(DateTimeFormatter.ofPattern("EEEE · h:mm a", Locale.ENGLISH)).uppercase()

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .screenGutters()
            .padding(top = Space.s6, bottom = TabBarClearance),
        verticalArrangement = Arrangement.spacedBy(Space.s6),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                Eyebrow(stamp)
                CvText("$greeting, ${ui.name.ifBlank { "driver" }}", Cv.type.displayL, c.ink, maxLines = 2)
            }
            CvAvatar(me?.avatarPath, garage.primary?.badge() ?: me?.primaryVehicle?.badge(), Roster.initials(ui.name))
        }

        // Home is the dashboard now; driving, convoys and recording live on the Drive tab.
        if (ui.room != null) LiveConvoyCard(ui, eta = if (navState.isOn) navState.etaMs?.let { Fmt.clockOfDay(it) } ?: "--:--" else "--:--", onClick = { onOpenConvoy(false) })

        DashboardCard(onOpenRide = onOpenRide)

        CvButton(if (navState.isOn) "Back to navigation" else "Drive", onOpenNavigation, Modifier.fillMaxWidth(), icon = CvIcons.Navigate)

        CvCard(Modifier.fillMaxWidth(), edge = c.line, shape = app.convoyage.design.CutSShape, onClick = onOpenRoutes) {
            Row(Modifier.padding(Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
                CvIcon(CvIcons.Route, c.signal)
                Column(Modifier.weight(1f)) {
                    Eyebrow("SAVED ROUTES")
                    CvText("Routes", Cv.type.bodyStrong, c.ink)
                    CvText("Draw a sprint or circuit, import from PhotoScout, lead it as a convoy.", Cv.type.bodyS, c.inkMuted)
                }
                CvIcon(CvIcons.Chevron, c.inkMuted)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Eyebrow("EVENTS")
            }
            when {
                layers.available -> {
                    if (layers.homeEvents.isEmpty()) {
                        EventsNotice("No upcoming events within 150 km.", "Meets and cruises from PhotoScout show up here.")
                    } else {
                        layers.homeEvents.forEach { e ->
                            UpcomingEventRow(e, onClick = { openEvent = e })
                        }
                    }
                }
                layers.configured -> ConnectEventsCard()
                else -> EventsNotice("Events come from PhotoScout", "This server does not have PhotoScout, so there are no events to show.")
            }
        }

        // Garage card: the primary vehicle. Its PS1 render is a static thumbnail.
        CvCard(Modifier.fillMaxWidth(), fill = androidx.compose.ui.graphics.Color.Transparent, shape = CutMShape, onClick = onOpenGarage) {
            Row(
                Modifier.gridBackdrop(c.line.copy(alpha = 0.4f)).padding(horizontal = Space.s4, vertical = Space.s3),
                horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                    Eyebrow(if (primary != null) "YOUR GARAGE · ${garage.owned.size}" else "YOUR GARAGE")
                    CvText(primary?.displayName ?: "Add your first vehicle", Cv.type.title, c.ink, maxLines = 1)
                    CvText(primary?.let(::buildSummary) ?: "Rides record on your primary vehicle", Cv.type.bodyS, c.inkMuted, maxLines = 1)
                }
                if (primary != null) Ps1Thumb(primary.ps1(), app.convoyage.render.ThumbSize.Card, Modifier.size(width = 96.dp, height = 64.dp), fallback = { CvIcon(kindIcon(primary.kind), primary.build.paint?.let(::parsePaint) ?: c.signal, size = 40.dp) })
                else CvIcon(CvIcons.Cube, c.signal, size = 40.dp)
            }
        }
    }
    if (showRecord) RecordSheet(onDismiss = { showRecord = false }, bottomInset = TabBarClearance)
    openEvent?.let { e ->
        EventSheet(
            e,
            onNavigate = { p -> openEvent = null; container.nav.navigateTo(p, container.tracker.lastKnown()); onOpenNavigation() },
            onJoinCruise = { openEvent = null; container.convoy.joinCruise(e.id); onOpenNavigation() },
            onDismiss = { openEvent = null },
            bottomInset = TabBarClearance,
        )
    }
    }
}

/** A PhotoScout event in the Home list: the weekday and day, its title, and where it is (or that it is a cruise). */
@Composable
private fun UpcomingEventRow(e: PsEvent, onClick: () -> Unit) {
    val c = Cv.colors
    val at = Fmt.parse(e.startTime)
    val zone = ZoneId.systemDefault()
    val day = at?.let { DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH).withZone(zone).format(it).uppercase() } ?: "--"
    val date = at?.let { DateTimeFormatter.ofPattern("dd", Locale.ENGLISH).withZone(zone).format(it) } ?: "--"
    val where = e.venue.ifBlank { e.address }
    val meta = when {
        e.cruise != null -> if (where.isBlank()) "Cruise" else "Cruise · $where"
        else -> where
    }
    CvCard(Modifier.fillMaxWidth(), edge = c.line, shape = CutSShape, onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Space.s3, vertical = Space.s2),
            horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.size(width = 48.dp, height = 48.dp).cutPanel(c.surface300, c.lineStrong, CutSShape),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Eyebrow(day)
                CvText(date, Cv.type.telemetry.copy(fontSize = 18.sp), c.ink)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CvText(e.title, Cv.type.bodyStrong, c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (meta.isNotBlank()) CvText(meta, Cv.type.bodyS, c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CvIcon(CvIcons.Chevron, c.inkMuted)
        }
    }
}

/** PhotoScout is on this server but not linked: say what linking does, with the button that does it. */
@Composable
private fun ConnectEventsCard() {
    val c = Cv.colors
    val (connect, error) = rememberPhotoScoutConnect()
    CvCard(Modifier.fillMaxWidth(), edge = c.line, shape = CutSShape) {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            CvText("Link PhotoScout to see meets and cruises near you.", Cv.type.body, c.ink)
            error?.let { Eyebrow(it.uppercase(), color = c.hazard) }
            CvButton("Connect PhotoScout", connect, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
        }
    }
}

/** The EVENTS section when there is nothing to list, and why. */
@Composable
private fun EventsNotice(title: String, sub: String) {
    val c = Cv.colors
    CvCard(Modifier.fillMaxWidth(), edge = c.line, shape = CutSShape) {
        Row(Modifier.padding(Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
            CvIcon(CvIcons.Route, c.inkMuted)
            Column {
                CvText(title, Cv.type.bodyStrong, c.ink)
                CvText(sub, Cv.type.bodyS, c.inkMuted)
            }
        }
    }
}

@Composable
private fun LiveConvoyCard(ui: HomeUi, eta: String, onClick: () -> Unit) {
    val c = Cv.colors
    val live = ui.room != null
    CvCard(
        Modifier.fillMaxWidth(),
        edge = if (live) c.signal else c.lineStrong,
        shape = CutMShape,
        onClick = onClick,
    ) {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(if (live) "LIVE CONVOY" else "NO LIVE CONVOY", color = if (live) c.signal else c.inkMuted)
                if (live) CvText(ui.room?.code.orEmpty(), Cv.type.telemetry, c.inkMuted)
                else LinkStatus(ui.link)
            }
            if (live) {
                val room = ui.room ?: return@Column
                Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                    CvText(room.name.ifBlank { "Convoy" }, Cv.type.title, c.ink)
                    CvText(
                        (room.destination?.label ?: "No destination yet") + (ui.leaderName?.let { " · $it leading" } ?: ""),
                        Cv.type.bodyS, c.inkMuted,
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = Space.s3), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Readout("Online", "${ui.onlineCount}/${ui.memberCount}", Modifier.weight(1f), valueStyle = Cv.type.telemetryL.copy(fontSize = 22.sp))
                    Readout("Spread", ui.spread.uppercase(), Modifier.weight(1f), valueStyle = Cv.type.telemetryL.copy(fontSize = 22.sp))
                    Readout("ETA", eta, Modifier.weight(1f), valueStyle = Cv.type.telemetryL.copy(fontSize = 22.sp))
                }
            } else {
                CvText("Start a convoy or join one with its eight-letter code.", Cv.type.body, c.inkMuted)
                Box(Modifier.height(0.dp))
                CvText("Server ${ui.serverUrl}", Cv.type.telemetry.copy(fontSize = 12.sp), c.inkMuted)
            }
        }
    }
}
