package de.waschbeken.heliolink.core

import kotlin.math.roundToInt

/** A stage the user set by hand for the minutes [from] until [to] (epoch seconds, exclusive). */
data class StageRange(val from: Long, val to: Long, val stage: SleepStage)

/**
 * A manual correction of one night (identified by the epoch day of the morning, [night]).
 * [start]/[end] replace the detected times, [ranges] override stages (later ones win),
 * [deleted] means "this was no sleep".
 */
data class SleepEdit(
    val night: Long,
    val start: Long,
    val end: Long,
    val ranges: List<StageRange> = emptyList(),
    val deleted: Boolean = false,
) {
    /** Compact text form for the database: "from:to:STAGE;…". */
    fun rangesText(): String = ranges.joinToString(";") { "${it.from}:${it.to}:${it.stage.name}" }

    companion object {
        fun parseRanges(text: String?): List<StageRange> = text.orEmpty().split(';').mapNotNull { part ->
            val p = part.split(':')
            if (p.size != 3) return@mapNotNull null
            val from = p[0].toLongOrNull() ?: return@mapNotNull null
            val to = p[1].toLongOrNull() ?: return@mapNotNull null
            val stage = SleepStage.entries.firstOrNull { it.name == p[2] } ?: return@mapNotNull null
            if (to > from) StageRange(from, to, stage) else null
        }
    }
}

/** Settings that tune how nights are built from the minute values. */
data class SleepTuning(
    /** Awake periods shorter than this (minutes) count as light sleep. */
    val ignoreAwakeMinutes: Int = 0,
    /** Awake gaps up to this length stay inside one night. */
    val maxGapMinutes: Int = 45,
    /** Shorter sleep is not shown as a night. */
    val minSleepMinutes: Int = 20,
    /** The strap often counts lying still as sleep: start the night this many minutes later. */
    val onsetDelayMinutes: Int = 0,
    /** End the night this many minutes earlier (e.g. lying in bed after waking up). */
    val wakeEarlierMinutes: Int = 0,
)

object SleepEditor {
    /**
     * Applies [edit] to the detected [session] (may be null for a night the user added). Minutes
     * outside the detected night take their stage from the [samples] (estimated), otherwise LIGHT.
     */
    fun apply(session: SleepSession?, edit: SleepEdit, samples: List<ActivitySample>): SleepSession? {
        if (edit.deleted || edit.end <= edit.start) return null
        val start = edit.start / 60 * 60
        val end = edit.end / 60 * 60
        val base = HashMap<Long, SleepStage>()
        session?.stages?.forEach { (t, s) -> base[t / 60 * 60] = s }
        val window = samples.filter { it.timestamp in start until end }.sortedBy { it.timestamp }
        val missing = (start until end step 60).any { it !in base }
        if (missing && window.isNotEmpty()) {
            val estimated = window.map { it.timestamp / 60 * 60 }.zip(SleepStager.stages(window))
            for ((t, s) in estimated) base.putIfAbsent(t, s)
        }
        val stages = ArrayList<Pair<Long, SleepStage>>()
        var t = start
        while (t < end) {
            var stage = base[t] ?: SleepStage.LIGHT
            for (r in edit.ranges) if (t >= r.from && t < r.to) stage = r.stage
            stages += t to stage
            t += 60
        }
        val hr = window.map { it.heartRate }.filter { it > 0 }
        val unchanged = session != null && edit.ranges.isEmpty() && start == session.start / 60 * 60 && end == session.end / 60 * 60
        return SleepSession(
            start = start,
            end = end,
            stages = stages,
            avgHeartRate = if (hr.isEmpty()) session?.avgHeartRate else hr.average().roundToInt(),
            minHeartRate = hr.minOrNull() ?: session?.minHeartRate,
            strapScore = if (unchanged) session?.strapScore else null,
            estimated = session?.estimated == true && edit.ranges.isEmpty(),
            edited = true,
        )
    }

    /** Awake runs shorter than [minutes] become light sleep (the strap is jumpy about short wakings). */
    fun ignoreShortAwake(session: SleepSession, minutes: Int): SleepSession {
        if (minutes <= 0 || session.stages.isEmpty()) return session
        val out = session.stages.toMutableList()
        var i = 0
        while (i < out.size) {
            if (out[i].second != SleepStage.AWAKE) { i++; continue }
            var j = i
            while (j < out.size && out[j].second == SleepStage.AWAKE) j++
            // Waking at the very start or end of the night is real.
            if (j - i < minutes && i > 0 && j < out.size) for (k in i until j) out[k] = out[k].first to SleepStage.LIGHT
            i = j
        }
        return session.copy(stages = out)
    }

    /** Moves the start later and the end earlier by the tuning's minutes (keeps at least an hour). */
    fun trim(session: SleepSession, tuning: SleepTuning): SleepSession {
        val cutStart = tuning.onsetDelayMinutes.coerceAtLeast(0) * 60L
        val cutEnd = tuning.wakeEarlierMinutes.coerceAtLeast(0) * 60L
        if (cutStart == 0L && cutEnd == 0L) return session
        val start = session.start + cutStart
        val end = session.end - cutEnd
        if (end - start < 3600) return session
        return session.copy(start = start, end = end, stages = session.stages.filter { it.first >= start && it.first < end })
    }

    /** Consecutive minutes of the same stage as (from, to, stage). */
    fun segments(session: SleepSession): List<StageRange> {
        val out = ArrayList<StageRange>()
        for ((t, s) in session.stages.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && last.stage == s && last.to == t) out[out.lastIndex] = last.copy(to = t + 60)
            else out += StageRange(t, t + 60, s)
        }
        return out
    }
}
