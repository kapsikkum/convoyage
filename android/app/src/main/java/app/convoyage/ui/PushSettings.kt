package app.convoyage.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.convoyage.design.Cv
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvChipToggle
import app.convoyage.design.CvText
import app.convoyage.design.Eyebrow
import app.convoyage.design.Space
import app.convoyage.push.PushMode
import app.convoyage.push.PushStatus

/**
 * Notifications (Settings). No Google push on F-Droid, so: UnifiedPush through a distributor app, or "Stay connected"
 * on our own socket, or off. Also asks for the notification permission and, for Stay connected, the battery exemption.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PushSettings() {
    val c = Cv.colors
    val container = LocalContainer.current
    val context = LocalContext.current
    val mode by container.session.pushMode.collectAsStateWithLifecycle()
    val status by container.push.status.collectAsStateWithLifecycle()
    // Re-read things that change outside the app (a distributor installed, the battery exemption) on return.
    var resumes by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.repeatOnLifecycle(Lifecycle.State.RESUMED) { resumes++; container.push.apply() } }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun choose(m: PushMode) {
        if (m != PushMode.Off && Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        container.session.setPushMode(m)
    }

    Eyebrow("NOTIFICATIONS")
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            CvChipToggle("UnifiedPush", mode == PushMode.UnifiedPush, { choose(PushMode.UnifiedPush) })
            CvChipToggle("Stay connected", mode == PushMode.StayConnected, { choose(PushMode.StayConnected) })
            CvChipToggle("Off", mode == PushMode.Off, { choose(PushMode.Off) })
        }
        CvText(explain(mode, status), Cv.type.bodyS, c.inkMuted)
        status.error?.let { if (mode == PushMode.UnifiedPush) Eyebrow(it.uppercase(), color = c.hazard) }

        if (mode == PushMode.UnifiedPush && status.distributors.size > 1) {
            Eyebrow("DELIVERED BY")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                for (d in status.distributors) CvChipToggle(d.label, status.current == d.pkg, { container.push.pick(d.pkg) })
            }
        }
        if (mode == PushMode.UnifiedPush && status.distributors.isEmpty()) {
            CvButton("Get ntfy on F-Droid", { openNtfy(context) }, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
        }
        if (mode == PushMode.StayConnected && resumes >= 0 && !ignoringBatteryOptimisations(context)) {
            CvButton("Let it run in the background", { askBatteryExemption(context) }, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
        }
    }
}

private fun explain(mode: PushMode, s: PushStatus): String = when (mode) {
    PushMode.UnifiedPush -> when {
        s.distributors.isEmpty() -> "Needs a UnifiedPush app such as ntfy, which holds one connection for all your apps. " +
            "Until you install one, notifications only arrive while Convoyage is open. Or choose Stay connected."
        s.needsPick -> "Pick which app delivers Convoyage's notifications."
        s.registered -> "On, through ${s.distributors.firstOrNull { it.pkg == s.current }?.label ?: "your UnifiedPush app"}. " +
            "Messages are end-to-end encrypted: the delivering app cannot read them."
        else -> "Connecting to ${s.distributors.firstOrNull { it.pkg == s.current }?.label ?: "your UnifiedPush app"}..."
    }
    PushMode.StayConnected -> "Convoyage keeps its own connection open in the background, shown as a quiet notification. " +
        "Works without any other app, but uses some battery."
    PushMode.Off -> "No notifications while Convoyage is closed. SOS alerts from your convoy will not reach you."
}

private fun ignoringBatteryOptimisations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

@SuppressLint("BatteryLife") // the whole point of Stay connected; F-Droid has no policy against it
private fun askBatteryExemption(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
    }
}

private fun openNtfy(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=io.heckel.ntfy"))
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/io.heckel.ntfy/"))
    runCatching { context.startActivity(market) }.onFailure { runCatching { context.startActivity(web) } }
}
