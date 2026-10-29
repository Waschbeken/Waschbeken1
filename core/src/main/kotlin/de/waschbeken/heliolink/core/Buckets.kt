package de.waschbeken.heliolink.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** How long periods are split for charts: per day, per week (Monday) or per month. */
enum class Granularity { DAY, WEEK, MONTH }

/** One bar of a long-period chart: the days [from]..[to] (inclusive) with a short [label] (may be empty). */
data class Bucket(val from: LocalDate, val to: LocalDate, val label: String) {
    operator fun contains(date: LocalDate) = !date.isBefore(from) && !date.isAfter(to)
    val days get() = (to.toEpochDay() - from.toEpochDay() + 1).toInt()
}

object Buckets {
    private val german = Locale.GERMANY

    private fun month(d: LocalDate) = d.month.getDisplayName(TextStyle.SHORT, german).removeSuffix(".").take(3)

    /**
     * Splits [from]..[to] into buckets. Week buckets start on Monday and are cut at the ends of the
     * range. [weekdays] labels day buckets with "Mo", "Di" … instead of the day of the month.
     */
    fun of(from: LocalDate, to: LocalDate, granularity: Granularity, weekdays: Boolean = false): List<Bucket> {
        val out = ArrayList<Bucket>()
        when (granularity) {
            Granularity.DAY -> {
                var d = from
                while (!d.isAfter(to)) {
                    val label = when {
                        weekdays -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, german).take(2)
                        d.dayOfMonth == 1 || d.dayOfMonth % 5 == 0 -> "${d.dayOfMonth}"
                        else -> ""
                    }
                    out += Bucket(d, d, label)
                    d = d.plusDays(1)
                }
            }
            Granularity.WEEK -> {
                var start = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                var lastMonth = -1
                while (!start.isAfter(to)) {
                    val a = maxOf(start, from)
                    val b = minOf(start.plusDays(6), to)
                    val label = if (b.monthValue != lastMonth) month(b) else ""
                    lastMonth = b.monthValue
                    out += Bucket(a, b, label)
                    start = start.plusWeeks(1)
                }
            }
            Granularity.MONTH -> {
                var start = from.withDayOfMonth(1)
                val many = from.until(to).toTotalMonths() > 14
                while (!start.isAfter(to)) {
                    val label = when {
                        !many -> month(start)
                        start.monthValue == 1 -> "${start.year}"
                        start.monthValue == 7 -> month(start)
                        else -> ""
                    }
                    out += Bucket(maxOf(start, from), minOf(start.plusMonths(1).minusDays(1), to), label)
                    start = start.plusMonths(1)
                }
            }
        }
        return out
    }

    /** Average of the days' values in each bucket (days without a value are left out), null if none. */
    fun average(buckets: List<Bucket>, value: (LocalDate) -> Double?): List<Float?> = buckets.map { b ->
        val values = generateSequence(b.from) { it.plusDays(1) }.takeWhile { !it.isAfter(b.to) }.mapNotNull(value).toList()
        if (values.isEmpty()) null else values.average().toFloat()
    }
}
