package app.convoyage.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.convoyage.design.PttState
import app.convoyage.service.ConvoyService
import app.convoyage.voice.PttView

/** What a push-to-talk button needs: its state, the line under it and the two touch handlers. */
class PttControl(val state: PttState, val caption: String?, val onPress: () -> Unit, val onRelease: () -> Unit)

/**
 * Connects a PushToTalk button to the convoy's voice room. The first press asks for the microphone; once it is
 * granted the foreground service is restarted so it can carry the microphone type and keep the voice running with the
 * screen off. A press while voice is unavailable retries the connection.
 */
@Composable
fun rememberPtt(): PttControl {
    val container = LocalContainer.current
    val context = LocalContext.current
    val voice by container.voice.state.collectAsStateWithLifecycle()
    val convoy by container.convoy.state.collectAsStateWithLifecycle()
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) ConvoyService.start(context)
    }
    val members = convoy.active?.members.orEmpty()
    val state = when (voice.view) {
        PttView.Idle -> PttState.Idle
        PttView.Live -> PttState.Live
        PttView.Receiving -> PttState.Receiving
        PttView.Connecting -> PttState.Connecting
        PttView.Unavailable -> PttState.Unavailable
    }
    val caption = when (voice.view) {
        PttView.Unavailable -> "Voice unavailable"
        PttView.Connecting -> "Connecting"
        PttView.Receiving -> voice.talkers.joinToString(", ") { t ->
            t.name.ifBlank { members.firstOrNull { it.id == t.id }?.username.orEmpty() }.ifBlank { "Someone" }
        }
        PttView.Live -> null
        PttView.Idle -> null
    }
    return remember(container, state, caption) {
        PttControl(
            state, caption,
            onPress = {
                val v = container.voice
                when {
                    v.state.value.status == app.convoyage.voice.VoiceStatus.Unavailable -> v.retry()
                    !v.hasMicPermission() -> askMic.launch(Manifest.permission.RECORD_AUDIO)
                    else -> v.pressTalk()
                }
            },
            onRelease = { container.voice.releaseTalk() },
        )
    }
}
