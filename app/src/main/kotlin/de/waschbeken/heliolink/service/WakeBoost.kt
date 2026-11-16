package de.waschbeken.heliolink.service

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import de.waschbeken.heliolink.R
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.nextAlarm
import java.time.ZonedDateTime

/**
 * "Länger wecken": the strap's own alarm only buzzes briefly, and how long is up to its firmware.
 * Shortly after the alarm time the app therefore starts the strap's "find" vibration, which keeps
 * going until it is stopped on the strap, with "Aus" in the notification or after the set minutes.
 * The strap's alarm itself stays as it is, so it still wakes you when the phone is not there.
 */
object WakeBoost {
    private const val REQUEST = 4322
    const val ACTION_FIRE = "de.waschbeken.heliolink.WAKE_BOOST"
    const val ACTION_STOP = "de.waschbeken.heliolink.WAKE_BOOST_STOP"

    /** After the strap's own short buzz. */
    const val DELAY_SECONDS = 20L

    /** When the next boost starts, or null when it is off or no alarm is set. */
    fun next(context: Context, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        val app = context.app
        if (app.prefs.alarmBoostMinutes <= 0) return null
        val alarms = app.db.alarms().filter { !it.deleted }.map { it.alarm }
        // An alarm that rang less than DELAY_SECONDS ago still gets its boost.
        return nextAlarm(alarms, now.minusSeconds(DELAY_SECONDS))?.plusSeconds(DELAY_SECONDS)
    }

    /** Plans the next boost with Android's alarm clock (or cancels it). Reads the database: not on the main thread. */
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, WakeBoostReceiver::class.java).setAction(ACTION_FIRE)
        val at = next(context)
        if (at == null) {
            PendingIntent.getBroadcast(context, REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it) }
            return
        }
        val pending = PendingIntent.getBroadcast(context, REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val millis = at.toInstant().toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, millis, 60_000L, pending)
            }
        } catch (e: SecurityException) {
            am.setWindow(AlarmManager.RTC_WAKEUP, millis, 60_000L, pending)
        }
    }

    /** "Wecker – der Strap vibriert" with "Aus"; shown while the boost runs. */
    fun notify(context: Context) {
        Notifications.createChannels(context)
        val stop = PendingIntent.getBroadcast(
            context, REQUEST + 1, Intent(context, WakeBoostReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Wecker")
            .setContentText("Der Strap vibriert, bis du ihn stoppst")
            .setContentIntent(stop)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Aus", stop)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(Notifications.ID_WAKE, n)
        } catch (_: SecurityException) {
            // Notifications not permitted; stopping on the strap or the time limit still ends it.
        }
    }

    fun cancelNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(Notifications.ID_WAKE)
    }
}

/** Starts the boost at the alarm time (and plans the next one) or stops it from the notification. */
class WakeBoostReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.app
        when (intent.action) {
            WakeBoost.ACTION_STOP -> {
                app.repo.controller?.stopWakeBoost()
                WakeBoost.cancelNotification(context)
            }
            WakeBoost.ACTION_FIRE -> {
                val controller = app.repo.controller
                if (controller == null) {
                    app.repo.log("Wecker: HelioLink lief nicht, nur der Strap-Wecker hat geweckt")
                } else {
                    controller.startWakeBoost(app.prefs.alarmBoostMinutes * 60)
                }
                val pending = goAsync()
                kotlin.concurrent.thread(name = "wake-boost") {
                    try {
                        WakeBoost.schedule(context)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
