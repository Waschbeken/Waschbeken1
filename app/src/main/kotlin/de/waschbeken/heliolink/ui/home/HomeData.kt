package de.waschbeken.heliolink.ui.home

import de.waschbeken.heliolink.core.ActivityDetector
import de.waschbeken.heliolink.core.ActivitySample
import de.waschbeken.heliolink.core.Aggregates
import de.waschbeken.heliolink.core.BatteryForecast
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
            )
        }
    }
}
