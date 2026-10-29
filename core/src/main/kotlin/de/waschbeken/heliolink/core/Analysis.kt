package de.waschbeken.heliolink.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

data class SleepSession(
    val start: Long, // epoch seconds
    val end: Long, // epoch seconds, exclusive
    val stages: List<Pair<Long, SleepStage>>, // per minute
    val avgHeartRate: Int?,
    val minHeartRate: Int?,
) {
    fun minutes(stage: SleepStage) = stages.count { it.second == stage }
    val asleepMinutes get() = stages.count { it.second != SleepStage.AWAKE }
    val durationMinutes get() = ((end - start) / 60).toInt()

    /** Simple 0-100 score from duration, deep/REM share and interruptions. */
    val score: Int
        get() {
            if (asleepMinutes == 0) return 0
            val durationScore = (asleepMinutes / 480.0).coerceAtMost(1.0) * 50
            val deepShare = minutes(SleepStage.DEEP).toDouble() / asleepMinutes
            val remShare = minutes(SleepStage.REM).toDouble() / asleepMinutes
            val deepScore = (deepShare / 0.2).coerceAtMost(1.0) * 20
            val remScore = (remShare / 0.2).coerceAtMost(1.0) * 15
            val efficiency = asleepMinutes.toDouble() / durationMinutes.coerceAtLeast(1)
            val efficiencyScore = ((efficiency - 0.75) / 0.2).coerceIn(0.0, 1.0) * 15
            return (durationScore + deepScore + remScore + efficiencyScore).roundToInt()
        }
}

object SleepAnalyzer {
    /** Groups sleep minutes into sessions, awake gaps up to [maxGapMinutes] are kept inside a session. */
    fun sessions(samples: List<ActivitySample>, maxGapMinutes: Int = 45, minMinutes: Int = 20): List<SleepSession> {
        val sorted = samples.sortedBy { it.timestamp }
        val result = ArrayList<SleepSession>()
        var current = ArrayList<ActivitySample>()
        var lastSleep: ActivitySample? = null

        fun flush() {
            val first = current.firstOrNull() ?: return
            val last = lastSleep ?: return
            val inSession = current.filter { it.timestamp <= last.timestamp }
            val asleep = inSession.count { it.isSleep }
            if (asleep >= minMinutes) {
                val hr = inSession.map { it.heartRate }.filter { it > 0 }
                result += SleepSession(
                    start = first.timestamp,
                    end = last.timestamp + 60,
                    stages = inSession.map { it.timestamp to it.sleepStage },
                    avgHeartRate = if (hr.isEmpty()) null else hr.average().roundToInt(),
                    minHeartRate = hr.minOrNull(),
                )
            }
            current = ArrayList()
            lastSleep = null
        }

        for (s in sorted) {
            val ls = lastSleep
            if (s.isSleep) {
                if (ls != null && s.timestamp - ls.timestamp > maxGapMinutes * 60L) flush()
                current += s
                lastSleep = s
            } else if (ls != null) {
                if (s.timestamp - ls.timestamp > maxGapMinutes * 60L) flush() else current += s
            }
        }
        flush()
        return result
    }

    /** The main sleep that ended on [date] (between 18:00 the evening before and 18:00 on that day). */
    fun nightOf(date: LocalDate, sessions: List<SleepSession>, zone: ZoneId): SleepSession? {
        val from = date.minusDays(1).atTime(18, 0).atZone(zone).toEpochSecond()
        val to = date.atTime(18, 0).atZone(zone).toEpochSecond()
        return sessions.filter { it.end in (from + 1)..to }.maxByOrNull { it.asleepMinutes }
    }
}

data class DaySummary(
    val date: LocalDate,
    val steps: Int,
    val avgHeartRate: Int?,
    val minHeartRate: Int?,
    val maxHeartRate: Int?,
    val restingHeartRate: Int?,
    val wornMinutes: Int,
)

object DayAnalyzer {
    fun summarize(date: LocalDate, samples: List<ActivitySample>, zone: ZoneId): DaySummary {
        val day = samples.filter { Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() == date }
        val hr = day.map { it.heartRate }.filter { it > 0 }
        // Resting HR: the lowest 10 minute rolling average while awake and not moving.
        val calm = day.filter { it.heartRate > 0 && it.steps == 0 && !it.notWorn }.map { it.heartRate }
        val resting = if (calm.size >= 10) calm.windowed(10).minOf { it.average() }.roundToInt() else hr.minOrNull()
        return DaySummary(
            date = date,
            steps = day.sumOf { it.steps },
            avgHeartRate = if (hr.isEmpty()) null else hr.average().roundToInt(),
            minHeartRate = hr.minOrNull(),
            maxHeartRate = hr.maxOrNull(),
            restingHeartRate = resting,
            wornMinutes = day.count { !it.notWorn && (it.heartRate > 0 || it.steps > 0 || it.isSleep) },
        )
    }
}

/** Heart rate zones as percentage of the maximum heart rate. */
object HeartRateZones {
    val names = listOf("Erholung", "Fettverbrennung", "Aerob", "Anaerob", "Maximal")
    private val lowerBounds = listOf(0.5, 0.6, 0.7, 0.8, 0.9)

    fun maxHeartRate(age: Int) = (208 - 0.7 * age).roundToInt() // Tanaka

    /** 0..4 for the zones, -1 below zone 1. */
    fun zoneOf(bpm: Int, maxHr: Int): Int {
        val pct = bpm.toDouble() / maxHr
        return lowerBounds.indexOfLast { pct >= it }
    }

    fun lowerBpm(zone: Int, maxHr: Int) = (lowerBounds[zone] * maxHr).roundToInt()
}

object Calories {
    /** kcal per minute after Keytel et al. (2005). */
    fun perMinute(bpm: Int, weightKg: Double, age: Int, male: Boolean): Double {
        val kj = if (male) {
            -55.0969 + 0.6309 * bpm + 0.1988 * weightKg + 0.2017 * age
        } else {
            -20.4022 + 0.4472 * bpm - 0.1263 * weightKg + 0.074 * age
        }
        return (kj / 4.184).coerceAtLeast(0.0)
    }
}

/** RMSSD in ms from RR intervals, a common HRV measure. */
fun rmssd(rr: List<Int>): Double? {
    val clean = rr.filter { it in 300..2000 }
    if (clean.size < 3) return null
    val diffs = clean.zipWithNext { a, b -> (b - a).toDouble() }
    return kotlin.math.sqrt(diffs.sumOf { it * it } / diffs.size)
}
