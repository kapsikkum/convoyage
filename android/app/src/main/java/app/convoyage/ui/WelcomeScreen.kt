package app.convoyage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.convoyage.AppContainer
import app.convoyage.data.MagicLinkResult
import app.convoyage.data.RideMode
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.CvTextField
import app.convoyage.design.Cv
import app.convoyage.design.Eyebrow
import app.convoyage.design.MapPin
import app.convoyage.design.PinKind
import app.convoyage.design.SegOption
import app.convoyage.design.Segmented
import app.convoyage.design.Space
import app.convoyage.design.gridBackdrop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WelcomeUi(
    val busy: Boolean = false,
    val magicLinkSent: Boolean = false,
    /** Set when the server has no email sign-in yet; the UI then points at Dev sign-in. */
    val authNotReady: String? = null,
    val error: String? = null,
)

class WelcomeViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(WelcomeUi())
    val ui: StateFlow<WelcomeUi> = _ui.asStateFlow()
    val mode = container.session.mode
    val serverUrl = container.session.serverUrl

    fun setMode(mode: RideMode) = container.session.setMode(mode)

    fun sendMagicLink(email: String) {
        if (!email.contains("@")) {
            _ui.value = WelcomeUi(error = "Enter a valid email address")
            return
        }
        _ui.value = WelcomeUi(busy = true)
        viewModelScope.launch {
            _ui.value = when (val r = container.auth.requestMagicLink(container.session.serverUrl.value, email)) {
                MagicLinkResult.Sent -> WelcomeUi(magicLinkSent = true)
                is MagicLinkResult.NotReady -> WelcomeUi(authNotReady = r.detail)
                is MagicLinkResult.Failed -> WelcomeUi(error = r.message)
            }
        }
    }

    /** Dev sign-in: the server's `?dev_user=<id>&name=<name>` WebSocket auth. */
    fun devSignIn(name: String, serverUrl: String) {
        if (name.isBlank()) {
            _ui.value = WelcomeUi(error = "Pick a name to dev sign in")
            return
        }
        container.session.setServerUrl(serverUrl)
        container.session.signIn(name)
        container.connectIfSignedIn()
    }
}

@Composable
fun WelcomeScreen(onSignedIn: () -> Unit) {
    val container = LocalContainer.current
    val vm: WelcomeViewModel = viewModel(factory = viewModelFactory { initializer { WelcomeViewModel(container) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle()
    val c = Cv.colors

    var email by rememberSaveable { mutableStateOf("") }
    var devOpen by rememberSaveable { mutableStateOf(false) }
    var devName by rememberSaveable { mutableStateOf("") }
    var devServer by rememberSaveable(serverUrl) { mutableStateOf(serverUrl) }
    val showDev = devOpen || ui.authNotReady != null

    Column(
        Modifier
            .fillMaxSize()
            .gridBackdrop(c.line.copy(alpha = 0.35f))
            .verticalScroll(rememberScrollState())
            .screenGutters()
            .navigationBarsPadding()
            .padding(horizontal = Space.s2, vertical = Space.s6),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LogoMark()
                CvText("CONVOYAGE", Cv.type.title.copy(letterSpacing = 0.14.em), c.ink)
            }
            Box(Modifier.defaultMinTarget().clickableNoRipple { devOpen = !devOpen }, contentAlignment = Alignment.CenterEnd) {
                Eyebrow("DEV")
            }
        }

        WelcomeHero()

        Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Eyebrow("GROUP DRIVES · GROUP RIDES", color = c.signal)
            CvText("NEVER LOSE\nTHE CONVOY.", Cv.type.displayXl, c.ink)
            CvText(
                "Live positions, push-to-talk, regroup alerts and hazard calls for everyone in the group, on four wheels or two.",
                Cv.type.body, c.inkMuted,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Eyebrow("HOW DO YOU ROLL?")
            Segmented(
                options = listOf(SegOption("I drive", CvIcons.Car), SegOption("I ride", CvIcons.Bike), SegOption("Both")),
                selected = mode.ordinal,
                onSelect = { vm.setMode(RideMode.entries[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            CvTextField(
                value = email, onValueChange = { email = it }, label = "Email",
                placeholder = "you@example.com",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            CvButton(
                text = if (ui.busy) "Sending..." else "Email me a sign-in link",
                onClick = { vm.sendMagicLink(email) },
                icon = CvIcons.Mail, enabled = !ui.busy, modifier = Modifier.fillMaxWidth(),
            )
            if (ui.magicLinkSent) Banner(BannerTone.Go, "Check your inbox", sub = "Open the link on this phone to finish signing in.", icon = CvIcons.Mail)
            ui.authNotReady?.let {
                Banner(
                    BannerTone.Hazard, "Email sign-in is not ready on this server",
                    sub = "Use Dev sign-in below. ($it)", icon = CvIcons.Lock,
                )
            }
            ui.error?.let { Banner(BannerTone.Pulse, it) }

            if (!showDev) {
                CvButton("Dev sign-in", { devOpen = true }, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Eyebrow("DEV SIGN-IN · SERVER WEBSOCKET AUTH", color = c.hazard)
                    CvTextField(devName, { devName = it }, "Display name", placeholder = "Alex")
                    CvTextField(devServer, { devServer = it }, "Server URL", mono = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    CvButton(
                        "Enter as ${devName.ifBlank { "..." }}",
                        onClick = { vm.devSignIn(devName, devServer); if (devName.isNotBlank()) onSignedIn() },
                        modifier = Modifier.fillMaxWidth(), kind = CvButtonKind.Ghost,
                    )
                }
            }
        }
    }
}

/** Decorative hero: a route with three convoy pins, like the welcome mockup. */
@Composable
private fun WelcomeHero() {
    val c = Cv.colors
    Box(Modifier.fillMaxWidth().height(220.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(-20f, h * 0.92f)
                cubicTo(w * 0.15f, h * 0.8f, w * 0.25f, h * 0.55f, w * 0.4f, h * 0.55f)
                cubicTo(w * 0.6f, h * 0.55f, w * 0.7f, h * 0.45f, w * 0.75f, h * 0.3f)
                cubicTo(w * 0.85f, h * 0.05f, w * 0.95f, h * 0.05f, w + 20f, h * 0.05f)
            }
            drawPath(path, c.lineStrong.copy(alpha = 0.55f), style = Stroke(width = 22.dp.toPx(), cap = StrokeCap.Round))
            drawPath(path, c.signal, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        }
        MapPin(PinKind.Member, "AV", Modifier.offset(x = 24.dp, y = 140.dp), heading = 40f)
        MapPin(PinKind.Me, "", Modifier.offset(x = 120.dp, y = 90.dp), heading = 70f)
        MapPin(PinKind.Lead, "JK", Modifier.offset(x = 240.dp, y = 28.dp), heading = 30f, tag = "LEAD")
    }
}

@Composable
fun LogoMark(size: androidx.compose.ui.unit.Dp = 28.dp) {
    val c = Cv.colors
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 28f
        val body = Path().apply {
            moveTo(8 * s, 0f); lineTo(28 * s, 0f); lineTo(28 * s, 20 * s); lineTo(20 * s, 28 * s); lineTo(0f, 28 * s); lineTo(0f, 8 * s); close()
        }
        drawPath(body, c.signal)
        listOf(Offset(9 * s, 19 * s), Offset(14 * s, 14 * s), Offset(19 * s, 9 * s)).forEach {
            drawCircle(c.signalInk, 2.5f * s, it)
        }
    }
}
