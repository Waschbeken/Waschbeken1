package de.waschbeken.heliolink.service

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import de.waschbeken.heliolink.R
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.StrapAlarm
import de.waschbeken.heliolink.core.alarmsAt
import de.waschbeken.heliolink.core.nextAlarm
import java.time.ZonedDateTime

/**
 * "Länger wecken": the strap's own alarm only buzzes briefly, and how long is up to its firmware.
 * Shortly after the alarm time the app therefore starts the strap's "find" vibration, which keeps
 * going until you are clearly awake – the phone is unlocked, HelioLink is opened, you get up and
 * walk a few steps, "Aus" in the notification – or the set minutes are over. The strap has no
 * button and does not report taps, so it cannot be stopped on the strap itself.
 * The strap's alarm stays as it is, so it still wakes you when the phone is not there.
 */
object WakeBoost {
    private const val REQUEST = 4322
    const val ACTION_FIRE = "de.waschbeken.heliolink.WAKE_BOOST"
    const val ACTION_STOP = "de.waschbeken.heliolink.WAKE_BOOST_STOP"

    /** After the strap's own short buzz. */
    const val DELAY_SECONDS = 20L

    /** Steps counted by the strap since the start that count as "got up". */
    const val STEPS_TO_STOP = 12

    private fun key(a: StrapAlarm) = "${a.position}:${a.hour}:${a.minute}"

    /**
     * When the next boost starts, or null when it is off or no alarm is set. A one-time alarm is
     * boosted once: the strap switches it off after ringing, but the app may not have heard of that.
     */
    fun next(context: Context, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        val app = context.app
        if (app.prefs.alarmBoostMinutes <= 0) return null
        val done = app.prefs.boostedOnce
        val alarms = app.db.alarms().filter { !it.deleted }.map { it.alarm }.filter { it.repetition != 0 || key(it) !in done }
        // An alarm that rang less than DELAY_SECONDS ago still gets its boost.
        return nextAlarm(alarms, now.minusSeconds(DELAY_SECONDS))?.plusSeconds(DELAY_SECONDS)
    }

    /** An alarm was saved or deleted in its slot: a one-time alarm there may be boosted again. */
    fun alarmChanged(context: Context, position: Int) {
        val prefs = context.app.prefs
        val left = prefs.boostedOnce.filterNot { it.startsWith("$position:") }.toSet()
        if (left.size != prefs.boostedOnce.size) prefs.boostedOnce = left
    }

    /**
     * Whether an alarm really rang [DELAY_SECONDS] before [now]; its one-time alarms are marked as
     * boosted. Guards against a boost without an alarm, e.g. after the alarm was changed elsewhere.
     */
    fun rang(context: Context, now: ZonedDateTime = ZonedDateTime.now()): Boolean {
        val app = context.app
        val rows = app.db.alarms().filter { !it.deleted }.map { it.alarm }
        val due = alarmsAt(rows, now.minusSeconds(DELAY_SECONDS)).filter { it.enabled || it.repetition == 0 }
            .filter { it.repetition != 0 || key(it) !in app.prefs.boostedOnce }
        val once = due.filter { it.repetition == 0 }.map { key(it) }
        if (once.isNotEmpty()) app.prefs.boostedOnce = app.prefs.boostedOnce + once
        return due.isNotEmpty()
    }

    /** The phone is in someone's hands right now (screen on and unlocked): no need to wake them. */
    fun phoneInUse(context: Context): Boolean {
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false
        return interactive && !locked
    }

    /** Plans the next boost with Android's alarm clock (or cancels it). Reads the database: not on the main thread. */
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val prefs = context.app.prefs
        val intent = Intent(context, WakeBoostReceiver::class.java).setAction(ACTION_FIRE)
        // About to fire: leave it alone. The strap switches a one-time alarm off the moment it
        // rings and reports that, which would otherwise cancel the boost just before it starts.
        val nowMs = System.currentTimeMillis()
        if (prefs.wakeBoostAt in (nowMs + 1)..(nowMs + (DELAY_SECONDS + 5) * 1000)) return
        val at = next(context)
        if (at == null) {
            prefs.wakeBoostAt = 0
            PendingIntent.getBroadcast(context, REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it) }
            return
        }
        val pending = PendingIntent.getBroadcast(context, REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val millis = at.toInstant().toEpochMilli()
        prefs.wakeBoostAt = millis
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

    /** "Wecker" with "Aus", also on the lock screen; shown while the boost runs. */
    fun notify(context: Context) {
        Notifications.createChannels(context)
        val stop = PendingIntent.getBroadcast(
            context, REQUEST + 1, Intent(context, WakeBoostReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = "Aus: hier tippen, das Handy entsperren oder aufstehen und ein paar Schritte gehen"
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Wecker – der Strap vibriert")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(stop)
            .setOngoing(true)
            .addAction(0, "Aus", stop)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(Notifications.ID_WAKE, n)
        } catch (_: SecurityException) {
            // Notifications not permitted; unlocking the phone, getting up or the time limit still end it.
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
                // Null only when the receiver is called directly (tests) instead of by Android.
                val pending: PendingResult? = goAsync()
                kotlin.concurrent.thread(name = "wake-boost") {
                    try {
                        app.prefs.wakeBoostAt = 0
                        val controller = app.repo.controller
                        val minutes = app.prefs.alarmBoostMinutes
                        when {
                            minutes <= 0 -> Unit
                            !WakeBoost.rang(context) -> app.repo.log("Wecker: zu dieser Zeit ist kein Wecker gestellt, kein langes Vibrieren")
                            WakeBoost.phoneInUse(context) -> app.repo.log("Wecker: Handy ist in Benutzung, kein langes Vibrieren")
                            controller == null -> app.repo.log("Wecker: HelioLink lief nicht, nur der Strap-Wecker hat geweckt")
                            else -> controller.startWakeBoost(minutes * 60)
                        }
                        // The strap may have switched a one-time alarm off: read its list again.
                        controller?.refreshAlarms()
                        WakeBoost.schedule(context)
                    } finally {
                        pending?.finish()
                    }
                }
            }
        }
    }
}
