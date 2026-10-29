package de.waschbeken.heliolink.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What happened on one day, for the goals. Null = no data. */
data class DayFacts(
    val date: LocalDate,
    /** Sleep of the night ending this morning. */
    val sleepMinutes: Int? = null,
    /** When that night started, minutes after midnight. */
    val sleepStart: Int? = null,
    val steps: Int? = null,
    val activeMinutes: Int? = null,
    val workouts: Int = 0,
    /** Quick entries of the day. */
    val tags: Set<String> = emptySet(),
)

/** A personal goal: on [perWeek] days a week, e.g. "Schlaf ≥ 7 h an 5 Tagen". */
data class Goal(val kind: Kind, val value: Int, val perWeek: Int, val tag: String = "") {
    enum class Kind(val label: String) {
        SLEEP("Schlaf mindestens"),
        BEDTIME("Einschlafen vor"),
        STEPS("Schritte mindestens"),
        ACTIVE("Aktive Minuten mindestens"),
        WORKOUT("Training"),
        WITHOUT("Ohne"),
    }

    /** E.g. "Schlaf ≥ 7 h an 5 Tagen pro Woche" or "Ohne Nikotin". */
    val title: String
        get() = when (kind) {
            Kind.SLEEP -> "Schlaf ≥ ${value / 60}" + (if (value % 60 != 0) ":%02d".format(value % 60) else "") + " h"
            Kind.BEDTIME -> "Einschlafen vor %02d:%02d".format(value / 60, value % 60)
            Kind.STEPS -> "≥ ${"%,d".format(value).replace(',', '.')} Schritte"
            Kind.ACTIVE -> "≥ $value aktive Minuten"
            Kind.WORKOUT -> "Training"
            Kind.WITHOUT -> "Ohne $tag"
        } + if (perWeek >= 7) " jeden Tag" else " an $perWeek Tagen pro Woche"

    /** Whether the goal was met on that day; null without data. */
    fun met(f: DayFacts): Boolean? = when (kind) {
        Kind.SLEEP -> f.sleepMinutes?.let { it >= value }
        // Shifted by 12 h so that 23:30 counts as earlier than 00:30.
        Kind.BEDTIME -> f.sleepStart?.let { (it + 720) % 1440 < (value + 720) % 1440 }
        Kind.STEPS -> f.steps?.let { it >= value }
        Kind.ACTIVE -> f.activeMinutes?.let { it >= value }
        Kind.WORKOUT -> f.workouts > 0
        Kind.WITHOUT -> tag !in f.tags
    }

    fun encode() = "${kind.name}|$value|$perWeek|${tag.replace("|", " ")}"

    companion object {
        fun decode(text: String): Goal? {
            val p = text.split('|')
            if (p.size < 3) return null
            val kind = Kind.entries.firstOrNull { it.name == p[0] } ?: return null
            return Goal(kind, p[1].toIntOrNull() ?: return null, (p[2].toIntOrNull() ?: return null).coerceIn(1, 7), p.getOrElse(3) { "" })
        }
    }
}

object Goals {
    /** Monday of the week of [date]. */
    fun weekStart(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

    data class Week(val start: LocalDate, val days: List<Boolean?>, val done: Int, val target: Int) {
        val reached get() = done >= target
    }

    /** The week (Monday–Sunday) containing [date]; days after [today] are null. */
    fun week(goal: Goal, facts: Map<LocalDate, DayFacts>, date: LocalDate, today: LocalDate): Week {
        val start = weekStart(date)
        val days = (0L..6L).map { i ->
            val d = start.plusDays(i)
            if (d.isAfter(today)) null else goal.met(facts[d] ?: DayFacts(d))
        }
        return Week(start, days, days.count { it == true }, goal.perWeek)
    }

    /** Finished weeks in a row (before the current one) in which the goal was reached. */
    fun streak(goal: Goal, facts: Map<LocalDate, DayFacts>, today: LocalDate, maxWeeks: Int = 52): Int {
        var n = 0
        var start = weekStart(today).minusWeeks(1)
        while (n < maxWeeks && week(goal, facts, start, today).reached) {
            n++
            start = start.minusWeeks(1)
        }
        return n
    }
}

/** "Days without …": since when there was no entry of a tag, and the longest stretch without. */
object Abstinence {
    data class Result(val daysSince: Long?, val longest: Long, val entries: Int)

    /** [dates] of the entries, counting from [since] (first day with data) to [today]. */
    fun of(dates: Collection<LocalDate>, since: LocalDate, today: LocalDate): Result {
        val sorted = dates.filter { !it.isAfter(today) }.distinct().sorted()
        val last = sorted.lastOrNull()
        val daysSince = last?.let { ChronoUnit.DAYS.between(it, today) }
        var longest = 0L
        var prev = since.minusDays(1)
        for (d in sorted) {
            longest = maxOf(longest, ChronoUnit.DAYS.between(prev, d) - 1)
            prev = d
        }
        longest = maxOf(longest, ChronoUnit.DAYS.between(prev, today))
        return Result(daysSince, longest, sorted.size)
    }
}
