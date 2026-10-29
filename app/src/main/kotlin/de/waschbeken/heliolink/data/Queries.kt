package de.waschbeken.heliolink.data

import de.waschbeken.heliolink.core.ActivitySample
import de.waschbeken.heliolink.core.BodyCheck
import de.waschbeken.heliolink.core.NightVitals
import de.waschbeken.heliolink.core.SleepAnalyzer
import de.waschbeken.heliolink.core.SleepEditor
import de.waschbeken.heliolink.core.SleepSession
import de.waschbeken.heliolink.core.SleepStage
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
            var night = SleepAnalyzer.nightOf(d, sessions, zone)
                ?.let { SleepEditor.trim(it, tuning) }
                ?.let { SleepEditor.ignoreShortAwake(it, tuning.ignoreAwakeMinutes) }
            val edit = edits[d.toEpochDay()]
            if (edit != null) night = SleepEditor.apply(night, edit, samples)
            night?.let { out[d] = it.copy(goalMinutes = prefs.profile.sleepGoalMinutes) }
            d = d.plusDays(1)
        }
        return out
    }

    fun night(db: HealthDb, date: LocalDate, zone: ZoneId, applyEdits: Boolean = true): SleepSession? =
        nights(db, date, date, zone, applyEdits)[date]

    /**
     * What the user's corrections say: on average how many minutes later they fell asleep and how
     * many minutes earlier they woke up than detected. Null with fewer than two corrected nights.
     */
    fun correctionBias(db: HealthDb, zone: ZoneId): Pair<Int, Int>? {
        val today = LocalDate.now()
        val edits = db.sleepEdits(today.minusDays(90).toEpochDay(), today.toEpochDay()).values.filter { !it.deleted }
        if (edits.size < 2) return null
        val detected = nights(db, LocalDate.ofEpochDay(edits.minOf { it.night }), today, zone, applyEdits = false)
        val pairs = edits.mapNotNull { e -> detected[LocalDate.ofEpochDay(e.night)]?.let { e to it } }
        val onset = pairs.map { (e, d) -> (e.start - d.start) / 60 }.filter { kotlin.math.abs(it) < 180 }
        val wake = pairs.map { (e, d) -> (d.end - e.end) / 60 }.filter { kotlin.math.abs(it) < 180 }
        if (onset.size < 2) return null
        return kotlin.math.round(onset.average()).toInt() to (if (wake.isEmpty()) 0 else kotlin.math.round(wake.average()).toInt())
    }

    /** A night without its minutes, for long periods. */
    data class NightSummary(
        val date: LocalDate,
        val start: Long,
        val end: Long,
        val asleepMinutes: Int,
        val deep: Int,
        val light: Int,
        val rem: Int,
        val awake: Int,
        val score: Int,
        val edited: Boolean,
        val estimated: Boolean,
    ) {
        fun minutes(stage: SleepStage) = when (stage) {
            SleepStage.DEEP -> deep
            SleepStage.LIGHT -> light
            SleepStage.REM -> rem
            SleepStage.AWAKE -> awake
        }

        companion object {
            fun of(date: LocalDate, s: SleepSession) = NightSummary(
                date, s.start, s.end, s.asleepMinutes, s.minutes(SleepStage.DEEP), s.minutes(SleepStage.LIGHT),
                s.minutes(SleepStage.REM), s.minutes(SleepStage.AWAKE), s.score, s.edited, s.estimated,
            )
        }
    }

    /** Nights between [from] and [to], computed month by month so years fit into memory. */
    fun nightSummaries(db: HealthDb, from: LocalDate, to: LocalDate, zone: ZoneId): Map<LocalDate, NightSummary> {
        val out = LinkedHashMap<LocalDate, NightSummary>()
        var chunk = from
        while (!chunk.isAfter(to)) {
            val chunkEnd = minOf(chunk.plusMonths(1).minusDays(1), to)
            for ((d, s) in nights(db, chunk, chunkEnd, zone)) out[d] = NightSummary.of(d, s)
            chunk = chunkEnd.plusDays(1)
        }
        return out
    }

    /** One day of [HealthDb.hourTotals]. */
    class DayTotals(
        val date: LocalDate,
        val steps: Int,
        val hrMin: Int?,
        val hrAvg: Int?,
        val hrMax: Int?,
        val activeMinutes: Int,
        val zones: IntArray,
    ) {
        /** Minutes in zone 2 and higher. */
        val intenseMinutes get() = zones.drop(1).sum()
    }

    /** Per-day totals from the hourly sums (fast even for years). */
    fun dayTotals(db: HealthDb, from: LocalDate, to: LocalDate, zone: ZoneId, zoneLower: IntArray): Map<LocalDate, DayTotals> {
        val hours = db.hourTotals(from.atStartOfDay(zone).toEpochSecond(), to.plusDays(1).atStartOfDay(zone).toEpochSecond(), zoneLower)
        return hours.groupBy { Instant.ofEpochSecond(it.hour).atZone(zone).toLocalDate() }.mapValues { (date, list) ->
            val count = list.sumOf { it.hrCount }
            val withHr = list.filter { it.hrCount > 0 }
            DayTotals(
                date = date,
                steps = list.sumOf { it.steps },
                hrMin = withHr.minOfOrNull { it.hrMin },
                hrAvg = if (count == 0) null else (list.sumOf { it.hrSum } / count).toInt(),
                hrMax = withHr.maxOfOrNull { it.hrMax },
                activeMinutes = list.sumOf { it.activeMinutes },
                zones = IntArray(5) { i -> list.sumOf { it.zones[i] } },
            )
        }
    }

    /** Naps on [date] between 09:00 and 21:00 that are not part of the main night. */
    fun naps(db: HealthDb, date: LocalDate, zone: ZoneId, mainNight: SleepSession?): List<SleepSession> {
        val from = date.atTime(9, 0).atZone(zone).toEpochSecond()
        val to = date.atTime(21, 0).atZone(zone).toEpochSecond()
        val tuning = db.prefs.sleepTuning
        val samples = db.activity(from, to)
        // A pulse far above rest is not sleep, even when the strap was lying still.
        val resting = db.values(HealthDb.RESTING_HR, from - 7 * 86_400L, to).lastOrNull()?.value ?: 60
        return SleepAnalyzer.sessions(samples, maxGapMinutes = 15, minMinutes = 15)
            .filter { s -> mainNight == null || s.end <= mainNight.start || s.start >= mainNight.end }
            .filter { it.asleepMinutes < 4 * 60 && it.asleepMinutes >= tuning.minSleepMinutes.coerceAtMost(15) }
            .filter { s ->
                val hr = samples.filter { it.timestamp in s.start until s.end && it.heartRate > 0 }.map { it.heartRate }
                hr.isEmpty() || hr.average() < maxOf(resting + 20, 80)
            }
    }

    /** The night (date of its morning) an entry at [ts] belongs to: before 4 am the same morning, otherwise the next. */
    fun nightOfEvent(ts: Long, zone: ZoneId): LocalDate {
        val local = Instant.ofEpochSecond(ts).atZone(zone)
        return if (local.hour < 4) local.toLocalDate() else local.toLocalDate().plusDays(1)
    }

    /** Quick entries that count for the night ending on [date]. */
    fun eventsForNight(db: HealthDb, date: LocalDate, zone: ZoneId): List<HealthDb.Event> =
        db.events(date.minusDays(1).atTime(4, 0).atZone(zone).toEpochSecond(), date.atTime(4, 0).atZone(zone).toEpochSecond())

    /** Diary tags offered on the sleep page. */
    val SLEEP_TAGS = listOf("Sport", "Koffein", "Nikotin", "Alkohol", "Stress", "Spät gegessen", "Bildschirm im Bett", "Krank", "Nickerchen", "Lesen", "Spät ins Bett")

    /** How a diary tag goes along with the sleep score: average with and without it. */
    data class TagEffect(val tag: String, val nightsWith: Int, val scoreWith: Int, val scoreWithout: Int, val minutesWith: Int, val minutesWithout: Int)

    fun tagEffects(db: HealthDb, zone: ZoneId, days: Long = 90): List<TagEffect> {
        val today = LocalDate.now()
        val from = today.minusDays(days)
        val tags = HashMap<Long, MutableSet<String>>()
        db.sleepTags(from.toEpochDay(), today.toEpochDay()).forEach { (day, set) -> tags.getOrPut(day) { HashSet() } += set }
        // quick entries count for the night that follows them
        db.events(from.minusDays(1).atStartOfDay(zone).toEpochSecond(), today.plusDays(1).atStartOfDay(zone).toEpochSecond()).forEach { e ->
            tags.getOrPut(nightOfEvent(e.timestamp, zone).toEpochDay()) { HashSet() } += e.tag
        }
        if (tags.isEmpty()) return emptyList()
        val nights = nights(db, from, today, zone)
        val out = ArrayList<TagEffect>()
        for (tag in tags.values.flatten().toSet()) {
            val with = nights.filter { (d, _) -> tag in tags[d.toEpochDay()].orEmpty() }.values
            val without = nights.filter { (d, _) -> tag !in tags[d.toEpochDay()].orEmpty() }.values
            if (with.size < 2 || without.size < 2) continue
            out += TagEffect(
                tag, with.size,
                with.map { it.score }.average().toInt(), without.map { it.score }.average().toInt(),
                with.map { it.asleepMinutes }.average().toInt(), without.map { it.asleepMinutes }.average().toInt(),
            )
        }
        return out.sortedByDescending { kotlin.math.abs(it.scoreWith - it.scoreWithout) }
    }

    /** Average per day of a value table, null for days without values. */
    fun dailyAverage(samples: List<ValueSample>, from: LocalDate, to: LocalDate, zone: ZoneId, scale: Double = 1.0): List<Float?> {
        val byDay = samples.groupBy { Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { d ->
            byDay[d]?.map { it.value }?.average()?.let { (it * scale).toFloat() }
        }.toList()
    }

    /**
     * Night values for the body check, one entry per morning from [from] to [to]: the strap's resting
     * heart rate of the day and HRV, skin temperature and breathing during the night.
     */
    fun nightVitals(db: HealthDb, from: LocalDate, to: LocalDate, zone: ZoneId): List<NightVitals> {
        val start = nightWindow(from, zone).first
        val end = nightWindow(to, zone).second
        val nights = nights(db, from, to, zone)
        val resting = db.values(HealthDb.RESTING_HR, start, end)
            .groupBy { Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }
        val hrv = db.values(HealthDb.HRV, start, end)
        val temp = db.values(HealthDb.TEMPERATURE, start, end)
        val breath = db.values(HealthDb.RESPIRATION, start, end)
        val out = ArrayList<NightVitals>()
        var d = from
        while (!d.isAfter(to)) {
            val night = nights[d]
            val v = NightVitals(
                date = d,
                restingHr = resting[d]?.lastOrNull()?.value?.takeIf { it in 30..120 }?.toDouble(),
                hrv = hrvOfDay(hrv, night, d, zone),
                temperature = during(temp, night).map { it.value / 100.0 }.filter { it in 25.0..42.0 }
                    .takeIf { it.size >= 3 }?.let { BodyCheck.median(it) },
                breath = during(breath, night).map { it.value.toDouble() }.filter { it in 5.0..40.0 }.takeIf { it.isNotEmpty() }?.average(),
            )
            if (v.restingHr != null || v.hrv != null || v.temperature != null || v.breath != null) out += v
            d = d.plusDays(1)
        }
        return out
    }

    /**
     * HRV of the night ending on [date]; without night values the morning measurement (04–11 h)
     * stands in, because the strap does not hand out its night HRV.
     */
    private fun hrvOfDay(hrv: List<ValueSample>, night: SleepSession?, date: LocalDate, zone: ZoneId): Double? {
        during(hrv, night).map { it.value.toDouble() }.filter { it in 5.0..250.0 }.takeIf { it.size >= 3 }?.let { return it.average() }
        val from = date.atTime(4, 0).atZone(zone).toEpochSecond()
        val to = date.atTime(11, 0).atZone(zone).toEpochSecond()
        return hrv.filter { it.timestamp in from until to }.map { it.value.toDouble() }.filter { it in 5.0..250.0 }
            .takeIf { it.isNotEmpty() }?.average()
    }

    /** HRV per morning (23:00 the evening before until 11:00), for the HRV status. */
    fun hrvDaily(db: HealthDb, from: LocalDate, to: LocalDate, zone: ZoneId): Map<LocalDate, Double> {
        val values = db.values(HealthDb.HRV, from.minusDays(1).atTime(23, 0).atZone(zone).toEpochSecond(), to.atTime(11, 0).atZone(zone).toEpochSecond())
        // shifted by an hour, so 23:00–11:00 becomes 00:00–12:00 of the morning's date
        return values.filter { it.value in 5..250 }.mapNotNull { v ->
            val t = Instant.ofEpochSecond(v.timestamp + 3600).atZone(zone)
            if (t.hour < 12) t.toLocalDate() to v.value.toDouble() else null
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.average() }
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
