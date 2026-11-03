package app.convoyage

import android.content.Context
import app.convoyage.api.ConvoyApi
import app.convoyage.api.TokenStore
import app.convoyage.data.AccountRepository
import app.convoyage.data.AuthKind
import app.convoyage.data.ConvoyRepository
import app.convoyage.data.HazardRepository
import app.convoyage.data.KeystoreTokenStore
import app.convoyage.data.LocationTracker
import app.convoyage.data.SessionStore
import app.convoyage.data.SignInOutcome
import app.convoyage.data.SocketAuth
import app.convoyage.net.ConvoySocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency container (no DI framework). One instance, owned by [ConvoyageApp]. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val session = SessionStore(context)
    val tokenStore: TokenStore = KeystoreTokenStore(context)
    val tracker = LocationTracker(context)

    val api: ConvoyApi = ConvoyApi(
        baseUrl = { session.serverUrl.value },
        tokens = tokenStore,
        // The refresh token was refused: the session is over. Called on an OkHttp thread.
        onSessionLost = { appScope.launch { account.sessionLost(); convoy.disconnect(); hazards.clear() } },
    )
    val account = AccountRepository(api, session, appScope)
    val convoy = ConvoyRepository(appScope, ConvoySocket(), tracker) { force -> api.freshAccessToken(force) }
    val hazards = HazardRepository(appScope, api, convoy.hazardMessages)

    /** (Re)connects the socket with the stored identity and server URL, if signed in. */
    fun connectIfSignedIn() {
        val name = session.name.value ?: return
        when (session.kind.value) {
            AuthKind.Account -> if (tokenStore.tokens != null) convoy.connect(session.serverUrl.value, SocketAuth.Account)
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
        convoy.disconnect()
        hazards.clear()
        account.logout()
    }
}
