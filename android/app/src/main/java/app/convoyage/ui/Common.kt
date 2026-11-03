package app.convoyage.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.composed
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import app.convoyage.AppContainer
import app.convoyage.data.Link
import app.convoyage.design.CutSShape
import app.convoyage.design.Cv
import app.convoyage.design.CvText
import app.convoyage.design.Eyebrow
import app.convoyage.design.Space
import app.convoyage.design.cutPanel

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** Bottom room the floating tab bar needs (bar height + its margin). */
val TabBarClearance = 112.dp

/** Status-bar inset plus the 16dp screen gutter. */
fun Modifier.screenGutters(): Modifier = composed {
    windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Space.gutter)
}

/** Location permission state plus a launcher for the system dialog. */
class LocationPermission(val granted: Boolean, val request: () -> Unit)

@Composable
fun rememberLocationPermission(): LocationPermission {
    val tracker = LocalContainer.current.tracker
    var granted by remember { mutableStateOf(tracker.hasPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.any { it } || tracker.hasPermission()
        if (granted) tracker.restartIfNeeded()
    }
    LifecycleResumeEffect(Unit) {
        granted = tracker.hasPermission()
        onPauseOrDispose { }
    }
    return LocationPermission(granted) {
        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}

/**
 * While this composition is started: location updates run and our position is published (when in a convoy),
 * and the screen stays awake. This is the "while visible" rule; there is no background tracking yet.
 */
@Composable
fun TrackWhileVisible(permission: LocationPermission) {
    val container = LocalContainer.current
    val view = LocalView.current
    LifecycleStartEffect(permission.granted) {
        container.tracker.acquire()
        container.convoy.setVisible(true)
        view.keepScreenOn = true
        onStopOrDispose {
            view.keepScreenOn = false
            container.convoy.setVisible(false)
            container.tracker.release()
        }
    }
}

/** Small "ONLINE / CONNECTING / OFFLINE" status line with a dot. */
@Composable
fun LinkStatus(link: Link, modifier: Modifier = Modifier) {
    val c = Cv.colors
    val (text, color) = when (link) {
        Link.Online -> "ONLINE" to c.go
        Link.Connecting -> "CONNECTING" to c.hazard
        Link.Offline -> "OFFLINE" to c.inkMuted
        Link.Rejected -> "REFUSED" to c.pulse
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color, CircleShape))
        CvText(text, Cv.type.label, color)
    }
}

/** Screen title row: eyebrow above a display title. */
@Composable
fun TitleBlock(eyebrow: String, title: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier) {
        Eyebrow(eyebrow)
        CvText(title, Cv.type.displayL, Cv.colors.ink)
    }
}

/** A labelled section divider used on list screens. */
@Composable
fun SectionLabel(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Eyebrow(text)
        if (trailing != null) Eyebrow(trailing, color = Cv.colors.signal)
    }
}

/** Empty chamfered placeholder block (renders, map thumbnails). */
@Composable
fun PlaceholderBlock(label: String, modifier: Modifier = Modifier) {
    val c = Cv.colors
    Box(
        modifier.cutPanel(c.surface100, c.line, CutSShape),
        contentAlignment = Alignment.Center,
    ) { Eyebrow(label) }
}

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))

/** Guarantees a 48dp touch target. */
fun Modifier.defaultMinTarget(): Modifier = defaultMinSize(minWidth = Space.target, minHeight = Space.target)

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
}
