package app.convoyage

import android.content.Context
import app.convoyage.api.ConvoyApi
import app.convoyage.api.TokenStore
import app.convoyage.data.AccountRepository
import app.convoyage.data.AuthKind
import app.convoyage.data.ConvoyRepository
import app.convoyage.data.FeedRepository
import app.convoyage.data.GarageRepository
import app.convoyage.data.ImageLoader
import app.convoyage.data.LatLngD
import app.convoyage.data.Link
import app.convoyage.data.RideMode
import app.convoyage.data.RideRecorder
import app.convoyage.data.RideStage
import app.convoyage.data.RideStore
import app.convoyage.data.RidesRepository
import app.convoyage.data.HazardRepository
import app.convoyage.data.KeystoreTokenStore
import app.convoyage.data.LocationTracker
import app.convoyage.data.SessionStore
import app.convoyage.data.SignInOutcome
import app.convoyage.data.SocketAuth
import app.convoyage.render.VehicleThumbs
import app.convoyage.nav.NavVoice
import app.convoyage.nav.NavigationController
import app.convoyage.nav.NominatimSearch
import app.convoyage.nav.PlaceSearch
import app.convoyage.net.ConvoySocket
import app.convoyage.voice.VoiceChat
import app.convoyage.service.ConvoyService
import app.convoyage.service.CrashAlertController
import app.convoyage.service.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/** Manual dependency container (no DI framework). One instance, owned by [ConvoyageApp]. */
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val session = SessionStore(context)
    val tokenStore: TokenStore = KeystoreTokenStore(context)
    val tracker = LocationTracker(context)

    val api: ConvoyApi = ConvoyApi(
        baseUrl = { session.serverUrl.value },
        tokens = tokenStore,
        // The refresh token was refused: the session is over. Called on an OkHttp thread.
        onSessionLost = { appScope.launch { account.sessionLost(); convoy.disconnect(); hazards.clear(); clearAccountData() } },
    )
    val account = AccountRepository(api, session, appScope)
    private val routeProfile: () -> String = { if (session.mode.value == RideMode.Ride) "motorcycle" else "car" }
    val convoy = ConvoyRepository(appScope, ConvoySocket(), tracker, { force -> api.freshAccessToken(force) }, api, routeProfile)
    val hazards = HazardRepository(appScope, api, convoy.hazardMessages)

    /** Turn-by-turn: spoken prompts, the route and where we are on it. */
    val navVoice = NavVoice(context, session)
    val nav = NavigationController(appScope, api, tracker.fix, hazards.active, navVoice, routeProfile)

    init {
        convoy.setOpenCruise(session.openCruise.value)
        // Speak convoy events and (optionally) chat, as PhotoScout's convoy did, through the navigation voice.
        appScope.launch {
            var prev = convoy.state.value
            convoy.state.collect { next ->
                val notices = app.convoyage.data.ConvoyEvents.between(prev, next)
                prev = next
                notices.forEach { n ->
                    val on = if (n.message) session.convoyReadChat.value else session.convoyAnnounce.value
                    if (on) navVoice.announce(n.text)
                }
            }
        }
    }
    val places: PlaceSearch = NominatimSearch()

    /** Open Cruise on or off, remembered. */
    fun setOpenCruise(on: Boolean) {
        session.setOpenCruise(on)
        convoy.setOpenCruise(on)
    }
    /** Home / Work and recent destinations for the Drive dock. */
    val savedPlaces = app.convoyage.data.PlacesStore(context)

    /** Push-to-talk to the convoy (LiveKit). Connects while a convoy is on. */
    val voice = VoiceChat(context, appScope, { code -> api.voiceToken(code) })
    val garage = GarageRepository(appScope, api)
    val rides = RidesRepository(appScope, api)
    val savedRoutes = app.convoyage.data.RoutesRepository(appScope, api)
    val images = ImageLoader(appScope, api)

    /** Static PS1 vehicle renders (avatar badges, cards): one shared offscreen renderer, cached by build. */
    val thumbs = VehicleThumbs(context)
    val feed = FeedRepository(appScope, api, location = { tracker.lastKnown()?.let { LatLngD(it.lat, it.lng) } })

    /** Live lean angle; held by Ride mode while it is on screen (the phone is on the bike there). */
    val lean = app.convoyage.data.LeanSensor(context) {
        tracker.fix.value?.takeIf { System.currentTimeMillis() - it.timeMs < 5_000 }?.speedMps
    }

    /** The ride being recorded (or waiting to upload). Saved on disk as it goes. */
    val recorder = RideRecorder(
        scope = appScope,
        store = RideStore(File(context.filesDir, "ride")),
        fixes = tracker.fix,
        api = api,
        lean = lean.lean,
        acquireTracker = tracker::acquire,
        releaseTracker = tracker::release,
        preparePhoto = { ImageLoader.prepareForUpload(it, context.cacheDir) },
    )

    /** Notifications without Google services: UnifiedPush, or our own socket kept alive. */
    val push = app.convoyage.push.PushManager(
        context, appScope, api,
        mode = { session.pushMode.value },
        signedIn = { session.kind.value == AuthKind.Account && tokenStore.tokens != null },
    )

    /** The "Are you OK?" countdown after a detected (or, in debug builds, simulated) crash. */
    val crash = CrashAlertController(context, appScope, convoy, tracker, session)

    /** A convoy code from a `convoyage://join/<CODE>` link that is waiting for sign-in and a connection. */
    private val _pendingJoin = MutableStateFlow<String?>(null)
    val pendingJoin: StateFlow<String?> = _pendingJoin
    fun setPendingJoin(code: String?) { _pendingJoin.value = code }

    /** Starts the foreground service if a convoy or a ride is on and the location permission is there. */
    fun ensureService() {
        if (!tracker.hasPermission()) return
        if (convoy.state.value.activeCode != null || recorder.state.value.isActive || nav.state.value.isOn) ConvoyService.start(context)
    }

    init {
        Notifications.ensureChannels(context)
        appScope.launch {
            convoy.notifications.collect { n ->
                val type = n.data["type"]
                if ((type == "sos" || type == "hazard") && convoy.isOnScreen) return@collect
                Notifications.notice(context, n.title, n.body, n.data)
            }
        }
        appScope.launch { session.pushMode.drop(1).collect { push.apply() } }
        appScope.launch {
            combine(convoy.state.map { it.activeCode != null }, recorder.state.map { it.isActive }, nav.state.map { it.isOn }) { a, b, c -> a || b || c }
                .distinctUntilChanged()
                .collect { on -> if (on) ensureService() }
        }
        // Push-to-talk: a voice room per convoy, joined with the convoy and left with it.
        appScope.launch {
            convoy.state.map { if (it.link == Link.Online) it.activeCode else null }.distinctUntilChanged().collect { code ->
                if (code != null) voice.join(code) else voice.leave()
            }
        }
        recorder.restore()
        // "Record convoy rides automatically": a convoy appearing starts a ride, the convoy going away stops it.
        appScope.launch {
            var previous: String? = null
            convoy.state.map { it.activeCode }.distinctUntilChanged().collect { code ->
                val before = previous
                previous = code
                val rec = recorder.state.value
                if (code != null && before == null && session.autoRecordConvoy.value && !rec.isActive && tracker.hasPermission()) {
                    val v = garage.state.value.primary
                    recorder.start(v?.id, v?.displayName, code, tracker.fix.value, auto = true)
                } else if (code == null && before != null && rec.isActive && rec.meta?.autoStarted == true && rec.meta.convoyCode == before) {
                    recorder.stop()
                }
            }
        }
    }

    /** Loads the person's own data after sign-in (or at startup when already signed in). */
    fun refreshAccountData() {
        garage.refresh()
    }

    /** Forgets everything that belongs to the signed-in person. A ride that has not uploaded stays on disk. */
    private fun clearAccountData() {
        garage.clear()
        rides.clear()
        feed.clear()
        images.clear()
        recorder.dismiss()
    }

    /** (Re)connects the socket with the stored identity and server URL, if signed in. [onlyIfDown]: leave a live connection be. */
    fun connectIfSignedIn(onlyIfDown: Boolean = false) {
        if (onlyIfDown && convoy.isConnecting) return
        val name = session.name.value ?: return
        if (!onlyIfDown) push.apply()
        when (session.kind.value) {
            AuthKind.Account -> if (tokenStore.tokens != null) {
                convoy.connect(session.serverUrl.value, SocketAuth.Account)
                refreshAccountData()
            }
            AuthKind.Dev -> convoy.connect(session.serverUrl.value, SocketAuth.Dev(session.userId, name))
            null -> Unit
        }
    }

    /** Verifies a magic link (or bare token) and, on success, brings the socket up. Shared by the form and the deep link. */
    suspend fun signInWithLink(tokenOrLink: String): SignInOutcome {
        val outcome = account.verify(tokenOrLink)
        if (outcome is SignInOutcome.SignedIn) connectIfSignedIn()
        return outcome
    }

    /** Signs out on this device now; the server hears about it in the background. */
    fun signOut() {
        push.signOut()
        convoy.disconnect()
        hazards.clear()
        clearAccountData()
        account.logout()
    }
}
