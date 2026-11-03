package app.convoyage.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.convoyage.api.ApiResult
import app.convoyage.api.VoiceToken
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class VoiceStatus { Idle, Connecting, Connected, Unavailable }

/** Somebody talking right now. [id] is their user id (the LiveKit identity). */
data class Talker(val id: String, val name: String)

/** What the push-to-talk button shows. */
enum class PttView { Idle, Live, Receiving, Connecting, Unavailable }

data class VoiceState(
    val status: VoiceStatus = VoiceStatus.Idle,
    /** The PTT button is held and the microphone is open. */
    val transmitting: Boolean = false,
    /** Other people who are talking. */
    val talkers: List<Talker> = emptyList(),
    /** Why voice is unavailable, for the log and a tooltip. */
    val error: String? = null,
) {
    val view: PttView
        get() = when {
            status == VoiceStatus.Unavailable -> PttView.Unavailable
            status == VoiceStatus.Connecting -> PttView.Connecting
            status == VoiceStatus.Idle -> PttView.Unavailable
            transmitting -> PttView.Live
            talkers.isNotEmpty() -> PttView.Receiving
            else -> PttView.Idle
        }
}

/**
 * Push-to-talk over LiveKit. While in a convoy it keeps one connection to the room `convoy-<code>` with the
 * microphone muted; holding the button opens the mic, releasing mutes it. Everything about it can fail (no LiveKit on
 * the server, the server not reachable over its media ports...) and when it does the state says so and the rest of the
 * app carries on.
 *
 * The connection lives in the application scope, kept alive by [app.convoyage.service.ConvoyService] (foreground
 * service) while a convoy is on.
 *
 * Network: the ICE configuration is left at LiveKit's defaults, which include TCP candidates, so when the server
 * only forwards TCP (LiveKit's `rtc.tcp_port`, 7881) the media falls back to it instead of failing.
 */
class VoiceChat(
    context: Context,
    private val scope: CoroutineScope,
    private val fetchToken: suspend (convoyCode: String) -> ApiResult<VoiceToken>,
    private val audio: VoiceAudio = VoiceAudio(context),
) {
    private val app = context.applicationContext
    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var code: String? = null
    private var job: Job? = null
    private var room: Room? = null
    private var idleJob: Job? = null
    private val mic = Channel<Boolean>(Channel.CONFLATED)
    private var endOfConnection: CompletableDeferred<Unit>? = null

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Connects to the voice room of convoy [convoyCode] (and leaves the previous one). Safe to call repeatedly. */
    fun join(convoyCode: String) {
        if (code == convoyCode && job?.isActive == true) return
        leave()
        code = convoyCode
        _state.value = VoiceState(VoiceStatus.Connecting)
        job = scope.launch { run(convoyCode) }
    }

    /** Disconnects and forgets the room. */
    fun leave() {
        job?.cancel()
        job = null
        code = null
        idleJob?.cancel()
        closeRoom()
        audio.setActive(false)
        _state.value = VoiceState()
    }

    /** Tries again after "Voice unavailable" (a press of the button). */
    fun retry() {
        val c = code ?: return
        if (_state.value.status == VoiceStatus.Unavailable) {
            job?.cancel()
            _state.update { it.copy(status = VoiceStatus.Connecting, error = null) }
            job = scope.launch { run(c) }
        }
    }

    /** The PTT button went down. False when voice is not ready (the UI then offers a retry). */
    fun pressTalk(): Boolean {
        if (_state.value.status != VoiceStatus.Connected) return false
        if (!hasMicPermission()) return false
        _state.update { it.copy(transmitting = true) }
        mic.trySend(true)
        refreshAudio()
        return true
    }

    fun releaseTalk() {
        if (!_state.value.transmitting) return
        _state.update { it.copy(transmitting = false) }
        mic.trySend(false)
        refreshAudio()
    }

    private suspend fun run(convoyCode: String) {
        var attempt = 0
        while (true) {
            val outcome = connectOnce(convoyCode)
            when (outcome) {
                Outcome.Gone -> return
                Outcome.Permanent -> return
                Outcome.Retry -> {
                    // Tried and failed (or dropped): wait, then try again, slowly. The button shows "Voice unavailable" meanwhile.
                    attempt++
                    delay((5_000L shl attempt.coerceAtMost(4)).coerceAtMost(60_000L))
                    _state.update { it.copy(status = VoiceStatus.Connecting) }
                }
            }
        }
    }

    private enum class Outcome { Gone, Permanent, Retry }

    private suspend fun connectOnce(convoyCode: String): Outcome {
        val granted = when (val r = fetchToken(convoyCode)) {
            is ApiResult.Ok -> r.value
            is ApiResult.Err -> {
                unavailable("Voice token: ${r.message}")
                // 501 = this server has no LiveKit; 401/403/404 = not allowed. Neither is fixed by waiting.
                return if (r.status == 0 || r.status >= 500 && r.status != 501) Outcome.Retry else Outcome.Permanent
            }
        }
        val r = LiveKit.create(
            app,
            overrides = LiveKitOverrides(audioOptions = AudioOptions(audioHandler = NoAudioHandler())),
        )
        room = r
        val ended = CompletableDeferred<Unit>()
        endOfConnection = ended
        val events = scope.launch { watch(r) }
        val pump = scope.launch { pumpMic(r) }
        return try {
            r.connect(granted.url, granted.token)
            _state.update { it.copy(status = VoiceStatus.Connected, error = null) }
            // Wait here for as long as the connection lasts; unavailable() completes this when it ends.
            ended.await()
            Outcome.Retry
        } catch (e: CancellationException) {
            Outcome.Gone
        } catch (e: Exception) {
            unavailable("Voice connect: ${e.message ?: e.javaClass.simpleName}")
            Outcome.Retry
        } finally {
            events.cancel()
            pump.cancel()
            if (room === r) closeRoom()
        }
    }

    private suspend fun watch(r: Room) {
        r.events.events.collect { ev ->
            when (ev) {
                is RoomEvent.ActiveSpeakersChanged -> {
                    val others = ev.speakers.filter { it !is LocalParticipant }.map { it.toTalker() }
                    _state.update { it.copy(talkers = others) }
                    refreshAudio()
                }
                is RoomEvent.Disconnected -> unavailable("Voice disconnected")
                is RoomEvent.FailedToConnect -> unavailable("Voice connect: ${ev.error.message}")
                is RoomEvent.Reconnecting -> _state.update { it.copy(status = VoiceStatus.Connecting) }
                is RoomEvent.Reconnected -> _state.update { it.copy(status = VoiceStatus.Connected, error = null) }
                else -> Unit
            }
        }
    }

    /** Applies the newest mic on/off request, one at a time (publishing the track takes a moment the first time). */
    private suspend fun pumpMic(r: Room) {
        for (on in mic) {
            try {
                r.localParticipant.setMicrophoneEnabled(on)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(transmitting = false, error = "Microphone: ${e.message ?: e.javaClass.simpleName}") }
                refreshAudio()
            }
        }
    }

    private fun Participant.toTalker(): Talker = Talker(identity?.value.orEmpty(), name.orEmpty())

    private fun unavailable(why: String) {
        endOfConnection?.complete(Unit)
        _state.update { it.copy(status = VoiceStatus.Unavailable, transmitting = false, talkers = emptyList(), error = why) }
        refreshAudio()
    }

    /** Duck/route audio while somebody talks; put it back 2 s after the last word (so a pause does not flap the route). */
    private fun refreshAudio() {
        val s = _state.value
        val busy = s.transmitting || s.talkers.isNotEmpty()
        idleJob?.cancel()
        if (busy) {
            audio.setActive(true)
        } else {
            idleJob = scope.launch {
                delay(2_000)
                audio.setActive(false)
            }
        }
    }

    private fun closeRoom() {
        val r = room ?: return
        room = null
        runCatching { r.disconnect() }
        runCatching { r.release() }
    }
}
