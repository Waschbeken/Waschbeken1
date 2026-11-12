package de.waschbeken.heliolink.service

import android.content.Context
import de.waschbeken.heliolink.core.SleepStage
import de.waschbeken.heliolink.core.nextAlarm
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.data.Prefs
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What the app tells you on its own as a notification: bedtime, whether the strap's battery lasts
 * the night, the morning summary and the weekly and monthly review, the warning when the body works
 * harder than usual. Each of them comes at most once (per day, week or month); the strap service
 * calls them every few minutes and after a sync.
 */
internal class HealthMessages(
    private val context: Context,
    private val db: HealthDb,
    private val prefs: Prefs,
    private val repo: StrapRepository,
) {
    /** Reminds to go to bed so the sleep goal fits before the next alarm. */
    fun checkBedtime() {
        val before = prefs.bedtimeReminder
        if (before <= 0) return
        val now = ZonedDateTime.now()
        val alarm = nextAlarm(db.alarms().filter { !it.deleted }.map { it.alarm })?.takeIf { java.time.Duration.between(now, it).toHours() < 24 }
        // Without a strap alarm: the usual wake-up time of the last nights.
        val wake = alarm ?: usualWake(now) ?: return
        val bedtime = wake.minusMinutes(prefs.profile.sleepGoalMinutes.toLong())
        val remind = bedtime.minusMinutes(before.toLong())
        val day = bedtime.toLocalDate().toEpochDay()
        if (now.isAfter(remind) && now.isBefore(bedtime) && prefs.bedtimeNotifiedDay != day) {
            prefs.bedtimeNotifiedDay = day
            val goal = de.waschbeken.heliolink.ui.formatMinutes(prefs.profile.sleepGoalMinutes)
            Notifications.show(context, Notifications.ID_BEDTIME, Notifications.CHANNEL_HEALTH,
                "Bald Schlafenszeit",
                if (alarm != null) {
                    "Wecker um %02d:%02d – für %s Schlaf ab %02d:%02d ins Bett.".format(alarm.hour, alarm.minute, goal, bedtime.hour, bedtime.minute)
                } else {
                    "Du wachst meist gegen %02d:%02d auf – für %s Schlaf ab %02d:%02d ins Bett.".format(wake.hour, wake.minute, goal, bedtime.hour, bedtime.minute)
                },
                de.waschbeken.heliolink.ui.MainActivity.openIntent(context, de.waschbeken.heliolink.ui.Routes.SLEEP),
            )
        }
    }

    /** The next usual wake-up: the average time of the last 7 nights' ends, at least 3 nights needed. */
    private fun usualWake(now: ZonedDateTime): ZonedDateTime? {
        val today = now.toLocalDate()
        val ends = de.waschbeken.heliolink.data.Queries.nights(db, today.minusDays(7), today, now.zone).values
            .filter { it.asleepMinutes >= 180 }
            .map { java.time.Instant.ofEpochSecond(it.end).atZone(now.zone).let { t -> t.hour * 60 + t.minute } }
            .filter { it in 3 * 60..13 * 60 }
        if (ends.size < 3) return null
        val minute = ends.average().toInt()
        val candidate = today.atTime(minute / 60, minute % 60).atZone(now.zone)
        return if (candidate.isAfter(now)) candidate else candidate.plusDays(1)
    }

    /**
     * In the evening (18–23 h): when the strap's battery will probably not last until the morning,
     * say so once, so it can still be charged before going to bed and the night is recorded.
     */
    fun checkNightBattery() {
        if (prefs.lowBatteryAlert <= 0) return
        val s = repo.state.value
        val level = s.battery ?: return
        if (s.charging) return
        val now = ZonedDateTime.now()
        if (now.hour !in 18..22 || prefs.nightBatteryDay == now.toLocalDate().toEpochDay()) return
        val nowSec = now.toEpochSecond()
        val perDay = de.waschbeken.heliolink.core.BatteryProfiles.drainPerDay(db.values(HealthDb.BATTERY, nowSec - 3 * 86_400L, nowSec + 60))
            ?: return
        val hoursLeft = level / (perDay / 24)
        val morning = now.toLocalDate().plusDays(1).atTime(8, 0).atZone(now.zone)
        val hoursNeeded = java.time.Duration.between(now, morning).toMinutes() / 60.0
        if (hoursLeft >= hoursNeeded + 1) return
        prefs.nightBatteryDay = now.toLocalDate().toEpochDay()
        Notifications.show(context, Notifications.ID_BATTERY, Notifications.CHANNEL_HEALTH,
            "Strap-Akku reicht nicht für die Nacht",
            "Noch $level % – das reicht für etwa ${hoursLeft.toInt()} Stunden. Lade den Strap vor dem Schlafen kurz, damit die Nacht aufgezeichnet wird.",
            de.waschbeken.heliolink.ui.MainActivity.openIntent(context, de.waschbeken.heliolink.ui.Routes.settings("battery")),
        )
    }

    /** Monday morning: last week compared with the week before. */
    private fun weeklySummary() {
        val today = LocalDate.now()
        if (!prefs.weeklySummary || today.dayOfWeek != java.time.DayOfWeek.MONDAY || prefs.weeklySummaryDay == today.toEpochDay()) return
        if (LocalTime.now().hour < 6) return
        prefs.weeklySummaryDay = today.toEpochDay()
        val zone = ZoneId.systemDefault()
        val nights = de.waschbeken.heliolink.data.Queries.nights(db, today.minusDays(14), today.minusDays(1), zone)
        val last = nights.filterKeys { it >= today.minusDays(7) }.values
        val before = nights.filterKeys { it < today.minusDays(7) }.values
        val days = de.waschbeken.heliolink.core.Aggregates.days(
            db.activity(today.minusDays(14).atStartOfDay(zone).toEpochSecond(), today.atStartOfDay(zone).toEpochSecond()),
            today.minusDays(14), today.minusDays(1), zone,
        )
        val stepsLast = days.filter { it.date >= today.minusDays(7) }.map { it.steps }.average().takeIf { !it.isNaN() }?.toInt()
        val stepsBefore = days.filter { it.date < today.minusDays(7) }.map { it.steps }.average().takeIf { !it.isNaN() }?.toInt()
        if (last.isEmpty() && (stepsLast ?: 0) == 0) return
        fun trend(now: Int, was: Int?, unit: String) = if (was == null || was == 0) "" else {
            val d = now - was
            if (d >= 0) " (+$d$unit)" else " ($d$unit)"
        }
        val text = buildString {
            if (last.isNotEmpty()) {
                val sleep = last.map { it.asleepMinutes }.average().toInt()
                val score = last.map { it.score }.average().toInt()
                append("Ø ${de.waschbeken.heliolink.ui.formatMinutes(sleep)} Schlaf")
                append(trend(sleep, before.map { it.asleepMinutes }.average().takeIf { !it.isNaN() }?.toInt(), " min"))
                append(" · Score $score")
                append(trend(score, before.map { it.score }.average().takeIf { !it.isNaN() }?.toInt(), ""))
            }
            if (stepsLast != null && stepsLast > 0) {
                if (isNotEmpty()) append(" · ")
                append("Ø ${"%,d".format(java.util.Locale.GERMANY, stepsLast)} Schritte")
                append(trend(stepsLast, stepsBefore, ""))
            }
            val goals = prefs.goals
            if (goals.isNotEmpty()) {
                val facts = de.waschbeken.heliolink.ui.progress.ProgressData.facts(db, prefs.profile, today, emptyList(), weeks = 1).days
                val reached = goals.count { de.waschbeken.heliolink.core.Goals.week(it, facts, today.minusWeeks(1), today).reached }
                if (isNotEmpty()) append(" · ")
                append("Ziele: $reached von ${goals.size} geschafft")
            }
        }
        Notifications.show(context, Notifications.ID_WEEKLY, Notifications.CHANNEL_HEALTH, "Deine Woche", text,
            de.waschbeken.heliolink.ui.MainActivity.openIntent(context, de.waschbeken.heliolink.ui.Routes.PROGRESS),
        )
    }

    /** In the first days of a month: the month before compared with the one before that. */
    private fun monthlySummary() {
        val today = LocalDate.now()
        val month = today.withDayOfMonth(1)
        if (!prefs.monthlySummary || today.dayOfMonth > 3 || prefs.monthlySummaryMonth == month.toEpochDay()) return
        if (LocalTime.now().hour < 8) return
        prefs.monthlySummaryMonth = month.toEpochDay()
        val from = month.minusMonths(1)
        val to = month.minusDays(1)
        val now = de.waschbeken.heliolink.ui.progress.ProgressData.stats(db, prefs.profile, from, to)
        if (now.nights == 0 && now.stepsTotal == 0L) return
        val before = de.waschbeken.heliolink.ui.progress.ProgressData.stats(db, prefs.profile, from.minusMonths(1), from.minusDays(1))
        fun delta(a: Int?, b: Int?, unit: String) = if (a == null || b == null || a == b) "" else
            " (" + (if (a > b) "+" else "−") + kotlin.math.abs(a - b) + unit + ")"
        val text = buildList {
            now.sleepMinutes?.let { add("Ø ${de.waschbeken.heliolink.ui.formatHours(it)} Schlaf" + delta(it, before.sleepMinutes, " min")) }
            now.sleepScore?.let { add("Score $it" + delta(it, before.sleepScore, "")) }
            now.stepsPerDay?.let { add("Ø ${"%,d".format(java.util.Locale.GERMANY, it)} Schritte" + delta(it, before.stepsPerDay, "")) }
            now.restingHr?.let { add("Ruhepuls $it" + delta(it, before.restingHr, " bpm")) }
            if (now.workouts > 0) add("${now.workouts} Trainings")
        }.joinToString(" · ")
        val name = from.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.GERMANY)
        Notifications.show(context, Notifications.ID_MONTHLY, Notifications.CHANNEL_HEALTH, "Dein $name im Rückblick", "$text · Tippen für Details",
            de.waschbeken.heliolink.ui.MainActivity.openIntent(context, de.waschbeken.heliolink.ui.Routes.PROGRESS),
        )
    }

    /** Once a day: a clear warning when last night's values together point to an illness coming up. */
    private fun bodyCheckWarning() {
        val today = LocalDate.now()
        if (!prefs.bodyCheckNotify || prefs.bodyCheckDay == today.toEpochDay() || LocalTime.now().hour < 5) return
        val zone = ZoneId.systemDefault()
        val night = de.waschbeken.heliolink.data.Queries.night(db, today, zone) ?: return
        if (night.end > System.currentTimeMillis() / 1000 - 15 * 60) return // probably still in bed
        val result = de.waschbeken.heliolink.core.BodyCheck.check(de.waschbeken.heliolink.data.Queries.nightVitals(db, today.minusDays(36), today, zone))
        prefs.bodyCheckDay = today.toEpochDay()
        if (result == null || result.date != today || result.level != de.waschbeken.heliolink.core.BodyCheck.Level.ALERT) return
        Notifications.show(context, Notifications.ID_BODY_CHECK, Notifications.CHANNEL_HEALTH,
            "Dein Körper arbeitet mehr als sonst",
            result.signals.joinToString(" · ") { "${it.vital.label} ${it.text}" } +
                ". Das sieht oft so aus, bevor man krank wird – gönn dir heute Ruhe. Keine Diagnose.",
            de.waschbeken.heliolink.ui.MainActivity.openIntent(context, de.waschbeken.heliolink.ui.Routes.HOME),
        )
    }

    /** Morning summary after the first successful sync of the day. */
    fun afterSync() {
        de.waschbeken.heliolink.data.AutoBackup.runIfDueInBackground(context)
        try {
            weeklySummary()
            monthlySummary()
            bodyCheckWarning()
            StressPrompt.check(context, db, prefs, repo.state.value.workout != null)
        } catch (e: Exception) {
            de.waschbeken.heliolink.data.CrashReporter.nonFatal(context, e)
        }
        val today = LocalDate.now()
        if (!prefs.morningSummary || prefs.morningSummaryDay == today.toEpochDay() || LocalTime.now().hour < 5) return
        val zone = ZoneId.systemDefault()
        val night = de.waschbeken.heliolink.data.Queries.night(db, today, zone) ?: return
        if (night.end > System.currentTimeMillis() / 1000 - 15 * 60) return // probably still in bed
        prefs.morningSummaryDay = today.toEpochDay()
        val text = buildString {
            append("${de.waschbeken.heliolink.ui.formatMinutes(night.asleepMinutes)} geschlafen, Score ${night.score}")
            if (!night.estimated) {
                append(" · Tief ${de.waschbeken.heliolink.ui.formatMinutes(night.minutes(SleepStage.DEEP))}")
                append(" · REM ${de.waschbeken.heliolink.ui.formatMinutes(night.minutes(SleepStage.REM))}")
            }
            night.minHeartRate?.let { append(" · Puls min. $it") }
            // The most striking point of "Heute fällt auf", if any.
            try {
                de.waschbeken.heliolink.ui.home.HomeData.load(db, prefs.profile.stepGoal).insights.firstOrNull()?.let { append("\n").append(it.text) }
            } catch (e: Exception) {
                de.waschbeken.heliolink.data.CrashReporter.nonFatal(context, e)
            }
        }
        Notifications.show(context, Notifications.ID_MORNING, Notifications.CHANNEL_HEALTH, "Guten Morgen! Deine Nacht", "$text\nTippen zum Ansehen oder Korrigieren",
            de.waschbeken.heliolink.ui.MainActivity.openIntent(context, de.waschbeken.heliolink.ui.Routes.SLEEP),
        )
    }
}
