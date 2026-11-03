package app.convoyage.data

import app.convoyage.api.ApiResult
import app.convoyage.api.RouteApi
import app.convoyage.nav.NavMath
import app.convoyage.nav.NavRoute
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

/** A route the leader fetched itself, kept for the destination it was fetched for. */
data class LocalRoute(val code: String, val destination: Destination, val points: List<LatLngD>)

/** A saved route the leader shared: [staging] is where everyone gathers, [end] where the route finishes. */
data class SharedSavedRoute(val code: String, val route: app.convoyage.api.SavedRoute, val staging: Destination?, val end: Destination)

private class PendingStart(val destination: Destination, val route: NavRoute, val from: Fix?, val knownRooms: Set<String>)

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
    /** Road routing for the leader's own route line when the server did not send one. Null = straight lines only. */
    private val routes: RouteApi? = null,
    private val profile: () -> String = { "car" },
) {
    private val _state = MutableStateFlow(ConvoyState())
    val state: StateFlow<ConvoyState> = _state.asStateFlow()

    private val _hazards = MutableSharedFlow<ServerMessage.HazardReported>(extraBufferCapacity = 32)

    /** Every `hazard` frame from the socket (fed to [HazardRepository]). */
    val hazardMessages: SharedFlow<ServerMessage.HazardReported> = _hazards.asSharedFlow()

    private val _localRoute = MutableStateFlow<LocalRoute?>(null)

    /** The road route the leader fetched itself for the current destination (used when the room carries no `routeGeometry`). */
    val localRoute: StateFlow<LocalRoute?> = _localRoute.asStateFlow()

    /** A convoy to start the moment the server answers `convoy.create`: it gets this destination and route. */
    private var pendingStart: PendingStart? = null

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
                            // The server forgets Open Cruise when the socket drops: say it again on every hello.
                            if (m is ServerMessage.Hello && wantOpenCruise) socket.send(ClientMessage.CruiseOpen(true))
                            _state.update { it.apply(m) }
                            startPendingIfReady()
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
     * members draw it as a line, so a destination also shares a route: [route] (navigation's, if given), else one the
     * leader fetches from `/v1/route`, else a direct two-stop line from [from].
     *
     * The route goes BEFORE the destination on purpose: the server starts fetching its own road-following
     * `routeGeometry` when the destination arrives, and any later `convoy.route` would make that fetch obsolete.
     */
    fun setDestination(destination: Destination?, from: Fix?, route: NavRoute? = null) {
        val code = _state.value.activeCode ?: return
        _sharedRoute.value = null
        if (destination == null) {
            _localRoute.value = null
            send(ClientMessage.ConvoyDest(code, null))
            send(ClientMessage.ConvoyRouteSet(code, null))
            return
        }
        if (from == null) {
            send(ClientMessage.ConvoyDest(code, destination))
            return
        }
        scope.launch {
            val nav = route ?: fetchRoute(from, destination)
            _localRoute.value = nav?.let { LocalRoute(code, destination, it.points) }
            val stops = nav?.let { NavMath.sampleStops(it) } ?: listOf(listOf(from.lng, from.lat), listOf(destination.lng, destination.lat))
            val name = destination.label.ifBlank { "Convoy route" }.take(40)
            send(ClientMessage.ConvoyRouteSet(code, ConvoyRoute(name, stops)))
            send(ClientMessage.ConvoyDest(code, destination))
        }
    }

    private suspend fun fetchRoute(from: Fix, to: Destination): NavRoute? {
        val api = routes ?: return null
        val r = api.route(LatLngD(from.lat, from.lng), LatLngD(to.lat, to.lng), profile())
        return (r as? ApiResult.Ok)?.let { NavRoute.from(it.value) }
    }

    /**
     * Starts a convoy that already has a destination and route (navigation's "Invite friends"). The convoy is created
     * first; when the server's `room` answer arrives and we lead it, the destination and route are sent.
     */
    fun startConvoyWithRoute(name: String, destination: Destination, route: NavRoute, from: Fix?) {
        pendingStart = PendingStart(destination, route, from, _state.value.rooms.keys)
        createConvoy(name)
    }

    /** Starts a convoy named after a saved route; the route is shared once the server answers and we lead the room. */
    fun startConvoyWithSavedRoute(route: app.convoyage.api.SavedRoute, from: Fix?) {
        pendingSaved = Triple(route, from, _state.value.rooms.keys)
        createConvoy(route.name.take(40))
    }

    private var pendingSaved: Triple<app.convoyage.api.SavedRoute, Fix?, Set<String>>? = null

    private fun startPendingIfReady() {
        pendingSaved?.let { (route, from, known) ->
            val s = _state.value
            val room = s.active
            if (room != null && room.code !in known && s.meId != null && room.leader == s.meId) {
                pendingSaved = null
                shareSavedRoute(route, from)
            }
        }
        val p = pendingStart ?: return
        val s = _state.value
        val room = s.active ?: return
        if (room.code in p.knownRooms || s.meId == null || room.leader != s.meId) return
        pendingStart = null
        setDestination(p.destination, p.from, p.route)
    }

    private val _sharedRoute = MutableStateFlow<SharedSavedRoute?>(null)

    /** The saved route this device (as leader) shared in the active convoy, with its staging point. */
    val sharedRoute: StateFlow<SharedSavedRoute?> = _sharedRoute.asStateFlow()

    /**
     * Leader: drive a saved route. The route's convoy stops are shared (route BEFORE destination, see [setDestination]),
     * then the destination: its staging point when it has one and the leader is not there yet (everyone gathers
     * first), else where the route ends. The server routes its line through the stops still ahead.
     */
    fun shareSavedRoute(route: app.convoyage.api.SavedRoute, from: Fix?) {
        val code = _state.value.activeCode ?: return
        if (route.convoyStops.size < 2) return
        val stops = route.convoyStops
        val name = route.name.take(40)
        val staging = route.staging?.let { Destination(it.lat, it.lng, "$name staging".take(80)) }
        val atStaging = staging != null && from != null && Geo.distanceM(from.lat, from.lng, staging.lat, staging.lng) < 300
        val end = Destination(stops.last()[1], stops.last()[0], name)
        val dest = if (staging != null && !atStaging) staging else end
        val road = route.geometry?.let { runCatching { Polyline.decode(it, 6) }.getOrNull() }.orEmpty()
        _sharedRoute.value = SharedSavedRoute(code, route, staging, end)
        _localRoute.value = if (dest == end && road.size >= 2) LocalRoute(code, end, road) else null
        send(ClientMessage.ConvoyRouteSet(code, ConvoyRoute(name, stops)))
        send(ClientMessage.ConvoyDest(code, dest))
    }

    /** Leader: everyone is at the staging point; set off along the shared route to its end. */
    fun rollOut() {
        val shared = _sharedRoute.value ?: return
        val code = _state.value.activeCode ?: return
        if (shared.code != code) return
        val road = shared.route.geometry?.let { runCatching { Polyline.decode(it, 6) }.getOrNull() }.orEmpty()
        _localRoute.value = if (road.size >= 2) LocalRoute(code, shared.end, road) else null
        send(ClientMessage.ConvoyDest(code, shared.end))
    }

    /** Leader: rename the convoy. */
    fun rename(name: String) {
        val code = _state.value.activeCode ?: return
        val clean = name.trim().take(40)
        if (clean.isNotEmpty()) send(ClientMessage.ConvoyRename(code, clean))
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

    // ---- Open Cruise -------------------------------------------------------------------------------

    /** Whether the user wants Open Cruise on (kept in settings; re-sent after every reconnect). */
    @Volatile var wantOpenCruise: Boolean = false
        private set

    fun setOpenCruise(on: Boolean) {
        wantOpenCruise = on
        if (_state.value.link == Link.Online) send(ClientMessage.CruiseOpen(on))
        if (!on) _state.update { it.copy(openCruise = false, nearbyDrivers = emptyList(), nearbyConvoys = emptyList()) }
    }

    fun inviteNearby(driverId: String) = send(ClientMessage.CruiseInvite(driverId))
    fun joinOpen(code: String) = send(ClientMessage.CruiseJoin(code))
    fun dismissInvite() = _state.update { it.copy(invite = null) }

    /** Leader: open the convoy to Open Cruise drivers nearby, or close it. */
    fun setConvoyOpen(open: Boolean) {
        val code = _state.value.activeCode ?: return
        send(ClientMessage.ConvoyOpen(code, open))
    }

    /** Leader: no destination, no route, the convoy just drives together (PhotoScout PR #14). */
    fun justCruise() {
        val code = _state.value.activeCode ?: return
        _sharedRoute.value = null
        _localRoute.value = null
        send(ClientMessage.ConvoyRouteSet(code, null))
        send(ClientMessage.ConvoyDest(code, null))
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
                if (s.link != Link.Online || (s.rooms.isEmpty() && !s.openCruise)) continue
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
