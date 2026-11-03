package app.convoyage.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One location sample. [speedMps] and [headingDeg] are null when the device does not report them. */
data class Fix(
    val lat: Double,
    val lng: Double,
    val headingDeg: Double?,
    val speedMps: Double?,
    val accuracyM: Float,
    val timeMs: Long,
    /** Metres above the WGS84 ellipsoid as the device reports it; null when unknown. Noisy: see [TraceMath]. */
    val altitudeM: Double? = null,
)

/**
 * Foreground-only location via the platform [LocationManager] (GPS preferred, network as fallback).
 * Reference-counted: screens call [acquire] while visible and [release] when they stop.
 *
 * TODO(background): background location + a foreground service (and the notification/permission flow
 * that goes with it) is not part of this version. Today the convoy goes quiet when the app is hidden.
 */
class LocationTracker(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()

    private var users = 0
    private var lastGpsMs = 0L

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onSample(location)

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private fun onSample(l: Location) {
        val now = System.currentTimeMillis()
        if (l.provider == LocationManager.GPS_PROVIDER) lastGpsMs = now
        else if (now - lastGpsMs < 10_000) return // GPS is fresh; ignore the coarser fix
        _fix.value = Fix(
            lat = l.latitude,
            lng = l.longitude,
            headingDeg = if (l.hasBearing()) l.bearing.toDouble() else null,
            speedMps = if (l.hasSpeed()) l.speed.toDouble() else null,
            accuracyM = if (l.hasAccuracy()) l.accuracy else Float.MAX_VALUE,
            timeMs = now,
            altitudeM = if (l.hasAltitude()) l.altitude else null,
        )
    }

    /**
     * The newest position we have: the live fix, else whatever the system last saw (for "Nearby", where a position a
     * few minutes old is fine). Its [Fix.timeMs] is when the position was measured, not now.
     */
    @Suppress("MissingPermission")
    fun lastKnown(): Fix? {
        _fix.value?.let { return it }
        if (!hasPermission()) return null
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { if (manager.isProviderEnabled(it)) manager.getLastKnownLocation(it) else null }
                .maxByOrNull { it.time }
                ?.let { l ->
                    Fix(
                        l.latitude, l.longitude, if (l.hasBearing()) l.bearing.toDouble() else null,
                        if (l.hasSpeed()) l.speed.toDouble() else null, if (l.hasAccuracy()) l.accuracy else Float.MAX_VALUE, l.time,
                    )
                }
        } catch (_: SecurityException) {
            null
        }
    }

    /** Starts updates if this is the first user and the location permission has been granted. */
    fun acquire() {
        users++
        if (users == 1) start()
    }

    fun release() {
        if (users == 0) return
        users--
        if (users == 0) stop()
    }

    /** Call after the permission dialog is accepted while a screen is already acquired. */
    fun restartIfNeeded() {
        if (users > 0) {
            stop()
            start()
        }
    }

    @Suppress("MissingPermission")
    private fun start() {
        if (!hasPermission()) return
        try {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (!manager.isProviderEnabled(provider)) continue
                manager.getLastKnownLocation(provider)?.let { if (_fix.value == null) onSample(it) }
                manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
            // Permission revoked between check and request; the screen will ask again.
        }
    }

    private fun stop() {
        manager.removeUpdates(listener)
    }
}
