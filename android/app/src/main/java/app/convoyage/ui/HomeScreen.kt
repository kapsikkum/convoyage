package app.convoyage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.convoyage.AppContainer
import app.convoyage.data.Geo
import app.convoyage.data.Link
import app.convoyage.data.Roster
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.CutLShape
import app.convoyage.design.CutMShape
import app.convoyage.design.Cv
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
import app.convoyage.design.gridBackdrop
import app.convoyage.protocol.Room
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import java.time.LocalDateTime
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
fun HomeScreen(onOpenConvoy: (join: Boolean) -> Unit, onOpenNavigation: () -> Unit, onOpenGarage: () -> Unit) {
    val container = LocalContainer.current
    val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val c = Cv.colors
    val navState by container.nav.state.collectAsStateWithLifecycle()

    var showRecord by remember { mutableStateOf(false) }
    val garage by container.garage.state.collectAsStateWithLifecycle()
    val primary = garage.primary

    val now = remember { LocalDateTime.now() }
    val greeting = when (now.hour) { in 5..11 -> "Morning"; in 12..17 -> "Afternoon"; else -> "Evening" }
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
            CvAvatar(Roster.initials(ui.name), null)
        }

        LiveConvoyCard(ui, eta = if (navState.isOn) navState.etaMs?.let { Fmt.clockOfDay(it) } ?: "--:--" else "--:--", onClick = { onOpenConvoy(false) })

        RecordCard(onOpenSheet = { showRecord = true })

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Three across at 360 to 390dp: compact padding, and the label shrinks rather than being cut off.
            CvButton(if (ui.room != null) "Convoy" else "Start", { onOpenConvoy(false) }, Modifier.weight(1f), CvButtonKind.Primary, compact = true)
            CvButton("Join", { onOpenConvoy(true) }, Modifier.weight(1f), CvButtonKind.Ghost, compact = true)
            CvButton(if (navState.isOn) "Navigating" else "Navigate", onOpenNavigation, Modifier.weight(1f), CvButtonKind.Ghost, icon = CvIcons.Navigate, compact = true)
        }

        Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Eyebrow("EVENTS")
            }
            CvCard(Modifier.fillMaxWidth(), edge = c.line, shape = app.convoyage.design.CutSShape) {
                Row(Modifier.padding(Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
                    CvIcon(CvIcons.Route, c.inkMuted)
                    Column {
                        CvText("No upcoming cruises", Cv.type.bodyStrong, c.ink)
                        CvText("Events from the server show up here once cruises exist.", Cv.type.bodyS, c.inkMuted)
                    }
                }
            }
        }

        // Garage card: the primary vehicle. The 3D renderer is still a placeholder, see VehicleViewport.
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
                CvIcon(primary?.let { kindIcon(it.kind) } ?: CvIcons.Cube, primary?.build?.paint?.let(::parsePaint) ?: c.signal, size = 40.dp)
            }
        }
    }
    if (showRecord) RecordSheet(onDismiss = { showRecord = false }, bottomInset = TabBarClearance)
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
