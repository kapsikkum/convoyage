package app.convoyage.data

import app.convoyage.net.CLOSE_SESSION_ENDED
import app.convoyage.net.ConvoySocket
import app.convoyage.net.SocketEvent
import app.convoyage.net.devSocketUrl
import app.convoyage.protocol.AutomaticRegroup
import app.convoyage.protocol.ClientMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Owns the one WebSocket and the [ConvoyState] folded from it, and publishes our own position at ~1 Hz
 * while at least one screen is visible and we are in a convoy.
 */
class ConvoyRepository(
    private val scope: CoroutineScope,
    private val socket: ConvoySocket,
    private val tracker: LocationTracker,
) {
    private val _state = MutableStateFlow(ConvoyState())
    val state: StateFlow<ConvoyState> = _state.asStateFlow()

    private var connection: Job? = null
    private var publisher: Job? = null
    private var visibleCount = 0

    /** Connects (and keeps reconnecting with backoff) using the server's dev WebSocket auth. */
    fun connect(baseUrl: String, userId: String, name: String) {
        connection?.cancel()
        val url = devSocketUrl(baseUrl, userId, name)
        if (url == null) {
            _state.update { it.copy(link = Link.Offline, error = "Server URL is not valid: $baseUrl") }
            return
        }
        connection = scope.launch {
            var attempt = 0
            while (isActive) {
                _state.update { it.copy(link = Link.Connecting) }
                var stop = false
                socket.open(url).collect { event ->
                    when (event) {
                        SocketEvent.Opened -> attempt = 0
                        is SocketEvent.Received -> _state.update { it.apply(event.message) }
                        is SocketEvent.Closed -> if (event.code == CLOSE_SESSION_ENDED) {
                            stop = true
                            _state.update { it.copy(link = Link.Rejected, error = "Session ended") }
                        }
                        is SocketEvent.Failed -> if (event.httpStatus == 401) {
                            stop = true
                            _state.update { it.copy(link = Link.Rejected, error = "Server refused sign-in (is CONVOYAGE_DEV_AUTH=1 set?)") }
                        } else {
                            _state.update { it.copy(error = "Cannot reach ${url.host}:${url.port}") }
                        }
                    }
                }
                if (stop) return@launch
                _state.update { it.copy(link = Link.Offline) }
                attempt++
                delay((1000L shl attempt.coerceAtMost(4)).coerceAtMost(15_000L))
            }
        }
    }

    fun disconnect() {
        connection?.cancel()
        connection = null
        _state.value = ConvoyState()
    }

    fun clearError() = _state.update { it.copy(error = null, removedFrom = null) }

    // ---- commands -------------------------------------------------------------------------------

    fun createConvoy(name: String, regroupSeparationM: Double? = null) {
        val regroup = regroupSeparationM?.let { AutomaticRegroup(true, it) }
        send(ClientMessage.ConvoyCreate(name.trim(), regroup))
    }

    fun join(code: String) = send(ClientMessage.ConvoyJoin(code.trim().uppercase()))

    fun leave() {
        val code = _state.value.activeCode ?: return
        send(ClientMessage.ConvoyLeave(code))
    }

    fun findRestStop() {
        val code = _state.value.activeCode ?: return
        send(ClientMessage.ConvoyRest(code, "find"))
    }

    fun sendChat(text: String) {
        val code = _state.value.activeCode ?: return
        if (text.isBlank()) return
        send(ClientMessage.ConvoyChat(code, text.trim().take(500), UUID.randomUUID().toString()))
    }

    private fun send(message: ClientMessage) {
        if (!socket.send(message)) _state.update { it.copy(error = "Not connected") }
    }

    // ---- position publishing --------------------------------------------------------------------

    /** Screens call this when they become visible/hidden; positions are only sent while visible. */
    fun setVisible(visible: Boolean) {
        visibleCount = (visibleCount + if (visible) 1 else -1).coerceAtLeast(0)
        if (visibleCount > 0 && publisher == null) startPublisher()
        if (visibleCount == 0) {
            publisher?.cancel()
            publisher = null
        }
    }

    private fun startPublisher() {
        publisher = scope.launch {
            while (isActive) {
                delay(1000)
                val s = _state.value
                val fix = tracker.fix.value ?: continue
                if (s.link != Link.Online || s.rooms.isEmpty()) continue
                if (System.currentTimeMillis() - fix.timeMs > 15_000) continue // stale
                socket.send(
                    ClientMessage.Pos(
                        lat = fix.lat,
                        lng = fix.lng,
                        heading = fix.headingDeg?.let { ((it % 360) + 360) % 360 },
                        speed = fix.speedMps?.coerceIn(0.0, 120.0),
                    ),
                )
            }
        }
    }
}
