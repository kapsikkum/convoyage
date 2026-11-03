package app.convoyage

import android.content.Context
import app.convoyage.data.AuthApi
import app.convoyage.data.ConvoyRepository
import app.convoyage.data.LocationTracker
import app.convoyage.data.SessionStore
import app.convoyage.net.ConvoySocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container (no DI framework). One instance, owned by [ConvoyageApp]. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val session = SessionStore(context)
    val tracker = LocationTracker(context)
    val auth = AuthApi()
    val convoy = ConvoyRepository(appScope, ConvoySocket(), tracker)

    /** (Re)connects the socket with the stored identity and server URL, if signed in. */
    fun connectIfSignedIn() {
        val name = session.name.value ?: return
        convoy.connect(session.serverUrl.value, session.userId, name)
    }
}
