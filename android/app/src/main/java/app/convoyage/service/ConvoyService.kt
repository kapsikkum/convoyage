package app.convoyage.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.convoyage.AppContainer
import app.convoyage.ConvoyageApp
import app.convoyage.data.Geo
import app.convoyage.ui.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service (type `location`) that keeps the process, the location updates, the position publisher and the
 * ride recorder alive while the screen is off or the app is in the background. It runs only while we are in a
 * convoy or recording a ride, and stops itself otherwise. The ongoing notification shows the convoy and has Leave.
 */
class ConvoyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var running = false
    private var monitor: CrashMonitor? = null
    private val container: AppContainer get() = (application as ConvoyageApp).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = container
        if (intent?.action == ACTION_LEAVE) c.convoy.leave()
        val first = Notifications.convoy(this, "Convoyage", "Starting...", canLeave = false)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                // The microphone type needs RECORD_AUDIO already granted (else the system throws), so it only joins once it is.
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                if (Build.VERSION.SDK_INT >= 30 && c.voice.hasMicPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                ServiceCompat.startForeground(this, Notifications.ID_CONVOY, first, type)
            } else {
                startForeground(Notifications.ID_CONVOY, first)
            }
        } catch (_: Exception) {
            // Not allowed to run in the foreground right now (no location permission, or started from the background).
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) begin(c)
        return START_NOT_STICKY
    }

    private fun begin(c: AppContainer) {
        running = true
        c.tracker.acquire()
        c.convoy.setVisible(true)
        monitor = CrashMonitor(this, c.tracker) { c.crash.trigger() }
        job = scope.launch {
            combine(c.convoy.state, c.recorder.state, c.session.crashDetection, c.nav.state) { convoy, rec, crash, nav -> Quad(convoy, rec, crash, nav) }
                .collect { (convoy, rec, crash, nav) ->
                    val room = convoy.active
                    if (room == null && !rec.isActive && !nav.isOn) {
                        stopSelf()
                        return@collect
                    }
                    val online = room?.members?.count { it.online } ?: 0
                    val title = when {
                        nav.isOn -> "Navigating" + (nav.destination?.name?.let { " to $it" } ?: "")
                        room != null -> room.name.ifBlank { null } ?: "Convoy"
                        else -> "Recording a ride"
                    }
                    val text = buildString {
                        if (nav.isOn) append("${Geo.formatDistance(nav.remainingM)} · ${Math.round(nav.remainingS / 60.0)} min left")
                        if (nav.isOn && (room != null || rec.isActive)) append(" · ")
                        if (room != null) append("$online online · code ${room.code}")
                        if (room != null && rec.isActive) append(" · ")
                        if (rec.isActive) append("Ride ${Fmt.clock(rec.elapsedS)}${if (rec.isPaused) " (paused)" else ""}")
                    }
                    val n = Notifications.convoy(this@ConvoyService, title, text, canLeave = room != null)
                    runCatching { getSystemService(android.app.NotificationManager::class.java).notify(Notifications.ID_CONVOY, n) }
                    val m = monitor
                    if (crash && m != null && !m.running) m.start(scope) else if (!crash && m?.running == true) m.stop()
                }
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    override fun onDestroy() {
        if (running) {
            running = false
            monitor?.stop()
            container.convoy.setVisible(false)
            container.tracker.release()
        }
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_LEAVE = "app.convoyage.action.LEAVE"

        /** Starts the service if possible. Safe to call repeatedly. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ConvoyService::class.java))
            } catch (_: Exception) {
                // Background start refused by the system; the next time the app is in front it will try again.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConvoyService::class.java))
        }
    }
}
