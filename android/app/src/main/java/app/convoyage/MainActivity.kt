package app.convoyage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.convoyage.design.CvTheme
import app.convoyage.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ConvoyageApp).container
        setContent {
            val night by container.session.night.collectAsState()
            CvTheme(night = night) {
                AppRoot(container)
            }
        }
    }
}
