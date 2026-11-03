package app.convoyage.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.convoyage.AppContainer
import app.convoyage.design.CutMShape
import app.convoyage.design.Cv
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.Eyebrow
import app.convoyage.design.Space
import app.convoyage.design.cutPanel
import app.convoyage.service.CrashUi

private fun granted(context: android.content.Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Asks for what keeping a convoy live with the screen off needs, but only once a convoy or a ride is on and only after
 * explaining why: notifications (Android 13+) for the ongoing notification, then background location (Android 10+).
 * "Not now" is remembered; the convoy still works while the app is open.
 */
@Composable
fun BackgroundAccessGate(container: AppContainer) {
    val context = LocalContext.current
    val convoy by container.convoy.state.collectAsStateWithLifecycle()
    val rec by container.recorder.state.collectAsStateWithLifecycle()
    val seen by container.session.bgRationaleSeen.collectAsStateWithLifecycle()
    var tick by remember { mutableStateOf(0) }
    var askedNotif by remember { mutableStateOf(false) }
    var askedBg by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }

    @Suppress("UNUSED_EXPRESSION") tick
    val needNotif = Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)
    val needBg = Build.VERSION.SDK_INT >= 29 && !granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    val active = convoy.activeCode != null || rec.isActive

    val nextRef = remember { arrayOf<(() -> Unit)?>(null) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { nextRef[0]?.invoke() }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { nextRef[0]?.invoke() }

    fun next() {
        tick++
        val nNotif = Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)
        val nBg = Build.VERSION.SDK_INT >= 29 && !granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        when {
            nNotif && !askedNotif -> { askedNotif = true; notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            nBg && !askedBg -> { askedBg = true; bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
            else -> container.session.setBgRationaleSeen(true)
        }
    }
    nextRef[0] = ::next

    if (!active || seen || !container.tracker.hasPermission() || (!needNotif && !needBg)) return

    val c = Cv.colors
    Box(
        Modifier.fillMaxSize().background(Color(0xE605080D)).clickable(enabled = true) { }.statusBarsPadding().navigationBarsPadding().padding(Space.s4),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().cutPanel(c.surface100, c.signal, CutMShape).padding(Space.s4),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Eyebrow("KEEP THE CONVOY LIVE", color = c.signal)
            CvText("Share your position with the screen off", Cv.type.title, c.ink)
            CvText(
                "When you lock your phone or switch apps, Convoyage keeps sending your position to the convoy and keeps " +
                    "recording your ride. To do that it needs:",
                Cv.type.body, c.inkMuted,
            )
            if (needNotif) CvText("Notifications: an ongoing notice that shows the convoy and lets you leave it.", Cv.type.bodyStrong, c.ink)
            if (needBg) CvText(
                "Location all the time: on the next screen choose \"Allow all the time\". We only use it while you are in a convoy or recording a ride.",
                Cv.type.bodyStrong, c.ink,
            )
            CvButton("Continue", { next() }, Modifier.fillMaxWidth())
            CvButton("Not now", { container.session.setBgRationaleSeen(true) }, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
        }
    }
}

/** The full-screen "Are you OK?" countdown, and what happened if it ran out. Shown over everything. */
@Composable
fun CrashAlertOverlay(container: AppContainer) {
    val ui by container.crash.ui.collectAsStateWithLifecycle()
    val c = Cv.colors
    val state = ui
    if (state is CrashUi.Idle) return
    Box(
        Modifier.fillMaxSize().background(Color(0xFF1A0610)).clickable(enabled = true) { }
            .statusBarsPadding().navigationBarsPadding().padding(Space.s6),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is CrashUi.Counting -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Eyebrow(if (state.simulated) "CRASH DETECTED (TEST)" else "POSSIBLE CRASH DETECTED", color = c.pulse)
                    CvText("Are you OK?", Cv.type.displayL.copy(fontSize = 44.sp, lineHeight = 48.sp), c.ink, textAlign = TextAlign.Center)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CvText(
                        state.remainingS.toString(),
                        Cv.type.displayL.copy(fontSize = 160.sp, lineHeight = 160.sp), c.pulse,
                        modifier = Modifier.semantics { contentDescription = "${state.remainingS} seconds left" },
                    )
                    Eyebrow("SECONDS UNTIL AN SOS GOES TO YOUR CONVOY")
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.s3), horizontalAlignment = Alignment.CenterHorizontally) {
                    CvButton("I'm OK", { container.crash.imOk() }, Modifier.fillMaxWidth().height(96.dp), icon = CvIcons.Check)
                    CvText(
                        "If you do not answer, your convoy chat gets an SOS with your location. We never call emergency services for you: call 000 or 911 yourself if you need help.",
                        Cv.type.bodyS, c.inkMuted, textAlign = TextAlign.Center,
                    )
                }
            }
            is CrashUi.Expired -> Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Space.s4),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Eyebrow("CRASH ALERT", color = c.pulse)
                CvText(if (state.sentToConvoy) "SOS sent" else "No answer", Cv.type.displayL, c.ink, textAlign = TextAlign.Center)
                CvText(state.message, Cv.type.body, c.inkMuted, textAlign = TextAlign.Center)
                CvButton("Dismiss", { container.crash.dismissResult() }, Modifier.fillMaxWidth().height(72.dp))
            }
            CrashUi.Idle -> Unit
        }
    }
}
