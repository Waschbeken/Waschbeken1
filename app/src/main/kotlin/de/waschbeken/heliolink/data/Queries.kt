package de.waschbeken.heliolink.data

import de.waschbeken.heliolink.core.ActivitySample
import de.waschbeken.heliolink.core.SleepAnalyzer
import de.waschbeken.heliolink.core.SleepEditor
import de.waschbeken.heliolink.core.SleepSession
import de.waschbeken.heliolink.core.ValueSample
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Shared read helpers for the screens. Run them off the main thread. */
object Queries {
    /** A night belongs to the morning it ended: from 18:00 the evening before until 18:00. */
    fun nightWindow(date: LocalDate, zone: ZoneId): Pair<Long, Long> =
        date.minusDays(1).atTime(18, 0).atZone(zone).toEpochSecond() to date.atTime(18, 0).atZone(zone).toEpochSecond()

    fun dayWindow(date: LocalDate, zone: ZoneId): Pair<Long, Long> =
        date.atStartOfDay(zone).toEpochSecond() to date.plusDays(1).atStartOfDay(zone).toEpochSecond()

    /** All nights between [from] and [to] (dates of the mornings). The strap's own records win, corrections by hand win over both. */
    fun nights(db: HealthDb, from: LocalDate, to: LocalDate, zone: ZoneId, applyEdits: Boolean = true): Map<LocalDate, SleepSession> {
        val prefs = db.prefs
        val tuning = prefs.sleepTuning
        val start = nightWindow(from, zone).first
        val end = nightWindow(to, zone).second
        val samples = db.activity(start, end)
        val fromMinutes = SleepAnalyzer.sessions(samples, tuning.maxGapMinutes, tuning.minSleepMinutes)
        val sessions = if (prefs.sleepAlwaysEstimate) fromMinutes else {
            SleepAnalyzer.combine(db.sleepRecords(start, end).map { it.toSession(samples) }, fromMinutes)
        }
        val edits = if (applyEdits) db.sleepEdits(from.toEpochDay(), to.toEpochDay()) else emptyMap()
        val out = LinkedHashMap<LocalDate, SleepSession>()
        var d = from
        while (!d.isAfter(to)) {
            var night = SleepAnalyzer.nightOf(d, sessions, zone)?.let { SleepEditor.ignoreShortAwake(it, tuning.ignoreAwakeMinutes) }
            val edit = edits[d.toEpochDay()]
            if (edit != null) night = SleepEditor.apply(night, edit, samples)
            night?.let { out[d] = it }
            d = d.plusDays(1)
        }
        return out
    }

    fun night(db: HealthDb, date: LocalDate, zone: ZoneId, applyEdits: Boolean = true): SleepSession? =
        nights(db, date, date, zone, applyEdits)[date]

    /** Average per day of a value table, null for days without values. */
    fun dailyAverage(samples: List<ValueSample>, from: LocalDate, to: LocalDate, zone: ZoneId, scale: Double = 1.0): List<Float?> {
        val byDay = samples.groupBy { Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { d ->
            byDay[d]?.map { it.value }?.average()?.let { (it * scale).toFloat() }
        }.toList()
    }

    /** Values during a sleep session. */
    fun during(samples: List<ValueSample>, session: SleepSession?): List<ValueSample> =
        if (session == null) emptyList() else samples.filter { it.timestamp in session.start until session.end }

    /** Minute samples plus the heart rates the app recorded itself, merged into one list of points. */
    fun heartRatePoints(db: HealthDb, from: Long, to: Long, samples: List<ActivitySample>): List<Pair<Long, Float>> {
        val recorded = db.hr(from, to)
        val minutes = recorded.map { it.timestamp / 60 }.toHashSet()
        return (samples.filter { it.heartRate > 0 && it.timestamp / 60 !in minutes }.map { it.timestamp to it.heartRate.toFloat() } +
            recorded.map { it.timestamp to it.bpm.toFloat() }).sortedBy { it.first }
    }
}
