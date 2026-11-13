package de.waschbeken.heliolink.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SleepCyclesTest {
    /** One stage per minute from a string: W awake, L light, D deep, R REM. */
    private fun night(s: String) = s.mapIndexed { i, c ->
        i * 60L to when (c) {
            'W' -> SleepStage.AWAKE
            'D' -> SleepStage.DEEP
            'R' -> SleepStage.REM
            else -> SleepStage.LIGHT
        }
    }

    @Test
    fun eachRemPhaseClosesACycle() {
        val cycle = "L".repeat(30) + "D".repeat(40) + "L".repeat(10) + "R".repeat(10)
        assertEquals(3, SleepCycles.count(night(cycle.repeat(3))))
    }

    @Test
    fun shortBreaksInRemDoNotStartANewCycle() {
        // REM, a minute awake, REM again, then 10 minutes light and REM: still one phase
        val stages = night("LLLLL" + "RRRR" + "W" + "RRR" + "L".repeat(10) + "RR")
        assertEquals(1, SleepCycles.count(stages))
        val phase = SleepCycles.remPhases(stages).single()
        assertEquals(5 * 60L, phase.first)
        assertEquals(25 * 60L, phase.second)
    }

    @Test
    fun singleRemMinutesAreNoCycle() {
        assertEquals(0, SleepCycles.count(night("LLLLRLLLLL" + "L".repeat(30) + "RRL")))
        assertEquals(0, SleepCycles.count(emptyList()))
    }
}
