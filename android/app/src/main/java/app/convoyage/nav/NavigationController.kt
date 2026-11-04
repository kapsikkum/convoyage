package app.convoyage.nav

import app.convoyage.api.ApiResult
import app.convoyage.api.RouteApi
import app.convoyage.data.Fix
import app.convoyage.data.Geo
import app.convoyage.data.LatLngD
import app.convoyage.protocol.Hazard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NavPhase { Idle, Routing, Preview, Active, Arrived }

/** Everything the navigation screen draws. [progress] is null until the first fix after Start. */
data class NavState(
    val phase: NavPhase = NavPhase.Idle,
    val destination: Place? = null,
    val route: NavRoute? = null,
    val progress: NavProgress? = null,
    val speedMps: Double = 0.0,
    /** Where the camera should point: the route's direction while we are on it, else our own heading. Null = unknown. */
    val bearing: Double? = null,
    val rerouting: Boolean = false,
    val error: String? = null,
    val routeHazards: List<RouteHazard> = emptyList(),
    val hazardAhead: HazardAhead? = null,
    /** Estimated arrival, ms since the epoch (null before the route is known). */
    val etaMs: Long? = null,
    /** Hazard and camera alerts are not spoken (the banner still shows). */
    val alertsMuted: Boolean = false,
    /** On a detour: where to carry on to afterwards (the convoy's destination or the drive we left). */
    val resumeTo: Place? = null,
) {
    val isOn: Boolean get() = phase == NavPhase.Active || phase == NavPhase.Arrived

    /** The maneuver ahead: the card's headline, distance and icon. */
    val maneuver: RouteStep? get() = progress?.nextIndex?.let { route?.steps?.getOrNull(it) } ?: route?.steps?.lastOrNull()

    /** The one after that, for the "then" line. */
    val thenStep: RouteStep? get() = progress?.nextIndex?.let { route?.steps?.getOrNull(it + 1) }

    val remainingM: Double get() = progress?.remainingM ?: route?.distanceM ?: 0.0
    val remainingS: Double get() = progress?.remainingS ?: route?.durationS ?: 0.0
}

/**
 * Turn-by-turn navigation: fetches the route, follows the fixes along it, advances steps, speaks prompts, watches for
 * hazards ahead and reroutes when we leave the line. One instance for the app; the screen only reads [state].
 */
class NavigationController(
    private val scope: CoroutineScope,
    private val api: RouteApi,
    fixes: StateFlow<Fix?>,
    hazards: StateFlow<List<Hazard>>,
    private val speaker: Speaker,
    /** "car" or "motorcycle", from the person's drive/ride preference. */
    private val profile: () -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state.asStateFlow()

    private val prompts = PromptScheduler()
    private val offRoute = OffRouteDetector()
    private var lastSeg = 0
    private var stepIndex = 0
    private var prev: Fix? = null
    private var knownHazards: List<Hazard> = emptyList()
    private var fetch: Job? = null
    private var announcedArrival = false
    /** Hazards closed from the banner for this drive. */
    private val dismissed = mutableSetOf<String>()
    private var spokenHazard: String? = null
    /** The way we are actually moving, from the device or from the last few metres travelled. */
    private var travelHeading: Double? = null
    private var headingFrom: Fix? = null

    /** Device heading while moving; otherwise the bearing over the last 8 m or more. Stale after 5 minutes. */
    private fun trackHeading(fix: Fix) {
        val h = fix.headingDeg
        if (h != null && h != 0.0 && (fix.speedMps ?: 0.0) >= 3.0) {
            travelHeading = h
            headingFrom = fix
            return
        }
        val a = headingFrom ?: run { headingFrom = fix; return }
        if (Geo.distanceM(a.lat, a.lng, fix.lat, fix.lng) >= 8.0) {
            travelHeading = Geo.bearingDeg(a.lat, a.lng, fix.lat, fix.lng)
            headingFrom = fix
        } else if (fix.timeMs - a.timeMs > 5 * 60_000) {
            travelHeading = null
        }
    }

    /** The heading to route with, when we are moving or have just been. */
    fun heading(): Double? = travelHeading

    private fun ahead(onRoute: List<RouteHazard>, along: Double): HazardAhead? =
        RouteHazards.nextAhead(onRoute.filterNot { it.hazard.id in dismissed }, along)

    /** Close the alert for one hazard for the rest of this drive. */
    fun dismissHazard(id: String) {
        dismissed += id
        _state.update { it.copy(hazardAhead = it.hazardAhead?.takeIf { a -> a.hazard.id != id }) }
    }

    fun toggleAlertsMuted() = _state.update { it.copy(alertsMuted = !it.alertsMuted) }

    /** Speak a hazard the first time it comes within warning range. */
    private fun announceHazard() {
        val s = _state.value
        val a = s.hazardAhead ?: return
        if (a.hazard.id == spokenHazard) return
        spokenHazard = a.hazard.id
        if (!s.alertsMuted) speaker.speak(RouteHazards.spoken(a))
    }

    init {
        scope.launch { fixes.collect { if (it != null) onFix(it) } }
        scope.launch {
            hazards.collect {
                knownHazards = it
                refreshHazards()
            }
        }
    }

    /**
     * Asks for a route from [from] to [place] and shows it as a preview (nothing is spoken until [start]), or goes
     * straight into guidance with [autoStart]. [resumeTo] marks this as a detour that carries on there afterwards.
     */
    fun navigateTo(place: Place, from: Fix?, autoStart: Boolean = false, resumeTo: Place? = null) {
        fetch?.cancel()
        dismissed.clear()
        spokenHazard = null
        if (from == null) {
            _state.update { it.copy(error = "Waiting for your location") }
            return
        }
        val muted = _state.value.alertsMuted
        _state.value = NavState(phase = NavPhase.Routing, destination = place, resumeTo = resumeTo, alertsMuted = muted)
        resetProgress()
        trackHeading(from)
        if (prev == null) prev = from
        fetch = scope.launch {
            when (val r = api.route(LatLngD(from.lat, from.lng), LatLngD(place.lat, place.lng), profile(), headingDeg = travelHeading)) {
                is ApiResult.Ok -> {
                    val route = NavRoute.from(r.value)
                    if (route == null) {
                        _state.update { it.copy(phase = NavPhase.Idle, error = "No route found") }
                    } else {
                        _state.update { it.copy(phase = NavPhase.Preview, route = route, etaMs = nowMs() + (route.durationS * 1000).toLong()) }
                        refreshHazards()
                        if (autoStart) start()
                    }
                }
                is ApiResult.Err -> _state.update {
                    it.copy(
                        phase = NavPhase.Idle,
                        error = if (r.status == 404) "No route to there" else r.message,
                    )
                }
            }
        }
    }

    /**
     * A stop on the way: guide to [place] now, then offer to carry on to [resume] (or to wherever we were already
     * going). Starts at once, no preview.
     */
    fun detour(place: Place, from: Fix?, resume: Place? = null) {
        val s = _state.value
        val back = resume ?: s.resumeTo ?: s.destination?.takeIf { s.phase != NavPhase.Idle }
        navigateTo(place, from, autoStart = true, resumeTo = back)
    }

    /** After a detour: back on to where we were going. */
    fun resume(from: Fix?) {
        val to = _state.value.resumeTo ?: return
        navigateTo(to, from ?: prev, autoStart = true)
    }

    /** Preview to active guidance. */
    fun start() {
        val s = _state.value
        if (s.phase != NavPhase.Preview || s.route == null) return
        resetProgress()
        _state.update { it.copy(phase = NavPhase.Active, error = null) }
        prev?.let { onFix(it) }
    }

    /** Stops navigating and forgets the route. */
    fun end() {
        fetch?.cancel()
        resetProgress()
        _state.value = NavState()
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** The reverse-geocoded name of a dropped pin arrived: use it if the destination is still that pin. */
    fun relabel(lat: Double, lng: Double, name: String, detail: String) = _state.update { s ->
        val d = s.destination
        if (d != null && d.lat == lat && d.lng == lng) s.copy(destination = d.copy(name = name, detail = detail)) else s
    }

    private fun resetProgress() {
        prompts.reset()
        offRoute.reset()
        lastSeg = 0
        stepIndex = 0
        announcedArrival = false
    }

    /** A new position. Public so a test can feed positions without a location provider. */
    fun onFix(fix: Fix) {
        val before = prev
        prev = fix
        trackHeading(fix)
        val s = _state.value
        if (s.phase != NavPhase.Active) return
        val route = s.route ?: return
        val speed = speedOf(fix, before)
        val snap = NavMath.snap(route, fix.lat, fix.lng, lastSeg)
        lastSeg = snap.seg
        val p = NavEngine.progress(route, snap, stepIndex)
        stepIndex = p.stepIndex
        val onLine = p.offRouteM <= OffRouteDetector.DEFAULT_THRESHOLD_M
        val bearing = if (onLine) NavMath.routeBearing(route, snap) else fix.headingDeg
        val now = nowMs()

        if (p.arrived) {
            if (!announcedArrival) {
                announcedArrival = true
                speaker.speak("You have arrived" + (s.destination?.name?.takeIf { it.isNotBlank() && it != "Dropped pin" }?.let { " at $it" } ?: ""))
            }
            _state.update { it.copy(phase = NavPhase.Arrived, progress = p, speedMps = speed, bearing = bearing, hazardAhead = null, etaMs = now) }
            return
        }

        if (onLine) {
            prompts.update(p.nextIndex, p.nextIndex?.let { route.steps.getOrNull(it) }, p.distToManeuverM, speed)?.let { speaker.speak(it.text) }
        }
        val lost = offRoute.update(p.offRouteM, now)

        _state.update {
            it.copy(
                progress = p,
                speedMps = speed,
                bearing = bearing,
                hazardAhead = ahead(it.routeHazards, snap.along),
                etaMs = now + (p.remainingS * 1000).toLong(),
            )
        }
        announceHazard()
        if (lost) reroute(fix)
    }

    private fun reroute(from: Fix) {
        val dest = _state.value.destination ?: return
        if (fetch?.isActive == true) return
        speaker.speak("Rerouting")
        _state.update { it.copy(rerouting = true) }
        fetch = scope.launch {
            when (val r = api.route(LatLngD(from.lat, from.lng), LatLngD(dest.lat, dest.lng), profile(), headingDeg = travelHeading)) {
                is ApiResult.Ok -> {
                    val route = NavRoute.from(r.value)
                    if (route == null) {
                        _state.update { it.copy(rerouting = false, error = "Could not reroute") }
                    } else {
                        resetProgress()
                        _state.update { it.copy(route = route, progress = null, rerouting = false, error = null) }
                        refreshHazards()
                        prev?.let { onFix(it) }
                    }
                }
                is ApiResult.Err -> _state.update { it.copy(rerouting = false, error = "Could not reroute: ${r.message}") }
            }
        }
    }

    private fun refreshHazards() {
        _state.update { s ->
            val route = s.route
            val onRoute = if (route == null) emptyList() else RouteHazards.onRoute(route, knownHazards)
            val along = s.progress?.snap?.along
            s.copy(routeHazards = onRoute, hazardAhead = if (along != null) ahead(onRoute, along) else null)
        }
    }

    /** The fix's own speed when it has one, else worked out from the last two fixes (emulator fixes carry no speed). */
    private fun speedOf(fix: Fix, before: Fix?): Double {
        fix.speedMps?.let { if (it > 0.0 || before == null) return it.coerceIn(0.0, 70.0) }
        before ?: return 0.0
        val dt = (fix.timeMs - before.timeMs) / 1000.0
        if (dt < 0.4 || dt > 15.0) return _state.value.speedMps
        return (Geo.distanceM(before.lat, before.lng, fix.lat, fix.lng) / dt).coerceIn(0.0, 70.0)
    }
}
