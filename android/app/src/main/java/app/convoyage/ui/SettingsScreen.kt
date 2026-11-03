package app.convoyage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.convoyage.BuildConfig
import app.convoyage.data.AuthKind
import app.convoyage.data.ServerUrl
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.CutMShape
import app.convoyage.design.Cv
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvCard
import app.convoyage.design.CvChipToggle
import app.convoyage.design.CvIconButton
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.CvTextField
import app.convoyage.design.Eyebrow
import app.convoyage.design.Space

/**
 * Settings, reachable from Profile's gear and from the Welcome screen (so the server can be set before signing in).
 * The server address is editable in every build and stored on the phone.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = Cv.colors
    val container = LocalContainer.current
    val session = container.session
    val serverUrl by session.serverUrl.collectAsStateWithLifecycle()
    val night by session.night.collectAsStateWithLifecycle()
    val auto by session.autoRecordConvoy.collectAsStateWithLifecycle()
    val kind by session.kind.collectAsStateWithLifecycle()
    val crashOn by session.crashDetection.collectAsStateWithLifecycle()
    var draft by rememberSaveable(serverUrl) { mutableStateOf(serverUrl) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun apply(url: String) {
        session.setServerUrl(url)
        // New address, new connection. Tokens belong to the old server, so a signed-in account may need to sign in again.
        container.convoy.disconnect()
        container.connectIfSignedIn()
        message = "Saved. Using ${session.serverUrl.value}"
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).screenGutters().navigationBarsPadding().padding(top = Space.s2, bottom = Space.s6),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
            CvIconButton(CvIcons.Back, "Back", onBack, fill = c.surface200)
            Column {
                Eyebrow("THIS DEVICE")
                CvText("Settings", Cv.type.title, c.ink)
            }
        }

        Eyebrow("SERVER")
        CvCard(Modifier.fillMaxWidth(), shape = CutMShape) {
            Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                CvTextField(
                    draft, { draft = it; message = null; error = null }, "Server address", placeholder = "http://192.168.1.20:8080", mono = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                CvText(
                    "The convoy server this app talks to: sign-in, convoys, rides, garage and the feed. Self-hosting on your network? " +
                        "Use its address, for example http://192.168.1.20:8080.",
                    Cv.type.bodyS, c.inkMuted,
                )
                error?.let { Banner(BannerTone.Pulse, it) }
                message?.let { Banner(BannerTone.Go, it, icon = CvIcons.Check) }
                if (ServerUrl.isCleartext(draft)) {
                    Eyebrow("PLAIN HTTP: FINE ON YOUR OWN NETWORK, NOT ON THE INTERNET", color = c.hazard)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    CvButton(
                        "Default", { draft = BuildConfig.SERVER_URL; apply(BuildConfig.SERVER_URL) },
                        Modifier.weight(1f), CvButtonKind.Ghost, enabled = serverUrl != BuildConfig.SERVER_URL,
                    )
                    CvButton(
                        "Save",
                        {
                            val clean = ServerUrl.normalize(draft)
                            if (clean == null) error = "That is not a valid address. Use http:// or https:// and a host."
                            else { draft = clean; apply(clean) }
                        },
                        Modifier.weight(1f), enabled = draft.isNotBlank() && draft.trim().trimEnd('/') != serverUrl,
                    )
                }
                Eyebrow("DEFAULT FOR THIS BUILD · ${BuildConfig.SERVER_URL}")
            }
        }
        if (kind == AuthKind.Account && message != null) Eyebrow("CHANGED SERVER? SIGN IN AGAIN IF THINGS FAIL TO LOAD.")

        Eyebrow("APPEARANCE")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CvText("Night theme", Cv.type.bodyStrong, c.ink)
            CvChipToggle(if (night) "On" else "Off", night, { session.setNight(!night) })
        }

        Eyebrow("RECORDING")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = Space.s3)) {
                CvText("Record convoy rides automatically", Cv.type.bodyStrong, c.ink)
                CvText("Starts with a convoy, stops when you leave it. Keeps recording with the screen off.", Cv.type.bodyS, c.inkMuted)
            }
            CvChipToggle(if (auto) "On" else "Off", auto, { session.setAutoRecordConvoy(!auto) })
        }

        Eyebrow("SAFETY")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = Space.s3)) {
                CvText("Crash detection (Ride mode)", Cv.type.bodyStrong, c.ink)
                CvText(
                    "While you are in a convoy or recording a ride, a hard impact followed by about 10 seconds of standing still asks " +
                        "\"Are you OK?\". No answer in 30 seconds sends an SOS with your location to your convoy chat. " +
                        "It never calls emergency services.",
                    Cv.type.bodyS, c.inkMuted,
                )
            }
            CvChipToggle(if (crashOn) "On" else "Off", crashOn, { session.setCrashDetection(!crashOn) })
        }

        if (BuildConfig.DEBUG) {
            Eyebrow("DEBUG")
            CvButton("Simulate a crash", { container.crash.trigger(simulated = true) }, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
        }

        Eyebrow("ABOUT")
        CvText("Convoyage ${BuildConfig.VERSION_NAME}${if (BuildConfig.DEBUG) " (debug)" else ""}", Cv.type.telemetry, c.inkMuted)
    }
}
