package app.convoyage.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.convoyage.data.Fix
import app.convoyage.data.ImageSource
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.CutMShape
import app.convoyage.design.Cv
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvChipToggle
import app.convoyage.design.CvIcons
import app.convoyage.design.CvSheet
import app.convoyage.design.CvText
import app.convoyage.design.Eyebrow
import app.convoyage.design.Space
import kotlinx.coroutines.launch
import java.io.File

/**
 * The shutter. Opens the system camera (`ActivityResultContracts.TakePicture`, no CameraX) with a cache file as
 * the target; [pending] is the finished shot waiting for "Add to ride" / "Discard".
 */
@Stable
class RidePhotoCapture internal constructor(
    private val launch: () -> Unit,
    pendingState: androidx.compose.runtime.MutableState<String?>,
    locationState: androidx.compose.runtime.MutableState<Boolean>,
    messageState: androidx.compose.runtime.MutableState<String?>,
) {
    var pending: String? by pendingState
    var includeLocation: Boolean by locationState
    var message: String? by messageState

    fun shoot(recording: Boolean) {
        if (!recording) {
            message = "Start recording to attach photos to your ride."
            return
        }
        message = null
        launch()
    }
}

@Composable
fun rememberRidePhotoCapture(): RidePhotoCapture {
    val context = LocalContext.current
    val target = rememberSaveable { mutableStateOf<String?>(null) }
    val pending = rememberSaveable { mutableStateOf<String?>(null) }
    val includeLocation = rememberSaveable { mutableStateOf(true) } // default on during a ride
    val message = remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = target.value
        target.value = null
        val file = path?.let(::File)
        if (ok && file != null && file.length() > 0) {
            pending.value = path
            includeLocation.value = true
        } else {
            file?.delete()
        }
    }

    return remember(launcher) {
        RidePhotoCapture(
            launch = {
                val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
                target.value = file.path
                val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                try {
                    launcher.launch(uri)
                } catch (_: ActivityNotFoundException) {
                    target.value = null
                    message.value = "No camera app found on this phone."
                }
            },
            pendingState = pending, locationState = includeLocation, messageState = message,
        )
    }
}

/** The round shutter button on a [CvIconButton] footprint is built by the callers; this is the shared sheet that follows a shot. */
@Composable
fun PhotoConfirmSheet(capture: RidePhotoCapture, modifier: Modifier = Modifier) {
    val path = capture.pending ?: return
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val c = Cv.colors
    var busy by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickableNoRipple {}) {
        CvSheet(Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().padding(bottom = Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                Eyebrow("PHOTO · ADD TO THIS RIDE", color = c.signal)
                CvImage(
                    ImageSource.Local(File(path)), Modifier.fillMaxWidth().height(200.dp).clip(CutMShape), maxEdgePx = 900,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        CvText("Include location", Cv.type.bodyStrong, c.ink)
                        CvText(
                            if (capture.includeLocation) "Pinned on the route map" else "Gallery only, no pin",
                            Cv.type.bodyS, c.inkMuted,
                        )
                    }
                    CvChipToggle(if (capture.includeLocation) "On" else "Off", capture.includeLocation, { capture.includeLocation = !capture.includeLocation })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    CvButton(
                        "Discard", { File(path).delete(); capture.pending = null }, Modifier.weight(1f), CvButtonKind.Ghost, enabled = !busy,
                    )
                    CvButton(
                        if (busy) "Adding..." else "Add to ride",
                        {
                            busy = true
                            scope.launch {
                                val loc = if (capture.includeLocation) currentPhotoFix(container) else null
                                container.recorder.addPhoto(File(path), loc)
                                capture.pending = null
                                busy = false
                            }
                        },
                        Modifier.weight(1f), icon = CvIcons.Camera, enabled = !busy,
                    )
                }
            }
        }
    }
}

/** Where a photo taken now was taken: the live fix if it is recent, else the last point of the trace. */
private fun currentPhotoFix(container: app.convoyage.AppContainer): Fix? {
    val now = System.currentTimeMillis()
    container.tracker.fix.value?.takeIf { now - it.timeMs < 60_000 }?.let { return it }
    return container.recorder.points().lastOrNull()?.let { Fix(it.lat, it.lng, null, it.speedMps, it.accuracyM, it.timeMs, it.altM) }
}

/** The note shown when the shutter is pressed with nothing recording, or the camera is missing. */
@Composable
fun PhotoHint(capture: RidePhotoCapture, modifier: Modifier = Modifier) {
    val msg = capture.message ?: return
    Banner(BannerTone.Hazard, msg, modifier, icon = CvIcons.Camera, actionLabel = "OK", onAction = { capture.message = null })
}
