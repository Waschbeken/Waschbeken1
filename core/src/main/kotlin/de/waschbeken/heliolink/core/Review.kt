package de.waschbeken.heliolink.core

import java.time.LocalDate
import kotlin.math.roundToInt

/** Week, month or year at a glance, compared with the period before ("Rückblick"). */
object Review {
    /** One day of the period. Null = no data that day. */
    data class Day(
        val date: LocalDate,
        val sleepMinutes: Int? = null,
        val sleepScore: Int? = null,
        /** Fell asleep, minutes after midnight (late evening counts negative, e.g. 23:30 = −30). */
        val bedtime: Int? = null,
        val steps: Int? = null,
        val activeMinutes: Int? = null,
        val restingHr: Int? = null,
        val hrv: Double? = null,
        val stress: Int? = null,
    )

    data class Stats(
        val days: Int,
        val nights: Int,
        val sleepMinutes: Int?,
        val sleepScore: Int?,
        val bedtime: Int?,
        val stepsPerDay: Int?,
        val stepsTotal: Long,
        val activeMinutes: Int?,
        val restingHr: Int?,
        val hrv: Int?,
        val stress: Int?,
        val workouts: Int,
        val workoutMinutes: Int,
        val bestNight: Pair<LocalDate, Int>?,
        val mostSteps: Pair<LocalDate, Int>?,
        /** Quick entries by count, most first. */
        val tags: List<Pair<String, Int>>,
    )

    private fun List<Int>.avg() = if (isEmpty()) null else average().roundToInt()

    /**
     * [workoutMinutes] lists the length of each workout of the period. Days without steps (strap
     * not worn) do not lower the averages.
     */
    fun stats(days: List<Day>, workoutMinutes: List<Int>, tags: List<String>): Stats {
        val nights = days.filter { it.sleepMinutes != null && it.sleepMinutes > 0 }
        val stepDays = days.filter { (it.steps ?: 0) > 0 }
        return Stats(
            days = days.size,
            nights = nights.size,
            sleepMinutes = nights.mapNotNull { it.sleepMinutes }.avg(),
            sleepScore = nights.mapNotNull { it.sleepScore }.avg(),
            bedtime = nights.mapNotNull { it.bedtime }.avg(),
            stepsPerDay = stepDays.mapNotNull { it.steps }.avg(),
            stepsTotal = stepDays.sumOf { (it.steps ?: 0).toLong() },
            activeMinutes = stepDays.mapNotNull { it.activeMinutes }.avg(),
            restingHr = days.mapNotNull { it.restingHr }.avg(),
            hrv = days.mapNotNull { it.hrv }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            stress = days.mapNotNull { it.stress }.avg(),
            workouts = workoutMinutes.size,
            workoutMinutes = workoutMinutes.sum(),
            bestNight = nights.filter { it.sleepScore != null }.maxByOrNull { it.sleepScore!! }?.let { it.date to it.sleepScore!! },
            mostSteps = stepDays.maxByOrNull { it.steps!! }?.let { it.date to it.steps!! },
            tags = tags.groupingBy { it }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key })).map { it.key to it.value },
        )
    }

    /** Whether a change is good (true), bad (false) or neither (null) for the user. */
    enum class Direction { HIGHER_IS_BETTER, LOWER_IS_BETTER, NEUTRAL }

    /** The change from [before] to [now] if both are known and it is big enough to mention. */
    fun change(now: Int?, before: Int?, minimum: Int = 1): Int? {
        if (now == null || before == null) return null
        val d = now - before
        return if (kotlin.math.abs(d) >= minimum) d else 0
    }

    fun judge(change: Int?, direction: Direction): Boolean? = when {
        change == null || change == 0 || direction == Direction.NEUTRAL -> null
        direction == Direction.HIGHER_IS_BETTER -> change > 0
        else -> change < 0
    }

    /** Minutes after midnight with the evening as negative, from minutes of the day. */
    fun bedtimeOf(minuteOfDay: Int): Int = if (minuteOfDay >= 12 * 60) minuteOfDay - 24 * 60 else minuteOfDay
}
