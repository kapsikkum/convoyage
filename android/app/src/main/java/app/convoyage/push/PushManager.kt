package app.convoyage.push

import android.content.Context
import app.convoyage.api.ApiJson
import app.convoyage.api.ApiResult
import app.convoyage.api.ConvoyApi
import app.convoyage.api.PushDevice
import app.convoyage.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.unifiedpush.android.connector.UnifiedPush

/**
 * How notifications reach the phone. The app ships on F-Droid, so there is no Google push:
 *  - [UnifiedPush]: a distributor app (ntfy, NextPush, ...) holds the one connection for every app on the phone.
 *    Best for battery. Needs a distributor installed.
 *  - [StayConnected]: our own socket kept open by a quiet foreground service. Works with nothing else
 *    installed, costs some battery.
 *  - [Off].
 */
enum class PushMode { UnifiedPush, StayConnected, Off }

/** What a push carries (the server's PushPayload), the same as a socket `notify`. */
@Serializable
data class PushContent(val title: String, val body: String, val data: Map<String, String> = emptyMap())

/** A UnifiedPush distributor app on this phone. */
data class Distributor(val pkg: String, val label: String)

data class PushStatus(
    val mode: PushMode = PushMode.UnifiedPush,
    val distributors: List<Distributor> = emptyList(),
    /** The distributor in use, if any. */
    val current: String? = null,
    /** The server has our endpoint. */
    val registered: Boolean = false,
    val error: String? = null,
) {
    /** Several distributors and none chosen yet: the user has to pick one. */
    val needsPick: Boolean get() = mode == PushMode.UnifiedPush && current == null && distributors.size > 1
}

class PushManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val api: ConvoyApi,
    private val mode: () -> PushMode,
    private val signedIn: () -> Boolean,
) {
    private val prefs = context.getSharedPreferences("push", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(PushStatus(mode = mode()))
    val status: StateFlow<PushStatus> = _status.asStateFlow()

    /** Brings the chosen mode into effect: registers or unregisters, starts or stops the keep-alive service. */
    fun apply() {
        val m = mode()
        refresh(m)
        if (!signedIn()) {
            KeepAliveService.stop(context)
            return
        }
        when (m) {
            PushMode.UnifiedPush -> {
                KeepAliveService.stop(context)
                registerWithDistributor()
            }
            PushMode.StayConnected -> {
                dropUnifiedPush()
                KeepAliveService.start(context)
            }
            PushMode.Off -> {
                dropUnifiedPush()
                KeepAliveService.stop(context)
            }
        }
    }

    /** The user chose [pkg] among several distributors. */
    fun pick(pkg: String) {
        UnifiedPush.saveDistributor(context, pkg)
        UnifiedPush.register(context, messageForDistributor = "Convoyage")
        refresh(mode())
    }

    /** Signing out: the endpoint dies with the registration, so the server drops it on its next send. */
    fun signOut() {
        KeepAliveService.stop(context)
        if (UnifiedPush.getSavedDistributor(context) != null) runCatching { UnifiedPush.unregister(context) }
        prefs.edit().clear().apply()
        refresh(mode())
    }

    // ---- from the UnifiedPush service ----------------------------------------------------------

    fun onNewEndpoint(url: String, p256dh: String?, auth: String?) {
        if (p256dh == null || auth == null) {
            // Without keys the server cannot encrypt, and it will not send in the clear.
            _status.update { it.copy(registered = false, error = "This distributor does not support encrypted push") }
            return
        }
        prefs.edit().putString("endpoint", url).putString("p256dh", p256dh).putString("auth", auth).putBoolean("sent", false).apply()
        sendEndpoint()
    }

    fun onUnregistered() {
        prefs.edit().clear().apply()
        refresh(mode())
    }

    fun onRegistrationFailed(reason: String) {
        _status.update { it.copy(registered = false, error = "Distributor refused: $reason") }
    }

    fun onMessage(bytes: ByteArray, decrypted: Boolean) {
        if (!decrypted) return // never show what the distributor could have forged or read
        val c = runCatching { ApiJson.decodeFromString(PushContent.serializer(), bytes.decodeToString()) }.getOrNull() ?: return
        Notifications.notice(context, c.title, c.body, c.data)
    }

    // ---- internals ------------------------------------------------------------------------------

    private fun registerWithDistributor() {
        val all = UnifiedPush.getDistributors(context)
        val saved = (UnifiedPush.getAckDistributor(context) ?: UnifiedPush.getSavedDistributor(context))?.takeIf { it in all }
        val pick = saved ?: all.singleOrNull()
        if (pick == null) return // none installed, or the user has to choose (see needsPick)
        if (saved == null) UnifiedPush.saveDistributor(context, pick)
        // Re-registering is cheap and returns the same endpoint; it also re-sends it after a server change.
        UnifiedPush.register(context, messageForDistributor = "Convoyage")
        if (prefs.getString("endpoint", null) != null) sendEndpoint()
        refresh(mode())
    }

    private fun dropUnifiedPush() {
        val endpoint = prefs.getString("endpoint", null)
        if (UnifiedPush.getSavedDistributor(context) != null) runCatching { UnifiedPush.unregister(context) }
        prefs.edit().clear().apply()
        if (endpoint != null && signedIn()) scope.launch { api.forgetPushDevice(endpoint) }
    }

    private fun sendEndpoint() {
        val url = prefs.getString("endpoint", null) ?: return
        val p = prefs.getString("p256dh", null) ?: return
        val a = prefs.getString("auth", null) ?: return
        if (!signedIn()) return
        scope.launch {
            when (val r = api.registerPushDevice(PushDevice("unifiedpush", url, p, a))) {
                is ApiResult.Ok -> {
                    prefs.edit().putBoolean("sent", true).apply()
                    _status.update { it.copy(registered = true, error = null) }
                }
                is ApiResult.Err -> _status.update { it.copy(registered = false, error = r.message) }
            }
        }
    }

    private fun refresh(m: PushMode) {
        val pm = context.packageManager
        val all = UnifiedPush.getDistributors(context).map { pkg ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            Distributor(pkg, label)
        }
        val current = UnifiedPush.getSavedDistributor(context)?.takeIf { s -> all.any { it.pkg == s } }
        _status.update {
            it.copy(
                mode = m, distributors = all, current = current,
                registered = m == PushMode.UnifiedPush && prefs.getBoolean("sent", false),
                error = if (m == PushMode.UnifiedPush) it.error else null,
            )
        }
    }
}
