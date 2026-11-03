package app.convoyage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.convoyage.api.ApiResult
import app.convoyage.data.Names
import app.convoyage.data.RideMode
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.Cv
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.CvTextField
import app.convoyage.design.Eyebrow
import app.convoyage.design.SegOption
import app.convoyage.design.Segmented
import app.convoyage.design.Space
import kotlinx.coroutines.launch

/**
 * The one-screen step after a first sign-in: the server names a new account `rider_<hex>`, so ask what the convoy
 * should call you (and optionally pick a handle and how you travel). Saved with PATCH /v1/me.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit, onSkip: () -> Unit) {
    val c = Cv.colors
    val container = LocalContainer.current
    val me by container.account.me.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var handle by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(RideMode.Drive.name) }
    var tried by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val nameProblem = Names.nameProblem(name)
    val handleProblem = Names.handleProblem(handle)

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).screenGutters().navigationBarsPadding()
            .padding(top = Space.s6, bottom = Space.s6),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Eyebrow("ONE QUICK THING")
        CvText("What should the convoy call you?", Cv.type.displayL, c.ink)
        CvText(
            "This is the name riders see on the roster, in chat and on the map. You can change it later in your profile.",
            Cv.type.body, c.inkMuted,
        )

        CvTextField(
            name, { name = it.take(Names.NAME_MAX); serverError = null }, "Display name", placeholder = "Sam Harper",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
        if (tried && nameProblem != null) Eyebrow(nameProblem.uppercase(), color = c.pulse)

        CvTextField(
            handle, { handle = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' }.take(Names.HANDLE_MAX); serverError = null },
            "Handle (optional)", placeholder = me?.handle?.ifBlank { null } ?: "your_handle", mono = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.None),
        )
        if (handleProblem != null) Eyebrow(handleProblem.uppercase(), color = c.pulse)
        else Eyebrow("@${handle.ifBlank { me?.handle ?: "..." }} · LEAVE BLANK TO KEEP YOURS")

        Eyebrow("HOW DO YOU GET AROUND?")
        Segmented(
            options = listOf(SegOption("Drive", CvIcons.Car), SegOption("Ride", CvIcons.Bike), SegOption("Both")),
            selected = RideMode.valueOf(mode).ordinal,
            onSelect = { mode = RideMode.entries[it].name },
            modifier = Modifier.fillMaxWidth(),
        )

        serverError?.let { Banner(BannerTone.Pulse, it, icon = CvIcons.Alert) }

        CvButton(
            if (busy) "Saving..." else "Continue",
            {
                tried = true
                if (nameProblem == null && handleProblem == null && !busy) {
                    busy = true
                    scope.launch {
                        when (val r = container.account.saveProfile(name, handle)) {
                            is ApiResult.Ok -> {
                                container.session.setMode(RideMode.valueOf(mode))
                                // The socket carries the old generated name; reconnect so the convoy sees the new one.
                                container.convoy.disconnect()
                                container.connectIfSignedIn()
                                busy = false
                                onDone()
                            }
                            is ApiResult.Err -> {
                                busy = false
                                serverError = if (r.status == 409) "That handle is taken. Try another." else r.message
                            }
                        }
                    }
                }
            },
            Modifier.fillMaxWidth(), enabled = !busy,
        )
        CvButton("Skip for now", onSkip, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
    }
}
