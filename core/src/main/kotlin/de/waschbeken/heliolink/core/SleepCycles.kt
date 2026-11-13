package de.waschbeken.heliolink.core

/**
 * Sleep runs in cycles of about 90 minutes: from light into deep sleep and back up, ending in a
 * REM phase. Each REM phase therefore closes a cycle; short interruptions of a REM phase (a minute
 * awake or light) do not start a new one.
 */
object SleepCycles {
    /** REM phases as (start, end) epoch seconds: REM minutes less than [maxGap] seconds apart joined, at least [minMinutes] of REM each. */
    fun remPhases(stages: List<Pair<Long, SleepStage>>, maxGap: Long = 20 * 60, minMinutes: Int = 3): List<Pair<Long, Long>> {
        val rem = stages.filter { it.second == SleepStage.REM }.map { it.first }.sorted()
        val out = ArrayList<Triple<Long, Long, Int>>() // start, end, REM minutes
        for (t in rem) {
            val last = out.lastOrNull()
            if (last != null && t - last.second <= maxGap) out[out.lastIndex] = Triple(last.first, t + 60, last.third + 1)
            else out += Triple(t, t + 60, 1)
        }
        return out.filter { it.third >= minMinutes }.map { it.first to it.second }
    }

    /** Completed cycles of the night. */
    fun count(stages: List<Pair<Long, SleepStage>>): Int = remPhases(stages).size
}
