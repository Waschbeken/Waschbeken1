package de.waschbeken.heliolink.core

/** Interval training: [rounds] times [workSeconds] effort, with [restSeconds] rest in between. */
data class IntervalPlan(val workSeconds: Int, val restSeconds: Int, val rounds: Int) {
    val totalSeconds: Int get() = rounds * workSeconds + (rounds - 1).coerceAtLeast(0) * restSeconds

    data class Phase(val work: Boolean, val round: Int, val remaining: Int)

    /** The phase [elapsed] seconds after the start; null before the start and once all rounds are done. */
    fun phaseAt(elapsed: Long): Phase? {
        if (elapsed < 0 || elapsed >= totalSeconds) return null
        val cycle = workSeconds + restSeconds
        val round = (elapsed / cycle).toInt()
        val inCycle = (elapsed % cycle).toInt()
        return if (inCycle < workSeconds) Phase(true, round + 1, workSeconds - inCycle) else Phase(false, round + 1, cycle - inCycle)
    }

    /** E.g. "30 s / 30 s × 10" or "4 min / 3 min × 4". */
    val label: String get() = "${time(workSeconds)} / ${time(restSeconds)} × $rounds"

    /** Text form for the settings, e.g. "30,30,10". */
    fun encode() = "$workSeconds,$restSeconds,$rounds"

    companion object {
        fun decode(text: String?): IntervalPlan? {
            val p = text?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
            if (p.size != 3) return null
            return of(p[0], p[1], p[2])
        }

        /** A valid plan or null: 5 s – 30 min effort, 0 – 30 min rest, 1 – 50 rounds. */
        fun of(work: Int, rest: Int, rounds: Int): IntervalPlan? =
            if (work in 5..1800 && rest in 0..1800 && rounds in 1..50) IntervalPlan(work, rest, rounds) else null

        private fun time(s: Int) = when {
            s % 60 == 0 && s >= 60 -> "${s / 60} min"
            s > 60 -> "${s / 60}:%02d min".format(s % 60)
            else -> "$s s"
        }

        val PRESETS = listOf(IntervalPlan(30, 30, 10), IntervalPlan(40, 20, 8), IntervalPlan(60, 60, 8), IntervalPlan(240, 180, 4))
    }
}
