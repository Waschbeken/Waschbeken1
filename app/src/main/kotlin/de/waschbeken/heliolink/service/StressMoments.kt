package de.waschbeken.heliolink.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import de.waschbeken.heliolink.R
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.StressMoments
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.data.Prefs
import de.waschbeken.heliolink.ui.MainActivity
import de.waschbeken.heliolink.ui.Routes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * After a longer stretch of high stress (from the strap's stress values) the app asks once
 * "Was war los?". The answer is saved as quick entry "Stress: …" at the start of the stretch, so
 * after a few weeks the stress page shows what stresses you most. At most three times a day, not at
 * night, not during workouts.
 */
object StressPrompt {
    const val ACTION = "de.waschbeken.heliolink.STRESS_ANSWER"
    const val EXTRA_REASON = "reason"
    const val EXTRA_TIME = "time"
    const val PREFIX = "Stress: "
    val REASONS = listOf("Arbeit/Lernen", "Ärger", "Sorgen", "Koffein")

    fun check(context: Context, db: HealthDb, prefs: Prefs, workoutRunning: Boolean) {
        if (!prefs.stressPrompt || workoutRunning) return
        val now = System.currentTimeMillis() / 1000
        val hour = LocalTime.now().hour
        if (hour < 7 || hour >= 22) return
        val today = LocalDate.now().toString()
        val count = prefs.stressPromptToday.takeIf { it.startsWith("$today:") }?.substringAfter(':')?.toIntOrNull() ?: 0
        if (count >= 3 || now - prefs.stressPromptLast < 2 * 3600) return
        val stress = db.stress(now - 2 * 3600, now + 60).map { it.timestamp to it.stress }
        val activity = db.activity(now - 2 * 3600, now + 60)
        if (activity.any { it.isSleep && it.timestamp > now - 3600 }) return
        val moment = StressMoments.find(stress, activity.associate { it.timestamp / 60 * 60 to it.steps }, now) ?: return
        if (moment.end <= prefs.stressPromptLast) return
        prefs.stressPromptLast = moment.end
        prefs.stressPromptToday = "$today:${count + 1}"
        show(context, moment)
    }

    private fun show(context: Context, m: StressMoments.Moment) {
        Notifications.createChannels(context)
        val zone = ZoneId.systemDefault()
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        val from = Instant.ofEpochSecond(m.start).atZone(zone).format(fmt)
        val to = Instant.ofEpochSecond(m.end).atZone(zone).format(fmt)
        val open = PendingIntent.getActivity(
            context, Notifications.ID_STRESS, MainActivity.openIntent(context, Routes.metric("stress")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_HEALTH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Viel Stress von $from bis $to – was war los?")
            .setContentText("Stress im Schnitt ${m.average}. Tipp an, was es war – nach ein paar Wochen siehst du deine Auslöser.")
            .setContentIntent(open)
            .setAutoCancel(true)
        REASONS.forEachIndexed { i, reason ->
            val intent = Intent(context, StressAnswerReceiver::class.java)
                .setAction(ACTION)
                .putExtra(EXTRA_REASON, reason)
                .putExtra(EXTRA_TIME, m.start)
            val pending = PendingIntent.getBroadcast(context, 100 + i, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            // Android shows at most three buttons; the fourth reason is on the stress page.
            if (i < 3) builder.addAction(0, reason, pending)
        }
        try {
            context.getSystemService(NotificationManager::class.java).notify(Notifications.ID_STRESS, builder.build())
        } catch (_: SecurityException) {
        }
    }
}

/** Saves the answer from the stress notification as quick entry. */
class StressAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != StressPrompt.ACTION) return
        val reason = intent.getStringExtra(StressPrompt.EXTRA_REASON) ?: return
        val time = intent.getLongExtra(StressPrompt.EXTRA_TIME, System.currentTimeMillis() / 1000)
        val app = context.app
        val pending = goAsync()
        kotlin.concurrent.thread(name = "stress-answer") {
            try {
                app.db.addEvent(time, StressPrompt.PREFIX + reason)
                app.repo.dataChanged()
            } finally {
                pending.finish()
            }
        }
        context.getSystemService(NotificationManager::class.java).cancel(Notifications.ID_STRESS)
    }
}
