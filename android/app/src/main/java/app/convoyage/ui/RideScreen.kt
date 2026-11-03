package app.convoyage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.foundation.background
import app.convoyage.data.Geo
import app.convoyage.design.CutLShape
import app.convoyage.design.CutSShape
import app.convoyage.design.Cv
import app.convoyage.design.CvIconButton
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.Eyebrow
import app.convoyage.design.Readout
import app.convoyage.design.RideTile
import app.convoyage.design.Space
import app.convoyage.design.CvIcon
import app.convoyage.design.TileTone
import app.convoyage.design.PushToTalk
import app.convoyage.design.cutPanel
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp

/**
 * Motorcycle HUD: hero speed, lean placeholder, 2x2 tiles (>=72dp targets), big PTT.
 * Same data as the Convoy screen, nothing to read twice: glanceable and gloves-friendly.
 */
@Composable
fun RideScreen(onExit: () -> Unit, onReport: () -> Unit) {
    val container = LocalContainer.current
    val vm: ConvoyViewModel = viewModel(factory = viewModelFactory { initializer { ConvoyViewModel(container) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    val permission = rememberLocationPermission()
    TrackWhileVisible(permission)
    val c = Cv.colors

    val speed = ConvoyViewModel.speedKmh(ui.fix)
    val toLeader = vm.metersToLeader(ui)
    val last = ui.lastRider

    Column(
        Modifier
            .fillMaxSize()
            .screenGutters()
            .navigationBarsPadding()
            .padding(top = Space.s2, bottom = Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
            CvIconButton(CvIcons.Back, "Exit Ride mode", onExit, fill = c.surface200, size = Space.targetRide)
            Row(
                Modifier
                    .weight(1f)
                    .height(Space.targetRide)
                    .cutPanel(c.surface200, if (ui.room != null) c.go else c.lineStrong, CutSShape)
                    .padding(horizontal = Space.s3),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CvIcon(CvIcons.Convoy, if (ui.room != null) c.go else c.inkMuted)
                Column {
                    CvText(ui.room?.name?.ifBlank { "Convoy" } ?: "No convoy", Cv.type.bodyStrong, c.ink, maxLines = 1)
                    CvText(if (ui.room != null) "${ui.onlineCount} online" else "Open Convoy to start or join", Cv.type.bodyS, c.inkMuted, maxLines = 1)
                }
            }
        }

        // Destination / next-step card. TODO(nav): turn-by-turn instructions go here.
        Row(
            Modifier.fillMaxWidth().cutPanel(c.surface100, c.lineStrong, CutLShape).padding(horizontal = Space.s4, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            CvIcon(CvIcons.Navigate, c.signal, size = 32.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val dest = ui.room?.destination
                val dist = if (dest != null && ui.fix != null) Geo.formatDistance(Geo.distanceM(ui.fix!!.lat, ui.fix!!.lng, dest.lat, dest.lng)).uppercase() else "--"
                CvText(dist, Cv.type.telemetryL, c.signal)
                CvText(dest?.label?.ifBlank { null } ?: "No destination set", Cv.type.body, c.inkMuted, maxLines = 1)
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = Space.s2), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Readout("Speed", "%3d".format(speed).trim(), unit = "km/h", valueStyle = Cv.type.telemetryXl, valueColor = c.signal)
            // TODO(lean): lean angle needs the rotation-vector sensor; placeholder meter until then.
            Column(Modifier.width(96.dp).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Readout("Lean", "--", unit = "°")
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CutSShape).background(c.surface300, CutSShape))
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                RideTile(
                    "To leader", Modifier.weight(1f),
                    value = toLeader?.let { Geo.formatDistance(it).substringBefore(' ') } ?: "--",
                    unit = toLeader?.let { Geo.formatDistance(it).substringAfter(' ') },
                    tone = TileTone.Signal,
                )
                RideTile(
                    "Last rider", Modifier.weight(1f),
                    value = last?.distanceM?.let { Geo.formatDistance(it).substringBefore(' ') } ?: "--",
                    unit = last?.distanceM?.let { Geo.formatDistance(it).substringAfter(' ') },
                    valueColor = if (last != null) c.pulse else null,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                RideTile("Report", Modifier.weight(1f), icon = CvIcons.Alert, tone = TileTone.Hazard, onClick = onReport)
                RideTile(
                    if (local.iAmStopping) "Moving again" else "I'm stopping", Modifier.weight(1f),
                    icon = CvIcons.Stop, tone = TileTone.Alert, onClick = vm::toggleStopping,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            // TODO(eta): needs routing.
            Readout("ETA", "--:--", Modifier.width(96.dp))
            PushToTalk(local.ptt, size = 128.dp, onPress = vm::pttDown, onRelease = vm::pttUp)
            Readout("Heading", ui.fix?.headingDeg?.let { "%03d°".format(it.toInt()) } ?: "---", Modifier.width(96.dp), align = Alignment.End)
        }
    }
}
