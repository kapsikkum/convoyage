package app.convoyage

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import app.convoyage.data.AppLink
import app.convoyage.data.DeepLink
import app.convoyage.data.SignInOutcome
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import app.convoyage.design.CvTheme
import app.convoyage.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ConvoyageApp).container
        handleAuthLink(intent)
        // Ride recording and the convoy now run in a foreground service, so the screen may go dark.
        // A crash countdown wakes the screen and shows over the lock screen.
        lifecycleScope.launch {
            container.crash.ui.map { it is app.convoyage.service.CrashUi.Counting }.distinctUntilChanged().collect { on ->
                setShowWhenLocked(on)
                setTurnScreenOn(on)
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        setContent {
            val night by container.session.night.collectAsState()
            CvTheme(night = night) {
                AppRoot(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthLink(intent)
    }

    /**
     * `convoyage://auth/magic?token=...` (what the server emails) finishes sign-in; `convoyage://join/<CODE>` (and the
     * https fallback) asks to join a convoy, which waits for sign-in and a connection.
     */
    private fun handleAuthLink(intent: Intent?) {
        val uri = intent?.data?.toString() ?: return
        val container = (application as ConvoyageApp).container
        when (val link = DeepLink.parse(uri)) {
            is AppLink.Auth -> {
                intent.data = null // handle each link once, not again after a rotation
                lifecycleScope.launch {
                    when (val r = container.signInWithLink(link.link)) {
                        is SignInOutcome.SignedIn -> Unit // AppRoot notices the session and opens Home
                        is SignInOutcome.Failed -> Toast.makeText(this@MainActivity, r.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            is AppLink.Join -> {
                intent.data = null
                container.setPendingJoin(link.code)
            }
            null -> Unit
        }
    }
}
