package app.convoyage.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.convoyage.MainActivity
import app.convoyage.R

/** Channels and builders for the ongoing convoy notification and the crash prompt. */
object Notifications {
    const val CH_CONVOY = "convoy_live"
    const val CH_SAFETY = "safety"
    const val ID_CONVOY = 1001
    const val ID_CRASH = 1002
    const val ID_SOS = 1003
    const val ID_KEEPALIVE = 1004
    const val CH_SOCIAL = "social"
    const val CH_ALERTS = "convoy_alerts"
    const val CH_KEEPALIVE = "keepalive"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_CONVOY, "Convoy and ride in progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while your location is shared with a convoy or a ride is being recorded."
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SOCIAL, "Followers, comments and likes", NotificationManager.IMPORTANCE_DEFAULT),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Convoy SOS and hazards", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "An SOS from someone in your convoy, or a hazard they reported while you were away."
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_KEEPALIVE, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Convoyage stays connected for notifications (Settings, Notifications, Stay connected)."
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SAFETY, "Crash alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks whether you are OK after a possible crash."
                enableVibration(true)
            },
        )
    }

    private fun openApp(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** The ongoing notification of the foreground service. [canLeave] adds the Leave action. */
    fun convoy(context: Context, title: String, text: String, canLeave: Boolean): Notification {
        ensureChannels(context)
        val b = NotificationCompat.Builder(context, CH_CONVOY)
            .setSmallIcon(R.drawable.ic_stat_convoy)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context, 0))
        if (canLeave) {
            val leave = PendingIntent.getService(
                context, 1,
                Intent(context, ConvoyService::class.java).setAction(ConvoyService.ACTION_LEAVE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(0, "Leave", leave)
        }
        return b.build()
    }

    /** The quiet ongoing notification of "Stay connected". */
    fun keepAlive(context: Context): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CH_KEEPALIVE)
            .setSmallIcon(R.drawable.ic_stat_convoy)
            .setContentTitle("Connected for notifications")
            .setContentText("Convoyage stays connected so SOS alerts and messages reach you.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(context, 5))
            .build()
    }

    /**
     * A notification from the server, by push or socket. [data] "type" picks the channel; the same event arriving by
     * both routes (an SOS goes out on every channel) lands on the same id, so it shows once.
     */
    fun notice(context: Context, title: String, body: String, data: Map<String, String>) {
        ensureChannels(context)
        if (!canPost(context)) return
        val type = data["type"].orEmpty()
        val urgent = type == "sos" || type == "hazard"
        val key = listOf(type, data["room"], data["userId"], data["postId"], data["hazardId"]).joinToString(":") { it.orEmpty() }
        val n = NotificationCompat.Builder(context, if (urgent) CH_ALERTS else CH_SOCIAL)
            .setSmallIcon(R.drawable.ic_stat_convoy)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_SOCIAL)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, 6))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(key, 0, n) }
    }

    private fun canPost(context: Context) =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** "Are you OK?": a heads-up notification that also tries to open the app full screen. */
    fun crashPrompt(context: Context) {
        ensureChannels(context)
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CH_SAFETY)
            .setSmallIcon(R.drawable.ic_stat_convoy)
            .setContentTitle("Are you OK?")
            .setContentText("A possible crash was detected. Open Convoyage to cancel the alert.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(openApp(context, 2))
            .setFullScreenIntent(openApp(context, 3), true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_CRASH, n) }
    }

    fun cancelCrashPrompt(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_CRASH)
    }

    /** What happened when the countdown ran out. */
    fun sosResult(context: Context, text: String) {
        ensureChannels(context)
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CH_SAFETY)
            .setSmallIcon(R.drawable.ic_stat_convoy)
            .setContentTitle("Crash alert")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, 4))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_SOS, n) }
    }
}
