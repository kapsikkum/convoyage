package app.convoyage.data

import android.content.Context
import app.convoyage.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** How the person usually moves: drives, rides, or both. Chooses the default Convoy/Ride layout. */
enum class RideMode { Drive, Ride, Both }

/** How this device is signed in: a real [Account] (JWT in the token store) or the debug-only [Dev] socket auth. */
enum class AuthKind { Account, Dev }

/**
 * Who is signed in on this device. SharedPreferences-backed and NOT secret: the tokens live in
 * [KeystoreTokenStore]; this only holds the display name and preferences.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("convoyage.session", Context.MODE_PRIVATE)

    /** Stable per-install id used as `dev_user`. */
    val userId: String = prefs.getString("userId", null) ?: ("u-" + UUID.randomUUID().toString().take(8)).also {
        prefs.edit().putString("userId", it).apply()
    }

    private val _name = MutableStateFlow(prefs.getString("name", null))
    val name: StateFlow<String?> = _name.asStateFlow()

    private val _kind = MutableStateFlow(
        runCatching { AuthKind.valueOf(prefs.getString("authKind", null)!!) }.getOrNull()
            // Sessions from before accounts existed were dev sessions.
            ?: if (prefs.getString("name", null) != null) AuthKind.Dev else null,
    )
    val kind: StateFlow<AuthKind?> = _kind.asStateFlow()

    private val _mode = MutableStateFlow(runCatching { RideMode.valueOf(prefs.getString("mode", "Drive")!!) }.getOrDefault(RideMode.Drive))
    val mode: StateFlow<RideMode> = _mode.asStateFlow()

    private val _night = MutableStateFlow(prefs.getBoolean("night", true))
    val night: StateFlow<Boolean> = _night.asStateFlow()

    private val _serverUrl = MutableStateFlow(prefs.getString("serverUrl", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.SERVER_URL)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _autoRecord = MutableStateFlow(prefs.getBoolean("autoRecordConvoy", false))

    /** When on, joining or starting a convoy starts recording a ride, and leaving the convoy stops it. */
    val autoRecordConvoy: StateFlow<Boolean> = _autoRecord.asStateFlow()

    private val _crash = MutableStateFlow(prefs.getBoolean("crashDetection", false))

    /** Opt-in: watch for a crash while in a convoy or recording a ride. */
    val crashDetection: StateFlow<Boolean> = _crash.asStateFlow()

    private val _bgSeen = MutableStateFlow(prefs.getBoolean("bgRationaleSeen", false))

    /** The background-location rationale has been shown (and answered) once; do not nag. */
    val bgRationaleSeen: StateFlow<Boolean> = _bgSeen.asStateFlow()

    private val _navVoice = MutableStateFlow(prefs.getBoolean("navVoice", true))

    /** Spoken turn-by-turn prompts on or off (the mute button on the maneuver card flips it). */
    val navVoice: StateFlow<Boolean> = _navVoice.asStateFlow()

    private val _navRate = MutableStateFlow(prefs.getFloat("navVoiceRate", 1.0f))

    /** Text-to-speech rate for prompts: 1.0 is normal. */
    val navVoiceRate: StateFlow<Float> = _navRate.asStateFlow()

    private val _navVoiceName = MutableStateFlow(prefs.getString("navVoiceName", null))

    /** The chosen text-to-speech voice, or null for the engine's default. */
    val navVoiceName: StateFlow<String?> = _navVoiceName.asStateFlow()

    private val _announce = MutableStateFlow(prefs.getBoolean("convoyAnnounce", true))

    /** Speak convoy events: joins and leaves, a new leader, destination, rest stop and route changes, stopping, SOS. */
    val convoyAnnounce: StateFlow<Boolean> = _announce.asStateFlow()

    private val _readChat = MutableStateFlow(prefs.getBoolean("convoyReadChat", false))

    /** Read other members' convoy messages aloud. */
    val convoyReadChat: StateFlow<Boolean> = _readChat.asStateFlow()

    private val _pushMode = MutableStateFlow(
        runCatching { app.convoyage.push.PushMode.valueOf(prefs.getString("pushMode", null)!!) }.getOrDefault(app.convoyage.push.PushMode.UnifiedPush),
    )

    /** How notifications reach this phone (no Google push on F-Droid). */
    val pushMode: StateFlow<app.convoyage.push.PushMode> = _pushMode.asStateFlow()

    fun setPushMode(mode: app.convoyage.push.PushMode) {
        prefs.edit().putString("pushMode", mode.name).apply()
        _pushMode.value = mode
    }

    private val _openCruise = MutableStateFlow(prefs.getBoolean("openCruise", false))
    /** Open Cruise: show me to drivers nearby who have it on too. Off unless chosen. */
    val openCruise: StateFlow<Boolean> = _openCruise.asStateFlow()

    fun setOpenCruise(on: Boolean) {
        prefs.edit().putBoolean("openCruise", on).apply()
        _openCruise.value = on
    }

    fun setConvoyAnnounce(on: Boolean) {
        prefs.edit().putBoolean("convoyAnnounce", on).apply()
        _announce.value = on
    }

    fun setConvoyReadChat(on: Boolean) {
        prefs.edit().putBoolean("convoyReadChat", on).apply()
        _readChat.value = on
    }

    val signedIn: Boolean get() = !_name.value.isNullOrBlank()

    init {
        // Dev sign-in only exists in debug builds; a release build must never come up "signed in" with it.
        if (_kind.value == AuthKind.Dev && !BuildConfig.DEBUG) signOut()
    }

    fun signIn(name: String, kind: AuthKind) {
        val clean = name.trim().take(40)
        prefs.edit().putString("name", clean).putString("authKind", kind.name).apply()
        _name.value = clean
        _kind.value = kind
    }

    /** Display name changed (profile edit, or fetched after sign-in) without changing how we are signed in. */
    fun setName(name: String) {
        val clean = name.trim().take(60)
        if (clean.isEmpty()) return
        prefs.edit().putString("name", clean).apply()
        _name.value = clean
    }

    fun signOut() {
        prefs.edit().remove("name").remove("authKind").apply()
        _name.value = null
        _kind.value = null
    }

    fun setMode(mode: RideMode) {
        prefs.edit().putString("mode", mode.name).apply()
        _mode.value = mode
    }

    fun setCrashDetection(on: Boolean) {
        prefs.edit().putBoolean("crashDetection", on).apply()
        _crash.value = on
    }

    fun setBgRationaleSeen(seen: Boolean) {
        prefs.edit().putBoolean("bgRationaleSeen", seen).apply()
        _bgSeen.value = seen
    }

    fun setNavVoice(on: Boolean) {
        prefs.edit().putBoolean("navVoice", on).apply()
        _navVoice.value = on
    }

    fun setNavVoiceRate(rate: Float) {
        prefs.edit().putFloat("navVoiceRate", rate).apply()
        _navRate.value = rate
    }

    fun setNavVoiceName(name: String?) {
        prefs.edit().apply { if (name == null) remove("navVoiceName") else putString("navVoiceName", name) }.apply()
        _navVoiceName.value = name
    }

    fun setNight(night: Boolean) {
        prefs.edit().putBoolean("night", night).apply()
        _night.value = night
    }

    fun setAutoRecordConvoy(on: Boolean) {
        prefs.edit().putBoolean("autoRecordConvoy", on).apply()
        _autoRecord.value = on
    }

    /** Saves the server address (any build). Blank goes back to the build's default. */
    fun setServerUrl(url: String) {
        val clean = url.trim().trimEnd('/')
        prefs.edit().putString("serverUrl", clean).apply()
        _serverUrl.value = clean.ifBlank { BuildConfig.SERVER_URL }
    }
}
