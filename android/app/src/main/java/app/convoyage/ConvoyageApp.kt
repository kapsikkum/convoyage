package app.convoyage

import android.app.Application
import org.maplibre.android.MapLibre

class ConvoyageApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        container = AppContainer(this)
        container.connectIfSignedIn()
    }
}
