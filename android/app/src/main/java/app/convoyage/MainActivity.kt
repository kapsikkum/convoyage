package app.convoyage

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import app.convoyage.data.SignInOutcome
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
