package de.waschbeken.heliolink

import de.waschbeken.heliolink.core.ActivitySample
import de.waschbeken.heliolink.core.OdiNight
import de.waschbeken.heliolink.core.PaiSample
import de.waschbeken.heliolink.core.RhythmCheck
import de.waschbeken.heliolink.core.SleepRecord
import de.waschbeken.heliolink.core.SleepStage
import de.waschbeken.heliolink.core.StrapAlarm
import de.waschbeken.heliolink.core.StressSample
import de.waschbeken.heliolink.core.ValueSample
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.data.Workout
import de.waschbeken.heliolink.service.RECORDING_SPORT
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sin
import kotlin.random.Random

/**
 * Realistic looking data for [days] days up to now, so the screens can be rendered like on a phone
 * that has been worn for a while: nights with sleep stages, a day rhythm, workouts, entries.
 */
object DemoData {
    fun fill(db: HealthDb, days: Int = 35, zone: ZoneId = ZoneId.systemDefault()) {
        val rnd = Random(42)
        val now = System.currentTimeMillis() / 1000 / 60 * 60
        val today = LocalDate.now(zone)
        val first = today.minusDays(days.toLong() - 1)
        val activity = ArrayList<ActivitySample>()
        val stress = ArrayList<StressSample>()
        val spo2 = ArrayList<ValueSample>()
        val hrv = ArrayList<ValueSample>()
        val temperature = ArrayList<ValueSample>()
        val breath = ArrayList<ValueSample>()
        val battery = ArrayList<ValueSample>()
        val resting = ArrayList<ValueSample>()
        val sdnn = ArrayList<ValueSample>()
        val pai = ArrayList<PaiSample>()
        val records = ArrayList<SleepRecord>()
        val odi = ArrayList<OdiNight>()

        var d = first
        var level = 100
        while (!d.isAfter(today)) {
            val dayStart = d.atStartOfDay(zone).toEpochSecond()
            val i = (d.toEpochDay() - first.toEpochDay()).toInt()
            // Night ending this morning: asleep about 23:20–06:50, a bit different every day.
            val sleepStart = dayStart - 40 * 60 + rnd.nextInt(-30, 40) * 60L
            val sleepEnd = dayStart + 6 * 3600 + 50 * 60 + rnd.nextInt(-40, 30) * 60L
            val segments = ArrayList<SleepRecord.Segment>()
            val charging = dayStart + 13 * 3600 + (i % 3) * 3600L
            for (m in 0 until 1440) {
                val ts = dayStart - 6 * 3600 + m * 60L // from 18:00 the evening before
                if (ts > now) break
                if (ts < first.atStartOfDay(zone).toEpochSecond()) continue
                if (ts >= dayStart + 18 * 3600) break
                val asleep = ts in sleepStart until sleepEnd
                val sample = when {
                    asleep -> {
                        val cycle = ((ts - sleepStart) / 60 % 90).toInt()
                        val deep = cycle in 20 until 45 && ts - sleepStart < 4 * 3600
                        val rem = cycle in 70 until 90
                        ActivitySample(
                            ts, ActivitySample.KIND_SLEEP, rnd.nextInt(0, 6), 0, 50 + rnd.nextInt(0, 8) + if (rem) 6 else 0,
                            1, if (deep) 100 else 0, if (rem) 100 else 0,
                        )
                    }
                    ts in charging until charging + 3600 -> ActivitySample(ts, ActivitySample.KIND_CHARGING, 0, 0, 0, 0, 0, 0)
                    else -> {
                        val hour = ((ts - dayStart) / 3600).toInt()
                        val walking = rnd.nextInt(100) < (if (hour in 7..19) 25 else 5)
                        val workout = i % 2 == 0 && ts in dayStart + 17 * 3600 until dayStart + 17 * 3600 + 40 * 60
                        val steps = if (workout) 150 + rnd.nextInt(0, 20) else if (walking) rnd.nextInt(20, 110) else rnd.nextInt(0, 4)
                        val hr = if (workout) 140 + rnd.nextInt(0, 25) else 66 + rnd.nextInt(0, 12) + steps / 6
                        ActivitySample(ts, 1, (steps * 1.5).toInt().coerceAtMost(255), steps, hr, 0, 0, 0)
                    }
                }
                activity += sample
                if (asleep) {
                    if (m % 2 == 0) hrv += ValueSample(ts, 45 + rnd.nextInt(0, 25))
                    if (m % 5 == 0) temperature += ValueSample(ts, 3420 + rnd.nextInt(0, 40) + if (i == days - 2) 70 else 0)
                    if (m % 5 == 0) breath += ValueSample(ts, 14 + rnd.nextInt(0, 3))
                    if (m % 10 == 0) spo2 += ValueSample(ts, 94 + rnd.nextInt(0, 5))
                } else if (!sample.notWorn) {
                    if (m % 5 == 0) stress += StressSample(ts, (35 + 25 * sin(m / 90.0) + rnd.nextInt(-10, 10)).toInt().coerceIn(1, 99))
                    if (m % 10 == 0) hrv += ValueSample(ts, 30 + rnd.nextInt(0, 25))
                    if (m % 15 == 0) temperature += ValueSample(ts, 3250 + rnd.nextInt(0, 80))
                    if (m % 60 == 0) spo2 += ValueSample(ts, 96 + rnd.nextInt(0, 3))
                }
                if (m % 60 == 0) {
                    level = if (sample.rawKind == ActivitySample.KIND_CHARGING) 100 else (level - 1).coerceAtLeast(5)
                    battery += ValueSample(ts, level)
                }
            }
            if (sleepEnd <= now) {
                var t = sleepStart
                while (t < sleepEnd) {
                    val cycle = ((t - sleepStart) / 60 % 90).toInt()
                    val stage = when {
                        cycle in 20 until 45 && t - sleepStart < 4 * 3600 -> SleepStage.DEEP
                        cycle in 70 until 90 -> SleepStage.REM
                        cycle == 60 -> SleepStage.AWAKE
                        else -> SleepStage.LIGHT
                    }
                    val end = minOf(t + 10 * 60, sleepEnd)
                    if (segments.lastOrNull()?.stage == stage) segments[segments.lastIndex] = segments.last().copy(end = end) else segments += SleepRecord.Segment(t, end, stage)
                    t = end
                }
                if (i >= days - 10) records += SleepRecord(dayStart, sleepEnd + 600, sleepStart, sleepEnd, 70 + rnd.nextInt(0, 20), segments)
                if (i >= days - 6) odi += OdiNight(dayStart, 6 * 3600L, rnd.nextLong(1, 6), 80 + rnd.nextInt(0, 15), rnd.nextInt(2, 12))
                resting += ValueSample(dayStart + 8 * 3600, 54 + rnd.nextInt(0, 5) + if (i == days - 2) 6 else 0)
            }
            for (h in listOf(9, 14, 20)) {
                val ts = dayStart + h * 3600L
                if (ts <= now) sdnn += ValueSample(ts, 40 + rnd.nextInt(0, 30))
            }
            if (dayStart + 20 * 3600 <= now) {
                pai += PaiSample(dayStart + 20 * 3600, if (i % 2 == 0) 18f else 4f, 60f + i, 40, if (i % 2 == 0) 30 else 5, if (i % 2 == 0) 12 else 0)
            }
            d = d.plusDays(1)
        }
        db.insertActivity(activity)
        db.insertStress(stress)
        db.insertValues(HealthDb.SPO2, spo2)
        db.insertValues(HealthDb.HRV, hrv)
        db.insertValues(HealthDb.TEMPERATURE, temperature)
        db.insertValues(HealthDb.RESPIRATION, breath)
        db.insertValues(HealthDb.BATTERY, battery)
        db.insertValues(HealthDb.RESTING_HR, resting)
        db.insertValues(HealthDb.SDNN, sdnn)
        db.insertPai(pai)
        db.insertSleepRecords(records)
        db.insertOdi(odi)

        // Entries, symptoms, feelings, diary tags.
        for (k in 0 until days) {
            val day = today.minusDays(k.toLong())
            val start = day.atStartOfDay(zone).toEpochSecond()
            fun at(h: Int, m: Int = 0) = (start + h * 3600L + m * 60).takeIf { it <= now }
            at(7, 45)?.let { db.addEvent(it, "Koffein") }
            if (k % 2 == 0) at(13, 10)?.let { db.addEvent(it, "Koffein") }
            if (k % 3 == 0) at(16, 30)?.let { db.addEvent(it, "Nikotin") }
            if (k % 7 == 5) at(21)?.let { db.addEvent(it, "Alkohol", "zwei Bier") }
            if (k == 1) at(10)?.let { db.addEvent(it, "Kopfschmerzen") }
            if (k == 2) at(15)?.let { db.addEvent(it, "${de.waschbeken.heliolink.service.StressPrompt.PREFIX}Arbeit") }
            db.setFeeling(day.toEpochDay(), 2 + (k * 7 % 4), if (k == 0) "Gut geschlafen" else "")
            if (k % 4 == 1) db.setSleepTag(day.toEpochDay(), "Spät gegessen", true)
        }

        // Workouts with second-by-second heart rate, and a recording today.
        for (k in listOf(2, 4, 6, 9)) {
            val start = today.minusDays(k.toLong()).atStartOfDay(zone).toEpochSecond() + 17 * 3600
            val id = db.startWorkout(if (k == 9) "Radfahren" else "Laufen", start)
            for (s in 0 until 40 * 60 step 5) db.addWorkoutHr(id, start + s, (120 + 30 * sin(s / 300.0) + rnd.nextInt(0, 10)).toInt())
            db.finishWorkout(Workout(id, "Laufen", start, start + 40 * 60, 146, 172, 410, listOf(120, 600, 900, 600, 180)))
            db.setWorkoutHrr(id, 24)
        }
        val recStart = now - 50 * 60
        val rec = db.startWorkout(RECORDING_SPORT, recStart)
        for (s in 0 until 10 * 60) db.addWorkoutHr(rec, recStart + s, 70 + (s / 20) % 9)
        db.finishWorkout(Workout(rec, RECORDING_SPORT, recStart, recStart + 600, 74, 79, 20, listOf(600, 0, 0, 0, 0)))
        db.setWorkoutNote(rec, listOf("Sitzen", "Koffein"), "nach dem Kaffee")
        for (s in 0 until 15 * 60) db.insertHr(now - 30 * 60 + s, 68 + (s / 30) % 7)

        // Heart rhythm checks of the last nights, two irregular ones.
        for (k in 1..5) {
            val ts = today.minusDays(k.toLong()).atStartOfDay(zone).toEpochSecond() + 3 * 3600
            db.insertRhythm(ts, RhythmCheck.Window(64, 55, if (k <= 2) 0.14 else 0.04, if (k <= 2) 0.66 else 0.5, if (k <= 2) 0.8 else 0.4))
        }
        db.saveAlarm(StrapAlarm(0, true, true, 6, 45, StrapAlarm.WEEKDAYS), "Arbeit", pending = false)
        db.saveAlarm(StrapAlarm(1, false, false, 9, 0, 0x60), "Wochenende", pending = true)
    }
}
