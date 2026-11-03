package app.convoyage.data

import android.content.Context
import app.convoyage.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** How the person usually moves: drives, rides, or both. Chooses the default Convoy/Ride layout. */
enum class RideMode { Drive, Ride, Both }

/** Who is signed in on this device. SharedPreferences-backed; not secret (dev auth only). */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("convoyage.session", Context.MODE_PRIVATE)

    /** Stable per-install id used as `dev_user`. */
    val userId: String = prefs.getString("userId", null) ?: ("u-" + UUID.randomUUID().toString().take(8)).also {
        prefs.edit().putString("userId", it).apply()
    }

    private val _name = MutableStateFlow(prefs.getString("name", null))
    val name: StateFlow<String?> = _name.asStateFlow()

    private val _mode = MutableStateFlow(runCatching { RideMode.valueOf(prefs.getString("mode", "Drive")!!) }.getOrDefault(RideMode.Drive))
    val mode: StateFlow<RideMode> = _mode.asStateFlow()

    private val _night = MutableStateFlow(prefs.getBoolean("night", true))
    val night: StateFlow<Boolean> = _night.asStateFlow()

    private val _serverUrl = MutableStateFlow(prefs.getString("serverUrl", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.SERVER_URL)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    val signedIn: Boolean get() = !_name.value.isNullOrBlank()

    fun signIn(name: String) {
        val clean = name.trim().take(40)
        prefs.edit().putString("name", clean).apply()
        _name.value = clean
    }

    fun signOut() {
        prefs.edit().remove("name").apply()
        _name.value = null
    }

    fun setMode(mode: RideMode) {
        prefs.edit().putString("mode", mode.name).apply()
        _mode.value = mode
    }

    fun setNight(night: Boolean) {
        prefs.edit().putBoolean("night", night).apply()
        _night.value = night
    }

    fun setServerUrl(url: String) {
        val clean = url.trim().trimEnd('/')
        prefs.edit().putString("serverUrl", clean).apply()
        _serverUrl.value = clean.ifBlank { BuildConfig.SERVER_URL }
    }
}
