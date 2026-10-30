package de.waschbeken.heliolink.ui.progress

import de.waschbeken.heliolink.core.Abstinence
import de.waschbeken.heliolink.core.DayFacts
import de.waschbeken.heliolink.core.Goals
import de.waschbeken.heliolink.core.HeartRateZones
import de.waschbeken.heliolink.core.Review
import de.waschbeken.heliolink.core.ValueSample
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.data.Queries
import de.waschbeken.heliolink.data.UserProfile
import de.waschbeken.heliolink.service.RECORDING_SPORT
import de.waschbeken.heliolink.service.StressPrompt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the goals and "Tage ohne …" need: a year of day facts and when each entry was made. */
internal class ProgressFacts(
    val days: Map<LocalDate, DayFacts>,
    val abstinence: Map<String, Abstinence.Result>,
)

/** One value per day of a year for the calendar. */
internal enum class CalendarMetric(val label: String, val lowIsGood: Boolean = false) {
    SLEEP("Schlaf"),
    SCORE("Schlaf-Score"),
    STEPS("Schritte"),
    ACTIVE("Aktive Minuten"),
    RESTING("Ruhepuls", lowIsGood = true),
    STRESS("Stress", lowIsGood = true),
}

/** The review of a period and of the one before. */
internal class ReviewData(val from: LocalDate, val to: LocalDate, val now: Review.Stats, val before: Review.Stats)

internal object ProgressData {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun zoneLower(profile: UserProfile) = IntArray(5) { HeartRateZones.lowerBpm(it, profile.maxHeartRate, profile.zoneRest(null)) }

    private fun day(ts: Long) = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate()

    private fun minuteOfDay(ts: Long) = Instant.ofEpochSecond(ts).atZone(zone).let { it.hour * 60 + it.minute }

    /** Recordings with the pulse only are not workouts. */
    private fun workouts(db: HealthDb, from: LocalDate, to: LocalDate) =
        db.workouts().filter { it.sport != RECORDING_SPORT && day(it.start) in from..to && it.end > it.start }

    /** Day facts from [weeks] weeks before the current one up to [today]. */
    fun facts(db: HealthDb, profile: UserProfile, today: LocalDate, tags: List<String>, weeks: Long = 52): ProgressFacts {
        val from = Goals.weekStart(today).minusWeeks(weeks)
        val nights = Queries.nightSummaries(db, from, today, zone)
        val totals = Queries.dayTotals(db, from, today, zone, zoneLower(profile))
        val workouts = workouts(db, from, today).groupingBy { day(it.start) }.eachCount()
        val events = db.events(from.atStartOfDay(zone).toEpochSecond(), today.plusDays(1).atStartOfDay(zone).toEpochSecond())
            .groupBy({ day(it.timestamp) }, { it.tag })
        val days = HashMap<LocalDate, DayFacts>()
        var d = from
        while (!d.isAfter(today)) {
            val night = nights[d]
            val t = totals[d]
            days[d] = DayFacts(
                date = d,
                sleepMinutes = night?.asleepMinutes,
                sleepStart = night?.let { minuteOfDay(it.start) },
                steps = t?.steps?.takeIf { it > 0 },
                activeMinutes = t?.let { if (it.steps > 0) it.activeMinutes else null },
                workouts = workouts[d] ?: 0,
                tags = events[d].orEmpty().toSet(),
            )
            d = d.plusDays(1)
        }
        // "Tage ohne …" counts from the first day with any data.
        val first = listOfNotNull(db.firstActivityTimestamp(), db.firstEventTimestamp()).minOrNull()?.let { day(it) } ?: today
        val all = if (tags.isEmpty()) emptyList() else db.events(0, today.plusDays(1).atStartOfDay(zone).toEpochSecond())
        val abstinence = tags.associateWith { tag ->
            Abstinence.of(all.filter { it.tag == tag }.map { day(it.timestamp) }, first, today)
        }
        return ProgressFacts(days, abstinence)
    }

    fun calendar(db: HealthDb, profile: UserProfile, year: Int, metric: CalendarMetric): Map<LocalDate, Float> {
        val from = LocalDate.of(year, 1, 1)
        val to = minOf(LocalDate.of(year, 12, 31), LocalDate.now())
        if (to < from) return emptyMap()
        val start = from.atStartOfDay(zone).toEpochSecond()
        val end = to.plusDays(1).atStartOfDay(zone).toEpochSecond()
        return when (metric) {
            CalendarMetric.SLEEP -> Queries.nightSummaries(db, from, to, zone).mapValues { it.value.asleepMinutes.toFloat() }
            CalendarMetric.SCORE -> Queries.nightSummaries(db, from, to, zone).mapValues { it.value.score.toFloat() }
            CalendarMetric.STEPS -> Queries.dayTotals(db, from, to, zone, zoneLower(profile))
                .filterValues { it.steps > 0 }.mapValues { it.value.steps.toFloat() }
            CalendarMetric.ACTIVE -> Queries.dayTotals(db, from, to, zone, zoneLower(profile))
                .filterValues { it.steps > 0 }.mapValues { it.value.activeMinutes.toFloat() }
            CalendarMetric.RESTING -> db.values(HealthDb.RESTING_HR, start, end).filter { it.value in 25..150 }
                .groupBy { day(it.timestamp) }.mapValues { it.value.last().value.toFloat() }
            CalendarMetric.STRESS -> db.stress(start, end).filter { it.stress in 1..100 }
                .groupBy { day(it.timestamp) }.mapValues { (_, v) -> v.map { it.stress }.average().toFloat() }
        }
    }

    /** [Review.stats] of the days [from]..[to]. */
    fun stats(db: HealthDb, profile: UserProfile, from: LocalDate, to: LocalDate): Review.Stats {
        val start = from.atStartOfDay(zone).toEpochSecond()
        val end = to.plusDays(1).atStartOfDay(zone).toEpochSecond()
        val nights = Queries.nightSummaries(db, from, to, zone)
        val totals = Queries.dayTotals(db, from, to, zone, zoneLower(profile))
        val resting = db.values(HealthDb.RESTING_HR, start, end).filter { it.value in 25..150 }.groupBy { day(it.timestamp) }
        val hrv = Queries.hrvDaily(db, from, to, zone)
        val stress = db.stress(start, end).filter { it.stress in 1..100 }.groupBy { day(it.timestamp) }
        val days = ArrayList<Review.Day>()
        var d = from
        while (!d.isAfter(to)) {
            val n = nights[d]
            val t = totals[d]
            days += Review.Day(
                date = d,
                sleepMinutes = n?.asleepMinutes,
                sleepScore = n?.score,
                bedtime = n?.let { Review.bedtimeOf(minuteOfDay(it.start)) },
                steps = t?.steps,
                activeMinutes = t?.activeMinutes,
                restingHr = resting[d]?.last()?.value,
                hrv = hrv[d],
                stress = stress[d]?.map { it.stress }?.average()?.toInt(),
            )
            d = d.plusDays(1)
        }
        val minutes = workouts(db, from, to).map { ((it.end - it.start) / 60).toInt() }
        val tags = db.events(start, end).map { it.tag }.filter { !it.startsWith(StressPrompt.PREFIX) }
        return Review.stats(days, minutes, tags)
    }

    fun review(db: HealthDb, profile: UserProfile, span: ReviewSpan, date: LocalDate): ReviewData {
        val (from, to) = span.range(date)
        val (beforeFrom, beforeTo) = span.range(span.shift(date, -1))
        val until = minOf(to, LocalDate.now())
        return ReviewData(from, to, stats(db, profile, from, until), stats(db, profile, beforeFrom, beforeTo))
    }

    /** Daily values for the report charts. */
    fun daily(samples: List<ValueSample>, scale: Double = 1.0): Map<LocalDate, Double> =
        samples.groupBy { day(it.timestamp) }.mapValues { (_, v) -> v.map { it.value * scale }.average() }
}

/** Week, month or calendar year for the review. */
internal enum class ReviewSpan(val label: String) {
    WEEK("Woche"), MONTH("Monat"), YEAR("Jahr");

    fun range(date: LocalDate): Pair<LocalDate, LocalDate> = when (this) {
        WEEK -> Goals.weekStart(date).let { it to it.plusDays(6) }
        MONTH -> date.withDayOfMonth(1).let { it to it.plusMonths(1).minusDays(1) }
        YEAR -> date.withDayOfYear(1).let { it to it.plusYears(1).minusDays(1) }
    }

    fun shift(date: LocalDate, by: Long): LocalDate = when (this) {
        WEEK -> date.plusWeeks(by)
        MONTH -> date.plusMonths(by)
        YEAR -> date.plusYears(by)
    }

    /** "Vorwoche", "Vormonat", "Vorjahr". */
    val before: String
        get() = when (this) {
            WEEK -> "Vorwoche"
            MONTH -> "Vormonat"
            YEAR -> "Vorjahr"
        }
}
