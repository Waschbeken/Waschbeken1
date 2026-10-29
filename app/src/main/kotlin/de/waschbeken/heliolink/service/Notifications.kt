package de.waschbeken.heliolink.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import de.waschbeken.heliolink.R
import de.waschbeken.heliolink.ui.MainActivity

/** All notifications except the ongoing connection notification. */
object Notifications {
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_UPDATES = "updates"
    const val CHANNEL_HEALTH = "health"

    const val ID_BATTERY = 2
    const val ID_SYNC = 3
    const val ID_UPDATE = 4
    const val ID_BEDTIME = 5
    const val ID_MORNING = 6
    const val ID_MAX_HR = 7
    const val ID_INSTALLED = 8
    const val ID_WEEKLY = 9
    const val ID_BODY_CHECK = 10
    const val ID_FOCUS = 11
    const val ID_STRESS = 12

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Warnungen", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_UPDATES, "App-Updates", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_HEALTH, "Schlaf & Erholung", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun show(context: Context, id: Int, channel: String, title: String, text: String, intent: Intent? = null) {
        createChannels(context)
        val open = intent ?: Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
        } catch (_: SecurityException) {
            // Notifications not permitted.
        }
    }

    fun installed(context: Context, version: String) =
        show(context, ID_INSTALLED, CHANNEL_UPDATES, "HelioLink $version ist installiert", "Tippen, um die App zu öffnen")
}
