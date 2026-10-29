package de.waschbeken.heliolink.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** Values of one day, for week and month charts. */
data class DayStats(
    val date: LocalDate,
    val steps: Int,
    val activeMinutes: Int,
    val avgHeartRate: Int?,
    val minHeartRate: Int?,
    val maxHeartRate: Int?,
    val restingHeartRate: Int?,
    val wornMinutes: Int,
)

object Aggregates {
    /** Per-day statistics for every day between [from] and [to] (inclusive). */
    fun days(samples: List<ActivitySample>, from: LocalDate, to: LocalDate, zone: ZoneId): List<DayStats> {
        val byDay = samples.groupBy { Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }
        val out = ArrayList<DayStats>()
        var d = from
        while (!d.isAfter(to)) {
            val day = byDay[d].orEmpty()
            val summary = DayAnalyzer.summarize(d, day, zone)
            out += DayStats(
                date = d,
                steps = summary.steps,
                activeMinutes = day.count { it.steps >= 30 || (!it.isSleep && it.intensity >= 40) },
                avgHeartRate = summary.avgHeartRate,
                minHeartRate = summary.minHeartRate,
                maxHeartRate = summary.maxHeartRate,
                restingHeartRate = summary.restingHeartRate,
                wornMinutes = summary.wornMinutes,
            )
            d = d.plusDays(1)
        }
        return out
    }

    /** Average of the non-null values, or null. */
    fun avg(values: List<Int?>): Int? = values.filterNotNull().takeIf { it.isNotEmpty() }?.average()?.roundToInt()

    /** Days in a row (ending yesterday or today) on which the step goal was reached. */
    fun goalStreak(days: List<DayStats>, goal: Int, today: LocalDate): Int {
        val reached = days.filter { it.steps >= goal }.map { it.date }.toSet()
        var d = if (today in reached) today else today.minusDays(1)
        var streak = 0
        while (d in reached) {
            streak++
            d = d.minusDays(1)
        }
        return streak
    }
}

/** A walk, run or other movement found in the per-minute samples. */
data class DetectedActivity(
    val start: Long,
    val end: Long,
    val steps: Int,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val kind: Kind,
) {
    enum class Kind(val label: String) { WALK("Gehen"), RUN("Laufen"), OTHER("Aktivität") }

    val minutes get() = ((end - start) / 60).toInt()
    val cadence get() = if (minutes > 0) steps / minutes else 0
}

object ActivityDetector {
    /**
     * Finds periods of at least [minMinutes] with steady movement (≥ 60 steps/min) or a heart rate well
     * above rest while moving. Short pauses up to [maxGap] minutes are bridged.
     */
    fun detect(samples: List<ActivitySample>, restingHr: Int?, minMinutes: Int = 8, maxGap: Int = 3): List<DetectedActivity> {
        val sorted = samples.sortedBy { it.timestamp }
        val hrThreshold = (restingHr ?: 65) + 35
        fun active(s: ActivitySample) = !s.isSleep && !s.notWorn &&
            (s.steps >= 60 || (s.heartRate >= hrThreshold && s.intensity >= 30))
        val result = ArrayList<DetectedActivity>()
        var run = ArrayList<ActivitySample>()
        var lastActive: ActivitySample? = null
        fun flush() {
            val first = run.firstOrNull() ?: return
            val last = lastActive ?: return
            val part = run.filter { it.timestamp <= last.timestamp }
            if ((last.timestamp - first.timestamp) / 60 + 1 >= minMinutes) {
                val hr = part.map { it.heartRate }.filter { it > 0 }
                val steps = part.sumOf { it.steps }
                val minutes = ((last.timestamp - first.timestamp) / 60 + 1).toInt()
                val cadence = steps / minutes
                result += DetectedActivity(
                    start = first.timestamp,
                    end = last.timestamp + 60,
                    steps = steps,
                    avgHeartRate = if (hr.isEmpty()) null else hr.average().roundToInt(),
                    maxHeartRate = hr.maxOrNull(),
                    kind = when {
                        cadence >= 135 -> DetectedActivity.Kind.RUN
                        cadence >= 60 -> DetectedActivity.Kind.WALK
                        else -> DetectedActivity.Kind.OTHER
                    },
                )
            }
            run = ArrayList()
            lastActive = null
        }
        for (s in sorted) {
            val la = lastActive
            if (la != null && s.timestamp - la.timestamp > (maxGap + 1) * 60L) flush()
            if (active(s)) {
                run += s
                lastActive = s
            } else if (lastActive != null) {
                run += s
            }
        }
        flush()
        return result
    }
}

/**
 * Recovery score 0–100 from last night's sleep, HRV and resting heart rate compared to the personal
 * baseline of the previous weeks. Each part is optional.
 */
data class Readiness(val score: Int, val parts: List<Pair<String, Int>>) {
    companion object {
        fun calculate(
            sleepScore: Int?,
            hrv: Double?,
            hrvBaseline: Double?,
            restingHr: Int?,
            restingBaseline: Double?,
        ): Readiness? {
            val parts = ArrayList<Pair<String, Int>>()
            if (sleepScore != null) parts += "Schlaf" to sleepScore.coerceIn(0, 100)
            if (hrv != null && hrvBaseline != null && hrvBaseline > 0) {
                // +-25 % around the baseline maps to 25..100
                val ratio = hrv / hrvBaseline
                parts += "HRV" to (62.5 + (ratio - 1) * 150).roundToInt().coerceIn(0, 100)
            }
            if (restingHr != null && restingBaseline != null && restingBaseline > 0) {
                // every bpm above the baseline costs 8 points
                parts += "Ruhepuls" to (85 - (restingHr - restingBaseline) * 8).roundToInt().coerceIn(0, 100)
            }
            if (parts.isEmpty()) return null
            return Readiness(parts.map { it.second }.average().roundToInt(), parts)
        }
    }

    val label
        get() = when {
            score >= 80 -> "Top erholt"
            score >= 65 -> "Gut erholt"
            score >= 45 -> "Mittel – lockerer Tag"
            else -> "Erschöpft – Pause gönnen"
        }
}

object BatteryForecast {
    /**
     * Days until empty from the battery levels (epoch seconds, %) since the last charge, or null if it
     * cannot be estimated (charging or too little data).
     */
    fun daysLeft(levels: List<ValueSample>): Double? {
        if (levels.size < 2) return null
        val sorted = levels.sortedBy { it.timestamp }
        // Only the part after the last increase (charging).
        var start = 0
        for (i in 1 until sorted.size) if (sorted[i].value > sorted[i - 1].value + 1) start = i
        val part = sorted.subList(start, sorted.size)
        if (part.size < 2) return null
        val hours = (part.last().timestamp - part.first().timestamp) / 3600.0
        val used = part.first().value - part.last().value
        if (hours < 6 || used <= 0) return null
        val perHour = used / hours
        return part.last().value / perHour / 24
    }
}

/** Night values compared to the personal average, e.g. skin temperature deviation. */
object Baseline {
    fun deviation(value: Double?, history: List<Double>): Double? {
        if (value == null || history.size < 3) return null
        return value - history.average()
    }

    fun isUnusual(deviation: Double?, threshold: Double) = deviation != null && abs(deviation) >= threshold
}
