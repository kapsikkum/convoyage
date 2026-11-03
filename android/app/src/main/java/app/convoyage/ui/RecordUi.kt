package app.convoyage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.convoyage.api.Vehicle
import app.convoyage.data.RecorderState
import app.convoyage.data.RideStage
import app.convoyage.data.StartResult
import app.convoyage.data.UploadState
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.CutLShape
import app.convoyage.design.CutMShape
import app.convoyage.design.CutSShape
import app.convoyage.design.Cv
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvCard
import app.convoyage.design.CvChipToggle
import app.convoyage.design.CvIcon
import app.convoyage.design.CvIcons
import app.convoyage.design.CvSheet
import app.convoyage.design.CvText
import app.convoyage.design.Eyebrow
import app.convoyage.design.Readout
import app.convoyage.design.Space
import app.convoyage.design.cutPanel

/** Opens the ride's recap screen (local ride). Provided by [AppRoot]; Home, Convoy and Ride use it after Stop. */
val LocalOpenRecap = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

/** What to call a ride's upload in one short line. */
fun uploadLine(u: UploadState, stage: RideStage?): String = when {
    stage == RideStage.Uploaded || u == UploadState.Done -> "SAVED TO YOUR ACCOUNT"
    u == UploadState.Uploading -> "UPLOADING..."
    u is UploadState.Failed -> if (u.retryable) "WAITING FOR A CONNECTION · WILL RETRY" else "UPLOAD FAILED"
    else -> "READY TO UPLOAD"
}

/**
 * The recorder's bottom sheet. Idle: choose the vehicle and start. Recording or paused: pause/resume and stop.
 * [onOpenRecap] is called after Stop, so the caller can navigate to the recap.
 */
@Composable
fun RecordSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, bottomInset: androidx.compose.ui.unit.Dp = 0.dp) {
    val container = LocalContainer.current
    val openRecap = LocalOpenRecap.current
    val rec by container.recorder.state.collectAsStateWithLifecycle()
    val garage by container.garage.state.collectAsStateWithLifecycle()
    val convoy by container.convoy.state.collectAsStateWithLifecycle()
    val auto by container.session.autoRecordConvoy.collectAsStateWithLifecycle()
    val permission = rememberLocationPermission()
    val c = Cv.colors

    val owned = garage.owned
    var vehicleId by remember(owned.size) { mutableStateOf(garage.primary?.id) }
    var withConvoy by remember { mutableStateOf(true) }
    var busyNote by remember { mutableStateOf<String?>(null) }
    val convoyCode = convoy.activeCode

    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickableNoRipple(onDismiss))
        CvSheet(Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset).clickableNoRipple {}) {
            Column(Modifier.fillMaxWidth().padding(bottom = Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                when {
                    rec.isActive -> ActiveBody(rec, onStop = {
                        container.recorder.stop()
                        onDismiss()
                        openRecap()
                    })
                    rec.stage == RideStage.Stopped -> {
                        Eyebrow("RIDE WAITING TO UPLOAD", color = c.hazard)
                        CvText("Your last ride is not on the server yet.", Cv.type.body, c.inkMuted)
                        CvButton("Open recap", { onDismiss(); openRecap() }, Modifier.fillMaxWidth())
                    }
                    else -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                CvText("Record a ride", Cv.type.title, c.ink)
                                Eyebrow("GPS TRACE · KEEP THE APP OPEN")
                            }
                            CvIcon(CvIcons.Rec, c.pulse, size = 28.dp)
                        }
                        if (!permission.granted) {
                            Banner(
                                BannerTone.Hazard, "Location is off", sub = "Allow location to record your route.",
                                icon = CvIcons.Locate, actionLabel = "Allow", onAction = permission.request,
                            )
                        }
                        Eyebrow("VEHICLE")
                        if (owned.isEmpty()) {
                            CvText(
                                if (garage.loaded) "No vehicle in your garage yet. You can still record without one." else "Loading your garage...",
                                Cv.type.bodyS, c.inkMuted,
                            )
                        } else {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                                owned.forEach { v ->
                                    CvChipToggle(
                                        (if (v.isPrimary) "★ " else "") + v.displayName, vehicleId == v.id,
                                        { vehicleId = v.id },
                                    )
                                }
                                CvChipToggle("No vehicle", vehicleId == null, { vehicleId = null })
                            }
                        }
                        if (convoyCode != null) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    CvText("Part of convoy", Cv.type.bodyStrong, c.ink)
                                    CvText("Code $convoyCode", Cv.type.telemetry, c.signal)
                                }
                                CvChipToggle(if (withConvoy) "Yes" else "No", withConvoy, { withConvoy = !withConvoy })
                            }
                        }
                        AutoRecordRow(auto) { container.session.setAutoRecordConvoy(it) }
                        busyNote?.let { Banner(BannerTone.Pulse, it) }
                        CvButton(
                            "Start recording",
                            {
                                val v = owned.firstOrNull { it.id == vehicleId }
                                val r = container.recorder.start(
                                    v?.id, v?.displayName, if (withConvoy) convoyCode else null, container.tracker.fix.value,
                                    title = Fmt.defaultTitle(java.time.LocalTime.now().hour),
                                )
                                if (r == StartResult.Busy) busyNote = "Finish or discard your last ride first." else onDismiss()
                            },
                            Modifier.fillMaxWidth(), icon = CvIcons.Rec, enabled = permission.granted,
                        )
                    }
                }
                CvButton("Close", onDismiss, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
            }
        }
    }
}

@Composable
private fun AutoRecordRow(auto: Boolean, onChange: (Boolean) -> Unit) {
    val c = Cv.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Space.s3)) {
            CvText("Record convoy rides automatically", Cv.type.bodyStrong, c.ink)
            CvText("Starts when you start or join a convoy, stops when you leave it.", Cv.type.bodyS, c.inkMuted)
        }
        CvChipToggle(if (auto) "On" else "Off", auto, { onChange(!auto) })
    }
}

@Composable
private fun ActiveBody(rec: RecorderState, onStop: () -> Unit) {
    val container = LocalContainer.current
    val c = Cv.colors
    val auto by container.session.autoRecordConvoy.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Eyebrow(if (rec.isPaused) "PAUSED" else "RECORDING", color = if (rec.isPaused) c.hazard else c.pulse)
            CvText(rec.meta?.title?.ifBlank { null } ?: "Ride", Cv.type.title, c.ink, maxLines = 1)
        }
        CvText(Fmt.clock(rec.elapsedS), Cv.type.telemetryL, c.ink)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        Readout("Distance", Fmt.km(rec.stats.distanceM), Modifier.weight(1f), unit = "km")
        Readout("Max", Fmt.kmh(rec.stats.maxSpeedMps), Modifier.weight(1f), unit = "km/h")
        Readout("Photos", "${rec.meta?.photos?.size ?: 0}", Modifier.weight(1f))
    }
    if (rec.meta?.convoyCode != null) Eyebrow("CONVOY ${rec.meta.convoyCode}", color = c.signal)
    AutoRecordRow(auto) { container.session.setAutoRecordConvoy(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        if (rec.isPaused) {
            CvButton("Resume", { container.recorder.resume() }, Modifier.weight(1f), icon = CvIcons.Play)
        } else {
            CvButton("Pause", { container.recorder.pause() }, Modifier.weight(1f), CvButtonKind.Ghost, icon = CvIcons.Pause)
        }
        CvButton("Stop", onStop, Modifier.weight(1f), CvButtonKind.Alert, icon = CvIcons.Stop)
    }
}

/** Home's recorder card: start, live numbers with pause/stop, or the saved ride waiting for its recap. */
@Composable
fun RecordCard(onOpenSheet: () -> Unit) {
    val container = LocalContainer.current
    val openRecap = LocalOpenRecap.current
    val rec by container.recorder.state.collectAsStateWithLifecycle()
    val garage by container.garage.state.collectAsStateWithLifecycle()
    val c = Cv.colors

    when {
        rec.isActive -> CvCard(Modifier.fillMaxWidth(), edge = if (rec.isPaused) c.hazard else c.pulse, shape = CutMShape, onClick = onOpenSheet) {
            Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CvIcon(CvIcons.Rec, if (rec.isPaused) c.hazard else c.pulse, size = 16.dp)
                        Eyebrow(if (rec.isPaused) "PAUSED" else "RECORDING", color = if (rec.isPaused) c.hazard else c.pulse)
                    }
                    CvText(Fmt.clock(rec.elapsedS), Cv.type.telemetryL, c.ink)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Readout("Distance", Fmt.km(rec.stats.distanceM), Modifier.weight(1f), unit = "km")
                    Readout("Max", Fmt.kmh(rec.stats.maxSpeedMps), Modifier.weight(1f), unit = "km/h")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    if (rec.isPaused) CvButton("Resume", { container.recorder.resume() }, Modifier.weight(1f), icon = CvIcons.Play, compact = true)
                    else CvButton("Pause", { container.recorder.pause() }, Modifier.weight(1f), CvButtonKind.Ghost, icon = CvIcons.Pause, compact = true)
                    CvButton("Stop", { container.recorder.stop(); openRecap() }, Modifier.weight(1f), CvButtonKind.Alert, icon = CvIcons.Stop, compact = true)
                }
            }
        }

        rec.hasRecap -> CvCard(Modifier.fillMaxWidth(), edge = if (rec.upload is UploadState.Failed) c.hazard else c.go, shape = CutMShape, onClick = openRecap) {
            Row(Modifier.padding(Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                    Eyebrow("LAST RIDE · ${uploadLine(rec.upload, rec.stage)}", color = if (rec.upload is UploadState.Failed) c.hazard else c.go)
                    CvText("${Fmt.km(rec.stats.distanceM)} km · ${Fmt.clock(rec.stats.durationS)}", Cv.type.title, c.ink)
                    CvText(rec.meta?.title?.ifBlank { null } ?: "Ride recap", Cv.type.bodyS, c.inkMuted, maxLines = 1)
                }
                CvIcon(CvIcons.Chevron, c.inkMuted)
            }
        }

        else -> CvCard(Modifier.fillMaxWidth(), edge = c.lineStrong, shape = CutMShape, onClick = onOpenSheet) {
            Row(Modifier.padding(Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).cutPanel(c.surface300, c.pulse, CutSShape), contentAlignment = Alignment.Center) {
                    CvIcon(CvIcons.Rec, c.pulse, size = 28.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                    Eyebrow("RIDE RECORDER")
                    CvText("Record a ride", Cv.type.title, c.ink)
                    CvText(garage.primary?.let { "On the ${it.displayName}" } ?: "Distance, speed, route and photos", Cv.type.bodyS, c.inkMuted, maxLines = 1)
                }
                CvIcon(CvIcons.Chevron, c.inkMuted)
            }
        }
    }
}

/**
 * Ride HUD strip, 72 dp tall: record or pause/resume, a live readout with stop, and the shutter.
 * Idle shows one wide Record button (which opens the sheet to pick the vehicle).
 */
@Composable
fun RecordStrip(onOpenSheet: () -> Unit, onShutter: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val openRecap = LocalOpenRecap.current
    val rec by container.recorder.state.collectAsStateWithLifecycle()
    val c = Cv.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
        if (rec.isActive) {
            HudSquare(
                if (rec.isPaused) CvIcons.Play else CvIcons.Pause, if (rec.isPaused) "Resume recording" else "Pause recording",
                tint = c.ink, edge = c.lineStrong, fill = c.surface200,
            ) { if (rec.isPaused) container.recorder.resume() else container.recorder.pause() }
            Row(
                Modifier.weight(1f).height(Space.targetRide).cutPanel(c.surface200, if (rec.isPaused) c.hazard else c.pulse, CutSShape).clickableNoRipple(onOpenSheet).padding(horizontal = Space.s3),
                horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically,
            ) {
                CvIcon(CvIcons.Rec, if (rec.isPaused) c.hazard else c.pulse, size = 18.dp)
                Column {
                    CvText(Fmt.clock(rec.elapsedS), Cv.type.telemetryL.copy(fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)), c.ink, maxLines = 1)
                    CvText("${Fmt.km(rec.stats.distanceM)} KM", Cv.type.label, c.inkMuted, maxLines = 1)
                }
            }
            HudSquare(CvIcons.Stop, "Stop recording", tint = c.pulseInk, edge = c.pulse, fill = c.pulse) { container.recorder.stop(); openRecap() }
        } else if (rec.hasRecap) {
            CvButton("Last ride recap", openRecap, Modifier.weight(1f), CvButtonKind.Ride, icon = CvIcons.Route)
        } else {
            CvButton("Record ride", onOpenSheet, Modifier.weight(1f), CvButtonKind.Ride, icon = CvIcons.Rec)
        }
        HudSquare(CvIcons.Camera, "Take a photo", tint = c.signal, edge = c.signal, fill = c.surface200, onClick = onShutter)
    }
}

@Composable
private fun HudSquare(icon: ImageVector, description: String, tint: Color, edge: Color, fill: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(Space.targetRide)
            .cutPanel(fill, edge, CutLShape)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { CvIcon(icon, tint, size = 32.dp) }
}
