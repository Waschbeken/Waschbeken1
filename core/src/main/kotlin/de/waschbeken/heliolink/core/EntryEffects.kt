package de.waschbeken.heliolink.core

import kotlin.math.roundToInt

/**
 * What a quick entry (caffeine, nicotine, alcohol …) does to the heart rate: the resting heart rate
 * in the two hours after each entry, compared with the same time of day on other days without that
 * entry. Only quiet minutes count (worn, awake, hardly any steps), so a walk after the coffee does
 * not show up as "coffee raises the pulse".
 */
object EntryEffects {
    data class Effect(
        val tag: String,
        /** Entries that could be compared. */
        val count: Int,
        /** Average difference in the first hour (bpm). */
        val firstHour: Double,
        /** Largest difference of the average curve (bpm) and when it happens (minutes after). */
        val peak: Double,
        val peakMinute: Int,
        /** Minutes until the curve stays below +2 bpm again; null if not within the window. */
        val lasts: Int?,
        /** Difference per [BIN]-minute step after the entry. */
        val curve: List<Double?>,
    )

    const val BIN = 10

    /**
     * [events] are (epoch seconds, tag), [samples] the minute samples of the whole period. Tags with
     * fewer than [minEvents] comparable entries are left out.
     */
    fun compute(
        events: List<Pair<Long, String>>,
        samples: List<ActivitySample>,
        windowMinutes: Int = 120,
        referenceDays: Int = 14,
        minEvents: Int = 3,
    ): List<Effect> {
        val quiet = HashMap<Long, Int>(samples.size)
        for (s in samples) {
            if (s.heartRate in 30..220 && !s.isSleep && !s.notWorn && s.steps < 15) quiet[s.timestamp / 60] = s.heartRate
        }
        val bins = windowMinutes / BIN
        fun binMeans(start: Long): Array<Double?> {
            val minute = start / 60
            return Array(bins) { b ->
                var sum = 0
                var n = 0
                for (m in 0 until BIN) {
                    val hr = quiet[minute + b * BIN + m] ?: continue
                    sum += hr
                    n++
                }
                if (n >= 3) sum.toDouble() / n else null
            }
        }
        val out = ArrayList<Effect>()
        for ((tag, list) in events.groupBy({ it.second }, { it.first })) {
            val times = list.sorted()
            val diffs = ArrayList<Array<Double?>>()
            for (t in times) {
                val after = binMeans(t)
                if (after.count { it != null } < bins / 3) continue
                // Same clock time on other days, but not where the same entry happened around then.
                val refs = (1..referenceDays).flatMap { listOf(t - it * 86_400L, t + it * 86_400L) }
                    .filter { r -> times.none { it in (r - 3 * 3600)..(r + windowMinutes * 60) } }
                    .map { binMeans(it) }
                val diff = Array<Double?>(bins) { b ->
                    val a = after[b] ?: return@Array null
                    val r = refs.mapNotNull { it[b] }
                    if (r.size < 3) null else a - BodyCheck.median(r)
                }
                if (diff.count { it != null } >= bins / 3) diffs += diff
            }
            if (diffs.size < minEvents) continue
            val curve = List(bins) { b ->
                val v = diffs.mapNotNull { it[b] }
                if (v.size >= (minEvents + 1) / 2) v.average() else null
            }
            val firstHour = curve.take(60 / BIN).filterNotNull()
            if (firstHour.isEmpty()) continue
            val peakIndex = curve.indices.filter { curve[it] != null }.maxBy { kotlin.math.abs(curve[it]!!) }
            // How long it lasts: the end of the last step that is still clearly raised.
            val lastHigh = curve.indices.lastOrNull { (curve[it] ?: 0.0) > 2.0 }
            val lasts = when {
                lastHigh == null -> 0
                lastHigh == bins - 1 -> null
                else -> (lastHigh + 1) * BIN
            }
            out += Effect(
                tag = tag,
                count = diffs.size,
                firstHour = firstHour.average(),
                peak = curve[peakIndex]!!,
                peakMinute = peakIndex * BIN + BIN / 2,
                lasts = lasts,
                curve = curve,
            )
        }
        return out.sortedByDescending { kotlin.math.abs(it.firstHour) }
    }

    /** Short text, e.g. "+8 bpm, am stärksten nach 25 min, etwa 70 min lang". */
    fun describe(e: Effect): String {
        val sign = if (e.firstHour >= 0) "+" else "−"
        val main = "$sign${kotlin.math.abs(e.firstHour).roundToInt()} bpm in der ersten Stunde"
        if (kotlin.math.abs(e.peak) < 2) return "$main – kaum Unterschied"
        val peak = "am stärksten nach etwa ${e.peakMinute} min (${if (e.peak >= 0) "+" else "−"}${kotlin.math.abs(e.peak).roundToInt()})"
        val lasts = when (e.lasts) {
            null -> "länger als 2 h erhöht"
            0 -> null
            else -> "etwa ${e.lasts} min erhöht"
        }
        return listOfNotNull(main, peak, lasts).joinToString(", ")
    }
}
