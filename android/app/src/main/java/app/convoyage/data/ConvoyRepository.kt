package app.convoyage.data

import app.convoyage.net.CLOSE_SESSION_ENDED
import app.convoyage.net.ConvoySocket
import app.convoyage.net.SocketEvent
import app.convoyage.net.accountSocketRequest
import app.convoyage.net.devSocketRequest
import app.convoyage.protocol.AutomaticRegroup
import app.convoyage.protocol.ClientMessage
import app.convoyage.protocol.ConvoyRoute
import app.convoyage.protocol.Destination
import app.convoyage.protocol.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/** Who the socket signs in as. [Account] uses the stored JWT; [Dev] is the debug-only `?dev_user=` auth. */
sealed interface SocketAuth {
    data object Account : SocketAuth
    data class Dev(val userId: String, val name: String) : SocketAuth
}

/** Supplies access tokens for the socket handshake (refreshing them when needed). */
fun interface AccessTokenSource {
    /** Null when signed out. [force] = the server just refused the token we had, so get a new one. */
    suspend fun accessToken(force: Boolean): String?
}

/**
 * Owns the one WebSocket and the [ConvoyState] folded from it, and publishes our own position at ~1 Hz
 * while at least one screen is visible and we are in a convoy.
 */
class ConvoyRepository(
    private val scope: CoroutineScope,
    private val socket: ConvoySocket,
    private val tracker: LocationTracker,
    private val tokens: AccessTokenSource = AccessTokenSource { null },
) {
    private val _state = MutableStateFlow(ConvoyState())
    val state: StateFlow<ConvoyState> = _state.asStateFlow()

    private val _hazards = MutableSharedFlow<ServerMessage.HazardReported>(extraBufferCapacity = 32)

    /** Every `hazard` frame from the socket (fed to [HazardRepository]). */
    val hazardMessages: SharedFlow<ServerMessage.HazardReported> = _hazards.asSharedFlow()

    private var connection: Job? = null
    private var publisher: Job? = null
    private var visibleCount = 0

    /** Connects (and keeps reconnecting with backoff). A fresh access token is fetched for every attempt. */
    fun connect(baseUrl: String, auth: SocketAuth) {
        connection?.cancel()
        connection = scope.launch {
            var attempt = 0
            var refreshedAfter401 = false
            while (isActive) {
                _state.update { it.copy(link = Link.Connecting) }
                val request = when (auth) {
                    is SocketAuth.Dev -> devSocketRequest(baseUrl, auth.userId, auth.name)
                    SocketAuth.Account -> {
                        val jwt = tokens.accessToken(force = refreshedAfter401)
                        if (jwt == null) {
                            _state.update { it.copy(link = Link.Rejected, error = "Signed out") }
                            return@launch
                        }
                        accountSocketRequest(baseUrl, jwt)
                    }
                }
                if (request == null) {
                    _state.update { it.copy(link = Link.Offline, error = "Server URL is not valid: $baseUrl") }
                    return@launch
                }
                var stop = false
                var retryNow = false
                socket.open(request).collect { event ->
                    when (event) {
                        SocketEvent.Opened -> { attempt = 0; refreshedAfter401 = false }
                        is SocketEvent.Received -> {
                            val m = event.message
                            if (m is ServerMessage.HazardReported) _hazards.tryEmit(m)
                            _state.update { it.apply(m) }
                        }
                        is SocketEvent.Closed -> if (event.code == CLOSE_SESSION_ENDED) {
                            stop = true
                            _state.update { it.copy(link = Link.Rejected, error = "Session ended") }
                        }
                        is SocketEvent.Failed -> if (event.httpStatus == 401) {
                            if (auth == SocketAuth.Account && !refreshedAfter401) {
                                // The access token probably expired: get a new one and try once more, right away.
                                refreshedAfter401 = true
                                retryNow = true
                            } else {
                                stop = true
                                _state.update {
                                    it.copy(
                                        link = Link.Rejected,
                                        error = if (auth is SocketAuth.Dev) "Server refused sign-in (is CONVOYAGE_DEV_AUTH=1 set?)" else "Server refused sign-in",
                                    )
                                }
                            }
                        } else {
                            _state.update { it.copy(error = "Cannot reach ${request.url.host}:${request.url.port}") }
                        }
                    }
                }
                if (stop) return@launch
                if (retryNow) continue
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

    /** Leader: leave rest mode and go back to the destination the convoy had. */
    fun continueFromRest() {
        val code = _state.value.activeCode ?: return
        send(ClientMessage.ConvoyRest(code, "continue"))
    }

    /**
     * Leader: set (or clear, with null) the destination. The server's rest-stop search needs a shared route and the
     * members draw it as a line, so a destination also sets a direct two-stop route from [from] to it.
     */
    fun setDestination(destination: Destination?, from: Fix?) {
        val code = _state.value.activeCode ?: return
        send(ClientMessage.ConvoyDest(code, destination))
        if (destination == null) {
            send(ClientMessage.ConvoyRouteSet(code, null))
        } else if (from != null) {
            val name = destination.label.ifBlank { "Convoy route" }.take(40)
            send(ClientMessage.ConvoyRouteSet(code, ConvoyRoute(name, listOf(listOf(from.lng, from.lat), listOf(destination.lng, destination.lat)))))
        }
    }

    /** Leader: pass the lead to [memberId]. */
    fun handOver(memberId: String) {
        val code = _state.value.activeCode ?: return
        send(ClientMessage.ConvoyLead(code, memberId))
    }

    /** Leader: put [memberId] out of the convoy (they cannot come back with the code). */
    fun remove(memberId: String) {
        val code = _state.value.activeCode ?: return
        send(ClientMessage.ConvoyRemove(code, memberId))
    }

    /** "stopping" | "moving" | "sos". True if it went out. */
    fun sendStatus(status: String, note: String? = null): Boolean {
        if (_state.value.activeCode == null) return false
        val ok = socket.send(ClientMessage.ConvoyStatus(status, note?.take(140)))
        if (!ok) _state.update { it.copy(error = "Not connected") }
        return ok
    }

    /** Sends a chat message to the active convoy. False if there is nothing to send to. */
    fun sendChat(text: String): Boolean {
        val code = _state.value.activeCode ?: return false
        if (text.isBlank()) return false
        send(ClientMessage.ConvoyChat(code, text.trim().take(500), UUID.randomUUID().toString()))
        return true
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
