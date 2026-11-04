package app.convoyage.push

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.convoyage.ConvoyageApp
import app.convoyage.service.Notifications

/**
 * "Stay connected": keeps the process (and so the app's socket) alive in the background, so notifications
 * arrive as `notify` messages without any push service. A foreground service of the remote-messaging type:
 * the system lets it hold the network in Doze and restarts it if it is killed.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifications.keepAlive(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(this, Notifications.ID_KEEPALIVE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            } else {
                startForeground(Notifications.ID_KEEPALIVE, n)
            }
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        // The socket reconnects by itself while the process lives; after a restart by the system it needs a nudge.
        (application as ConvoyageApp).container.connectIfSignedIn(onlyIfDown = true)
        return START_STICKY
    }

    companion object {
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}

/** Brings "Stay connected" back after a reboot or an app update. */
class PushBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Creating the application already ran connectIfSignedIn and PushManager.apply.
        (context.applicationContext as ConvoyageApp).container.push.apply()
    }
}
