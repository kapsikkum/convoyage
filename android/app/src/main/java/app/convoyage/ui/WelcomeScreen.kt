package app.convoyage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
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
import app.convoyage.BuildConfig
import app.convoyage.data.AuthKind
import app.convoyage.data.MagicLinkOutcome
import app.convoyage.data.RideMode
import app.convoyage.data.SignInOutcome
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.Cv
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvIconButton
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.CvTextField
import app.convoyage.design.Eyebrow
import app.convoyage.design.MapPin
import app.convoyage.design.PinKind
import app.convoyage.design.SegOption
import app.convoyage.design.Segmented
import app.convoyage.design.Space
import app.convoyage.design.gridBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The sign-in form's state. [sentTo] set = the "check your email" step. */
data class WelcomeUi(
    val busy: Boolean = false,
    val sentTo: String? = null,
    /** Shown while busy, e.g. when the dev server handed the link back. */
    val progress: String? = null,
    val error: String? = null,
)

class WelcomeViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(WelcomeUi())
    val ui: StateFlow<WelcomeUi> = _ui.asStateFlow()
    val mode = container.session.mode
    val serverUrl = container.session.serverUrl

    fun setMode(mode: RideMode) = container.session.setMode(mode)

    /** Debug builds can point at another server from the dev panel. */
    fun applyServer(url: String) {
        if (BuildConfig.DEBUG && url.isNotBlank() && url.trim().trimEnd('/') != container.session.serverUrl.value) container.session.setServerUrl(url)
    }

    fun sendMagicLink(email: String) {
        if (!email.contains("@") || email.trim().length < 5) {
            _ui.value = WelcomeUi(error = "Enter a valid email address")
            return
        }
        _ui.value = WelcomeUi(busy = true)
        viewModelScope.launch {
            when (val r = container.account.requestMagicLink(email)) {
                is MagicLinkOutcome.Failed -> _ui.value = WelcomeUi(error = r.message)
                is MagicLinkOutcome.Sent -> {
                    val token = r.devToken
                    if (token == null) {
                        _ui.value = WelcomeUi(sentTo = email.trim())
                    } else {
                        // MAIL_DEV=1: the server handed the token back, so there is no inbox to open.
                        _ui.value = WelcomeUi(busy = true, sentTo = email.trim(), progress = "Dev server returned the link, signing in...")
                        verify(token, email.trim())
                    }
                }
            }
        }
    }

    /** Verifies a pasted link or code (also the dev auto-verify path). */
    fun verify(tokenOrLink: String, sentTo: String? = _ui.value.sentTo) {
        _ui.value = WelcomeUi(busy = true, sentTo = sentTo, progress = "Signing in...")
        viewModelScope.launch {
            when (val r = container.signInWithLink(tokenOrLink)) {
                // AppRoot sees the new session and moves to Home.
                is SignInOutcome.SignedIn -> _ui.value = WelcomeUi(sentTo = sentTo)
                is SignInOutcome.Failed -> _ui.value = WelcomeUi(sentTo = sentTo, error = r.message)
            }
        }
    }

    fun back() {
        _ui.value = WelcomeUi()
    }

    /** Debug-only dev sign-in: the server's `?dev_user=<id>&name=<name>` WebSocket auth. No JWT, so no REST. */
    fun devSignIn(name: String, serverUrl: String) {
        if (!BuildConfig.DEBUG || name.isBlank()) return
        applyServer(serverUrl)
        container.session.signIn(name, AuthKind.Dev)
        container.connectIfSignedIn()
    }
}

@Composable
fun WelcomeScreen(onOpenSettings: () -> Unit) {
    val container = LocalContainer.current
    val vm: WelcomeViewModel = viewModel(factory = viewModelFactory { initializer { WelcomeViewModel(container) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle()
    val c = Cv.colors

    var email by rememberSaveable { mutableStateOf("") }
    var pasted by rememberSaveable { mutableStateOf("") }
    var devOpen by rememberSaveable { mutableStateOf(false) }
    var devName by rememberSaveable { mutableStateOf("Dev Driver") }
    var devServer by rememberSaveable(serverUrl) { mutableStateOf(serverUrl) }
    val showDev = BuildConfig.DEBUG && devOpen

    // Each form scrolls its primary button into view when a field gets focus, so the keyboard
    // (imePadding below shrinks the scroll viewport) never covers the field or the button under it.
    val emailButton = remember { BringIntoViewRequester() }
    val pasteButton = remember { BringIntoViewRequester() }
    val devButton = remember { BringIntoViewRequester() }
    // The field that has focus asks for its button to be shown. The keyboard animates in, so wait until the
    // IME height has stopped changing (the effect restarts on every change) before scrolling.
    var revealTarget by remember { mutableStateOf<BringIntoViewRequester?>(null) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(revealTarget, imeBottom) {
        val target = revealTarget ?: return@LaunchedEffect
        if (imeBottom == 0) return@LaunchedEffect
        delay(150)
        target.bringIntoView()
    }
    fun reveal(target: BringIntoViewRequester) {
        revealTarget = target
    }

    Column(
        Modifier
            .fillMaxSize()
            .gridBackdrop(c.line.copy(alpha = 0.35f))
            .imePadding()
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
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                if (BuildConfig.DEBUG) {
                    Box(Modifier.defaultMinTarget().clickableNoRipple { devOpen = !devOpen }, contentAlignment = Alignment.CenterEnd) {
                        Eyebrow("DEV")
                    }
                }
                // The server can be set before signing in, in every build.
                CvIconButton(CvIcons.Settings, "Settings", onOpenSettings)
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
            if (ui.sentTo == null) {
                CvTextField(
                    value = email, onValueChange = { email = it }, label = "Email",
                    placeholder = "you@example.com",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    enabled = !ui.busy,
                    onFocused = { reveal(emailButton) },
                )
                CvButton(
                    text = if (ui.busy) "Sending..." else "Email me a sign-in link",
                    onClick = { vm.applyServer(devServer); vm.sendMagicLink(email) },
                    icon = CvIcons.Mail, enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth().bringIntoViewRequester(emailButton).padding(bottom = 12.dp),
                )
            } else {
                Banner(
                    BannerTone.Go, "Check your email",
                    sub = "We sent a sign-in link to ${ui.sentTo}. Open it on this phone to finish. It works once and expires in 15 minutes.",
                    icon = CvIcons.Mail,
                )
                ui.progress?.let { Banner(BannerTone.Hazard, it, icon = CvIcons.Lock) }
                CvTextField(
                    value = pasted, onValueChange = { pasted = it }, label = "Or paste the link or code",
                    placeholder = "convoyage://auth/magic?token=...", mono = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    enabled = !ui.busy,
                    onFocused = { reveal(pasteButton) },
                )
                CvButton(
                    "Sign in", { vm.verify(pasted) }, Modifier.fillMaxWidth().bringIntoViewRequester(pasteButton).padding(bottom = 12.dp),
                    enabled = !ui.busy && pasted.isNotBlank(),
                )
                CvButton("Use a different email", vm::back, Modifier.fillMaxWidth(), CvButtonKind.Ghost, enabled = !ui.busy)
            }
            ui.error?.let { Banner(BannerTone.Pulse, it) }

            if (showDev) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Eyebrow("DEV SIGN-IN · SERVER WEBSOCKET AUTH · DEBUG BUILD ONLY", color = c.hazard)
                    CvTextField(devName, { devName = it.take(40) }, "Display name", placeholder = "Alex", onFocused = { reveal(devButton) })
                    CvTextField(
                        devServer, { devServer = it }, "Server URL", mono = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        onFocused = { reveal(devButton) },
                    )
                    if (devName.isBlank()) Eyebrow("ENTER A DISPLAY NAME TO CONTINUE", color = c.hazard)
                    CvButton(
                        if (devName.isBlank()) "Enter a name first" else "Enter as ${devName.trim()}",
                        onClick = { vm.devSignIn(devName, devServer) },
                        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(devButton).padding(bottom = 12.dp),
                        kind = CvButtonKind.Ghost, enabled = devName.isNotBlank(),
                    )
                }
            } else if (BuildConfig.DEBUG) {
                CvButton("Dev sign-in", { devOpen = true }, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
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
