package de.waschbeken.heliolink.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How regular the nights are: when you go to bed and get up on average and how much that varies,
 * how far the weekend shifts it ("social jetlag"), and whether your own nights at the usual time
 * score better than the ones far off it.
 *
 * Times are minutes after noon of the evening before the night's wake-up day, so 23:30 is 690 and
 * 00:30 is 750: a night past midnight stays later than one before it.
 */
object SleepRhythm {
    data class Night(
        /** The day the night ended (as the sleep pages count nights). */
        val date: LocalDate,
        /** Epoch seconds. */
        val start: Long,
        val end: Long,
        val score: Int,
    )

    enum class Rating(val label: String) {
        VERY_REGULAR("sehr regelmäßig"),
        REGULAR("ziemlich regelmäßig"),
        VARIABLE("schwankend"),
        IRREGULAR("unregelmäßig"),
    }

    data class Result(
        val nights: Int,
        /** Average bedtime, minutes after noon. */
        val bedtime: Int,
        /** Standard deviation of the bedtimes, minutes. */
        val bedSpread: Int,
        val wake: Int,
        val wakeSpread: Int,
        val rating: Rating,
        /**
         * Minutes the middle of the night lies later on free days (nights before Saturday and
         * Sunday) than on work days, negative when earlier; null with fewer than 2 nights of each.
         */
        val weekendShift: Int?,
        /** Average score of the nights within 30 minutes of the usual bedtime, and of those an hour or more off it (null with fewer than 3). */
        val scoreNear: Int?,
        val scoreFar: Int?,
    )

    /** Minutes after noon of the day before [day] for an epoch second. */
    fun minutesAfterNoon(epoch: Long, day: LocalDate, zone: ZoneId): Int {
        val noon = day.minusDays(1).atTime(LocalTime.NOON).atZone(zone).toEpochSecond()
        return ((epoch - noon) / 60).toInt()
    }

    /** "23:30" for minutes after noon. */
    fun clock(minutesAfterNoon: Int): String {
        val m = ((minutesAfterNoon + 12 * 60) % 1440 + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    private fun mean(v: List<Int>) = v.average()

    private fun spread(v: List<Int>): Int {
        if (v.size < 2) return 0
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size).roundToInt()
    }

    fun rating(bedSpread: Int, wakeSpread: Int): Rating {
        val s = (bedSpread + wakeSpread) / 2
        return when {
            s <= 30 -> Rating.VERY_REGULAR
            s <= 45 -> Rating.REGULAR
            s <= 75 -> Rating.VARIABLE
            else -> Rating.IRREGULAR
        }
    }

    /** Null with fewer than 3 nights: too few to say anything about a rhythm. */
    fun of(nights: List<Night>, zone: ZoneId): Result? {
        if (nights.size < 3) return null
        val bed = nights.map { minutesAfterNoon(it.start, it.date, zone) }
        val wake = nights.map { minutesAfterNoon(it.end, it.date, zone) }
        val bedMean = mean(bed)

        // Free days: the nights before Saturday and Sunday, i.e. ending on Saturday or Sunday.
        val mid = nights.map { n -> n to minutesAfterNoon((n.start + n.end) / 2, n.date, zone) }
        val free = mid.filter { it.first.date.dayOfWeek == DayOfWeek.SATURDAY || it.first.date.dayOfWeek == DayOfWeek.SUNDAY }.map { it.second }
        val work = mid.filter { it.first.date.dayOfWeek != DayOfWeek.SATURDAY && it.first.date.dayOfWeek != DayOfWeek.SUNDAY }.map { it.second }
        val shift = if (free.size >= 2 && work.size >= 2) (mean(free) - mean(work)).roundToInt() else null

        val near = nights.filterIndexed { i, _ -> abs(bed[i] - bedMean) <= 30 }.map { it.score }
        val far = nights.filterIndexed { i, _ -> abs(bed[i] - bedMean) >= 60 }.map { it.score }
        val bothSides = near.size >= 3 && far.size >= 3

        val bedSpread = spread(bed)
        val wakeSpread = spread(wake)
        return Result(
            nights = nights.size,
            bedtime = bedMean.roundToInt(),
            bedSpread = bedSpread,
            wake = mean(wake).roundToInt(),
            wakeSpread = wakeSpread,
            rating = rating(bedSpread, wakeSpread),
            weekendShift = shift,
            scoreNear = if (bothSides) near.average().roundToInt() else null,
            scoreFar = if (bothSides) far.average().roundToInt() else null,
        )
    }
}
