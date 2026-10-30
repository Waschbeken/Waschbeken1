package de.waschbeken.heliolink.core

import java.time.ZonedDateTime
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** When a quick entry happened, from a time of day picked afterwards. */
object EntryTime {
    /** [hour]:[minute] on the day of [now], or the day before when that is still ahead (23:00 picked at 06:00 = last night). */
    fun at(hour: Int, minute: Int, now: ZonedDateTime): Long {
        val sameDay = now.toLocalDate().atTime(hour, minute).atZone(now.zone)
        return (if (sameDay.isAfter(now.plusMinutes(1))) sameDay.minusDays(1) else sameDay).toEpochSecond()
    }
}

/** A short HRV measurement at rest (e.g. right after waking up) from the beat intervals. */
object HrvMeasurement {
    data class Result(val rmssd: Int, val heartRate: Int, val beats: Int, val artefacts: Int)

    /**
     * RMSSD of [rr] (ms). Intervals outside 300–2000 ms and jumps of more than 20 % (missed or
     * extra beats from the sensor) are left out. Null with fewer than 40 usable beats.
     */
    fun compute(rr: List<Int>): Result? {
        val plausible = rr.filter { it in 300..2000 }
        if (plausible.size < 40) return null
        val diffs = ArrayList<Double>()
        var artefacts = rr.size - plausible.size
        for (i in 1 until plausible.size) {
            val a = plausible[i - 1]
            val b = plausible[i]
            if (kotlin.math.abs(b - a) <= a * 0.2) diffs += (b - a).toDouble() else artefacts++
        }
        if (diffs.size < 30) return null
        val rmssd = sqrt(diffs.sumOf { it * it } / diffs.size)
        return Result(rmssd.roundToInt(), (60_000 / plausible.average()).roundToInt(), plausible.size, artefacts)
    }
}

/**
 * A stressful moment worth asking about: stress of at least [level] for [minMinutes] or more,
 * ending in the last hour, while not moving much.
 */
object StressMoments {
    data class Moment(val start: Long, val end: Long, val average: Int)

    /** [stress] are (epoch seconds, 0–100), [steps] steps per minute (epoch seconds of the minute, steps). */
    fun find(
        stress: List<Pair<Long, Int>>,
        steps: Map<Long, Int>,
        now: Long,
        level: Int = 70,
        minMinutes: Int = 15,
        maxGapMinutes: Int = 6,
    ): Moment? {
        val high = stress.filter { it.second >= level && it.first <= now }.sortedBy { it.first }
        var best: Moment? = null
        var i = 0
        while (i < high.size) {
            var j = i
            while (j + 1 < high.size && high[j + 1].first - high[j].first <= maxGapMinutes * 60L) j++
            val start = high[i].first
            val end = high[j].first + 60
            val stretch = high.subList(i, j + 1)
            if ((end - start) / 60 >= minMinutes && now - end <= 3600) {
                // Sport raises the stress value too: skip stretches with much movement.
                val minutes = ((end - start) / 60).toInt().coerceAtLeast(1)
                val moved = (start / 60 until end / 60).sumOf { steps[it * 60] ?: 0 }
                if (moved / minutes < 40) best = Moment(start, end, stretch.map { it.second }.average().roundToInt())
            }
            i = j + 1
        }
        return best
    }
}
