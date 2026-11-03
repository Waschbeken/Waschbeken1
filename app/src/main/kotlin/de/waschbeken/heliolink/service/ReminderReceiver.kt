package de.waschbeken.heliolink.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.Reminders
import java.time.ZonedDateTime

/** Plans the next reminder on the arm with Android's alarm clock. */
object ReminderScheduler {
    private const val REQUEST = 4321
    const val EXTRA_IDS = "ids"
    const val EXTRA_AT = "at"

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, ReminderReceiver::class.java)
        val next = Reminders.next(context.app.prefs.reminders, ZonedDateTime.now())
        if (next == null) {
            PendingIntent.getBroadcast(context, REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it) }
            return
        }
        val (at, due) = next
        intent.putExtra(EXTRA_IDS, due.map { it.id }.toIntArray()).putExtra(EXTRA_AT, at.toEpochSecond())
        val pending = PendingIntent.getBroadcast(context, REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val millis = at.toInstant().toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
            } else {
                // Without the exact-alarm permission: within 10 minutes after the time.
                am.setWindow(AlarmManager.RTC_WAKEUP, millis, 10 * 60_000L, pending)
            }
        } catch (e: SecurityException) {
            am.setWindow(AlarmManager.RTC_WAKEUP, millis, 10 * 60_000L, pending)
        }
    }
}

/**
 * A reminder is due: the strap vibrates (when connected) and a notification shows the text. Its
 * "Erledigt" button notes the reminder as an entry (e.g. "Tabletten"), so it shows in the entries
 * and in the report for the doctor.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.app
        if (intent.action == ACTION_DONE) {
            val id = intent.getIntExtra(EXTRA_ID, -1)
            val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "Erinnerung" }
            context.getSystemService(android.app.NotificationManager::class.java)?.cancel(Notifications.ID_REMINDER + id)
            val pending = goAsync()
            kotlin.concurrent.thread(name = "reminder-done") {
                try {
                    app.db.addEvent(System.currentTimeMillis() / 1000, text, "Erinnerung erledigt")
                    app.repo.dataChanged()
                } finally {
                    pending.finish()
                }
            }
            return
        }
        val ids = intent.getIntArrayExtra(ReminderScheduler.EXTRA_IDS) ?: IntArray(0)
        val at = intent.getLongExtra(ReminderScheduler.EXTRA_AT, 0)
        val due = app.prefs.reminders.filter { it.enabled && it.id in ids }
        // Delivered much too late (phone was off): better no reminder than a confusing one.
        val late = at > 0 && System.currentTimeMillis() / 1000 - at > 30 * 60
        if (due.isNotEmpty() && !late) {
            app.repo.controller?.buzz(due.maxOf { it.pulses }, 450)
            due.forEach { r -> notify(context, r) }
            app.repo.log("Erinnerung: " + due.joinToString { it.text })
        }
        ReminderScheduler.schedule(context)
    }

    private fun notify(context: Context, r: de.waschbeken.heliolink.core.Reminder) {
        Notifications.createChannels(context)
        val text = r.text.ifBlank { "Erinnerung" }
        val done = PendingIntent.getBroadcast(
            context, Notifications.ID_REMINDER + r.id,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_DONE).putExtra(EXTRA_ID, r.id).putExtra(EXTRA_TEXT, text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            context, Notifications.ID_REMINDER + r.id, Intent(context, de.waschbeken.heliolink.ui.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = androidx.core.app.NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDERS)
            .setSmallIcon(de.waschbeken.heliolink.R.drawable.ic_notification)
            .setContentTitle(text)
            .setContentText("Erinnerung · ${r.schedule}")
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Erledigt", done)
            .build()
        try {
            context.getSystemService(android.app.NotificationManager::class.java)?.notify(Notifications.ID_REMINDER + r.id, notification)
        } catch (_: SecurityException) {
            // Notifications not permitted.
        }
    }

    companion object {
        const val ACTION_DONE = "de.waschbeken.heliolink.REMINDER_DONE"
        private const val EXTRA_ID = "id"
        private const val EXTRA_TEXT = "text"
    }
}
