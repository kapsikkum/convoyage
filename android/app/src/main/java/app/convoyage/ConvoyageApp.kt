package app.convoyage

import android.app.Application
import org.maplibre.android.MapLibre

class ConvoyageApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        // Vehicle bodies modelled in Blender ship as assets/cars/<id>.glb.
        val assets = applicationContext.assets
        app.convoyage.render.Ps1CarModels.source = { path -> try { assets.open(path).use { it.readBytes() } } catch (e: java.io.IOException) { null } }
        container = AppContainer(this)
        container.connectIfSignedIn()
    }
}
