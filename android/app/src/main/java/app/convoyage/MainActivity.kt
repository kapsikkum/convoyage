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
        // A ride being recorded keeps the screen on: with no background service yet, a dark screen means no GPS fixes.
        // TODO(background): drop this once recording runs in a foreground service.
        lifecycleScope.launch {
            container.recorder.state.map { it.isRecording }.distinctUntilChanged().collect { on ->
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

    /** `convoyage://auth/magic?token=...` (what the server emails): finish sign-in. */
    private fun handleAuthLink(intent: Intent?) {
        val link = intent?.data?.takeIf { it.scheme == "convoyage" && it.host == "auth" }?.toString() ?: return
        intent.data = null // handle each link once, not again after a rotation
        val container = (application as ConvoyageApp).container
        lifecycleScope.launch {
            when (val r = container.signInWithLink(link)) {
                is SignInOutcome.SignedIn -> Unit // AppRoot notices the session and opens Home
                is SignInOutcome.Failed -> Toast.makeText(this@MainActivity, r.message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
