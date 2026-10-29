package de.waschbeken.heliolink.ui.home

import de.waschbeken.heliolink.core.ActivityDetector
import de.waschbeken.heliolink.core.ActivitySample
import de.waschbeken.heliolink.core.Aggregates
import de.waschbeken.heliolink.core.BatteryForecast
import de.waschbeken.heliolink.core.BodyCheck
import de.waschbeken.heliolink.core.HrvStatus
import de.waschbeken.heliolink.core.DayStats
import de.waschbeken.heliolink.core.DetectedActivity
import de.waschbeken.heliolink.core.PaiSample
import de.waschbeken.heliolink.core.Readiness
import de.waschbeken.heliolink.core.SleepSession
import de.waschbeken.heliolink.core.StressSample
import de.waschbeken.heliolink.core.ValueSample
import de.waschbeken.heliolink.core.nextAlarm
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.data.Queries
import de.waschbeken.heliolink.data.Workout
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Everything the start screen shows, loaded in one go off the main thread. */
class HomeData(
    val date: LocalDate,
    val day: List<ActivitySample>,
    val night: SleepSession?,
    val stress: List<StressSample>,
    val spo2: List<ValueSample>,
    val resting: Int?,
    val restingBaseline: Double?,
    val hrvNight: Double?,
    val hrvBaseline: Double?,
    val pai: PaiSample?,
    val tempNight: Double?,
    val tempDeviation: Double?,
    val breathNight: Double?,
    val batteryDays: Double?,
    val week: List<DayStats>,
    val streak: Int,
    val activities: List<DetectedActivity>,
    val workouts: List<Workout>,
    val nextAlarm: ZonedDateTime?,
    val nextAlarmLabel: String,
    val readiness: Readiness?,
    val hrPoints: List<Pair<Long, Float>>,
    /** Early warning from last night's values, null without enough history. */
    val bodyCheck: BodyCheck.Result?,
    /** Entries of yesterday that can explain unusual night values (alcohol, sport …). */
    val bodyCheckHints: List<String>,
    val hrvStatus: HrvStatus.Result?,
    /** Irregular heart rhythm stretches of the last 14 days, when they repeat (see RhythmCheck.worthAHint). */
    val irregularRhythm: List<HealthDb.RhythmRow>,
) {
    companion object {
        fun load(db: HealthDb, stepGoal: Int): HomeData {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val (dayStart, dayEnd) = Queries.dayWindow(today, zone)
            val (nightStart, _) = Queries.nightWindow(today, zone)
            val day = db.activity(dayStart, dayEnd)
            val night = Queries.night(db, today, zone)
            val past = today.minusDays(28).atStartOfDay(zone).toEpochSecond()

            val restingAll = db.values(HealthDb.RESTING_HR, past, dayEnd)
            val resting = restingAll.lastOrNull()?.value
            val restingBaseline = restingAll.dropLast(1).takeIf { it.size >= 3 }?.map { it.value }?.average()

            val hrvAll = db.values(HealthDb.HRV, past, dayEnd)
            val hrvNight = Queries.during(hrvAll, night).map { it.value }.takeIf { it.isNotEmpty() }?.average()
            val hrvByNight = hrvAll.groupBy { (it.timestamp + 6 * 3600) / 86400 }.values.map { v -> v.map { it.value }.average() }
            val hrvBaseline = hrvByNight.dropLast(1).takeIf { it.size >= 3 }?.average()

            val temps = db.values(HealthDb.TEMPERATURE, past, dayEnd)
            val tempNight = Queries.during(temps, night).map { it.value / 100.0 }.takeIf { it.isNotEmpty() }?.average()
            val tempNights = temps.filter { it.timestamp < nightStart }.groupBy { (it.timestamp - 4 * 3600) / 86400 }
                .values.map { v -> v.map { it.value / 100.0 }.sorted().let { s -> s[s.size / 2] } }
            val tempDeviation = if (tempNight != null && tempNights.size >= 3) tempNight - tempNights.average() else null

            val breath = Queries.during(db.values(HealthDb.RESPIRATION, nightStart, dayEnd), night)
                .map { it.value }.takeIf { it.isNotEmpty() }?.average()

            val week = Aggregates.days(db.activity(today.minusDays(29).atStartOfDay(zone).toEpochSecond(), dayEnd), today.minusDays(29), today, zone)
            val alarms = db.alarms().filter { !it.deleted }
            val next = nextAlarm(alarms.map { it.alarm })
            val nextLabel = alarms.firstOrNull { next != null && it.alarm.hour == next.hour && it.alarm.minute == next.minute }?.label.orEmpty()

            val vitals = Queries.nightVitals(db, today.minusDays(36), today, zone)
            val bodyCheck = BodyCheck.check(vitals)?.takeIf { it.date == today }
            val hints = if (bodyCheck == null || bodyCheck.level == BodyCheck.Level.NORMAL) emptyList() else {
                (Queries.eventsForNight(db, today, zone).map { it.tag } + db.sleepTags(today.toEpochDay(), today.toEpochDay())[today.toEpochDay()].orEmpty())
                    .filter { t -> BODY_CHECK_HINTS.any { t.contains(it, ignoreCase = true) } }.distinct()
            }
            val hrvStatus = HrvStatus.status(Queries.hrvDaily(db, today.minusDays(60), today, zone), today)

            return HomeData(
                date = today,
                day = day,
                night = night,
                stress = db.stress(dayStart, dayEnd),
                spo2 = db.values(HealthDb.SPO2, nightStart, dayEnd),
                resting = resting,
                restingBaseline = restingBaseline,
                hrvNight = hrvNight,
                hrvBaseline = hrvBaseline,
                pai = db.pai(past, dayEnd).lastOrNull(),
                tempNight = tempNight,
                tempDeviation = tempDeviation,
                breathNight = breath,
                batteryDays = BatteryForecast.daysLeft(db.values(HealthDb.BATTERY, past, dayEnd)),
                week = week.takeLast(7),
                streak = Aggregates.goalStreak(week, stepGoal, today),
                activities = ActivityDetector.detect(day, resting),
                workouts = db.workouts().filter { it.start >= dayStart },
                nextAlarm = next,
                nextAlarmLabel = nextLabel,
                readiness = Readiness.calculate(night?.score, hrvNight, hrvBaseline, resting, restingBaseline),
                hrPoints = Queries.heartRatePoints(db, dayStart, dayEnd, day),
                bodyCheck = bodyCheck,
                bodyCheckHints = hints,
                hrvStatus = hrvStatus,
                irregularRhythm = db.rhythm(dayEnd - 15 * 86_400L, dayEnd).filter { it.irregular }.let { list ->
                    if (de.waschbeken.heliolink.core.RhythmCheck.worthAHint(list.map { (it.timestamp + 3600) / 86_400 })) list else emptyList()
                },
            )
        }

        /** Entries that change night values without being ill. */
        val BODY_CHECK_HINTS = listOf("Alkohol", "Sport", "Stress", "Spät gegessen", "Essen", "Koffein", "Nikotin", "Spät ins Bett", "Krank", "Medikament")
    }
}
