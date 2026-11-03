package app.convoyage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.convoyage.AppContainer
import app.convoyage.api.HazardKinds
import app.convoyage.data.AuthKind
import app.convoyage.data.ReportResult
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.Cv
import app.convoyage.design.CvIconButton
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.Eyebrow
import app.convoyage.design.RideTile
import app.convoyage.design.Space
import app.convoyage.design.TileTone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One tile on the report screen: what the server calls it (kind + subkind) and what we show. */
data class HazardPreset(val label: String, val kind: String, val subkind: String, val icon: ImageVector, val tone: TileTone)

object HazardPresets {
    // subkind must match ^[a-z0-9_]{0,24}$ (protocol/openapi.json).
    val all = listOf(
        HazardPreset("Debris", HazardKinds.HAZARD, "debris", CvIcons.Alert, TileTone.Hazard),
        HazardPreset("Gravel", HazardKinds.HAZARD, "gravel", CvIcons.Alert, TileTone.Hazard),
        HazardPreset("Oil", HazardKinds.HAZARD, "oil", CvIcons.Alert, TileTone.Hazard),
        HazardPreset("Wet / ice", HazardKinds.HAZARD, "wet_ice", CvIcons.Alert, TileTone.Hazard),
        HazardPreset("Police", HazardKinds.POLICE, "", CvIcons.Alert, TileTone.Police),
        HazardPreset("Speed camera", HazardKinds.POLICE, "speed_camera", CvIcons.Camera, TileTone.Police),
        HazardPreset("Crash", HazardKinds.CRASH, "", CvIcons.Stop, TileTone.Alert),
        HazardPreset("Road works", HazardKinds.WORKS, "", CvIcons.Route, TileTone.Hazard),
    )
}

data class ReportUi(
    /** Label of the tile being sent. */
    val sending: String? = null,
    val sent: String? = null,
    val error: String? = null,
)

class ReportViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(ReportUi())
    val ui: StateFlow<ReportUi> = _ui.asStateFlow()

    /** Dev sign-in has a socket identity but no account, so the REST API (and hazards) refuse it. */
    val hasAccount: Boolean get() = container.session.kind.value == AuthKind.Account

    fun report(preset: HazardPreset) {
        if (_ui.value.sending != null) return
        val fix = container.tracker.fix.value
        if (fix == null || System.currentTimeMillis() - fix.timeMs > 120_000) {
            _ui.value = ReportUi(error = "Waiting for a location fix. Check that location is on, then try again.")
            return
        }
        _ui.value = ReportUi(sending = preset.label)
        viewModelScope.launch {
            _ui.value = when (val r = container.hazards.report(preset.kind, preset.subkind, fix.lat, fix.lng, fix.headingDeg)) {
                is ReportResult.Sent -> ReportUi(sent = preset.label)
                is ReportResult.Failed -> ReportUi(error = r.message)
            }
        }
    }
}

/**
 * Hazard report picker. A tap sends `POST /v1/hazards` with the current location and heading; convoy members
 * get it live over the socket and everyone nearby sees it on the map for 30 minutes.
 */
@Composable
fun ReportScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val vm: ReportViewModel = viewModel(factory = viewModelFactory { initializer { ReportViewModel(container) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val permission = rememberLocationPermission()
    TrackWhileVisible(permission)
    val c = Cv.colors

    // Back to the map a moment after a successful report.
    LaunchedEffect(ui.sent) {
        if (ui.sent != null) { delay(1400); onBack() }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).screenGutters().navigationBarsPadding().padding(top = Space.s2, bottom = Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
            CvIconButton(CvIcons.Back, "Back", onBack, fill = c.surface200)
            Column { Eyebrow("HAZARD"); CvText("Report", Cv.type.title, c.ink) }
        }
        if (!vm.hasAccount) {
            Banner(BannerTone.Hazard, "Sign in with your email to report", sub = "Dev sign-in has no account behind it, so the server cannot take reports.", icon = CvIcons.Lock)
        }
        if (!permission.granted) {
            Banner(BannerTone.Hazard, "Location is off", sub = "Reports are pinned to where you are.", icon = CvIcons.Locate, actionLabel = "Allow", onAction = permission.request)
        }
        ui.sending?.let { Banner(BannerTone.Hazard, "Sending $it...", icon = CvIcons.Alert) }
        ui.sent?.let { Banner(BannerTone.Go, "$it reported", sub = "Visible to nearby drivers for 30 minutes.", icon = CvIcons.Alert) }
        ui.error?.let { Banner(BannerTone.Pulse, it, icon = CvIcons.Alert) }
        HazardPresets.all.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                row.forEach { h -> RideTile(h.label, Modifier.weight(1f), icon = h.icon, tone = h.tone, onClick = { vm.report(h) }) }
            }
        }
    }
}
