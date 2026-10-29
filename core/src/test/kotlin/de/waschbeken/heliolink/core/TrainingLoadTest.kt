package de.waschbeken.heliolink.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainingLoadTest {
    @Test
    fun everydayLifeDoesNotCount() {
        assertEquals(0.0, TrainingLoad.minute(85, 60, 190, true))
        assertTrue(TrainingLoad.minute(160, 60, 190, true) > 1.5)
    }

    @Test
    fun hardHourIsAboutHundred() {
        val load = TrainingLoad.of(List(60) { 150 }, 60, 190, true) // ~70 % of the reserve
        assertTrue(load in 80.0..130.0, "load=$load")
    }

    @Test
    fun findsSessionsAndRecovery() {
        val points = (0 until 60).map { it * 60L to 150 } + (0 until 30).map { 10_000 * 60L + it * 60L to 80 }
        val sessions = TrainingLoad.sessions(points, 60, 190, true)
        assertEquals(1, sessions.size)
        val s = sessions.single()
        assertEquals(3600L, s.end)
        val hours = TrainingLoad.recoveryHours(sessions, s.end + 6 * 3600)
        assertTrue(hours in 20.0..34.0, "hours=$hours")
        assertEquals(0.0, TrainingLoad.recoveryHours(sessions, s.end + 80 * 3600))
    }

    @Test
    fun balanceOfSevenAgainstTwentyEightDays() {
        val today = LocalDate.of(2026, 10, 29)
        val usual = (0L..27L).associate { today.minusDays(it) to 30.0 }
        assertEquals(TrainingLoad.Status.OPTIMAL, TrainingLoad.balance(usual, today)!!.status)
        val spike = usual + (0L..6L).associate { today.minusDays(it) to 80.0 }
        assertEquals(TrainingLoad.Status.OVERLOAD, TrainingLoad.balance(spike, today)!!.status)
        assertNull(TrainingLoad.balance(usual.filterKeys { it > today.minusDays(5) }, today))
    }

    @Test
    fun vo2Estimate() {
        val v = Vo2Max.estimate(190, 55)
        assertNotNull(v)
        assertEquals(52.85, v, 0.1)
        assertEquals("hervorragend", Vo2Max.rating(v, 25, true))
        assertEquals("gut", Vo2Max.rating(44.0, 25, true))
        assertNull(Vo2Max.estimate(190, 0))
    }

    @Test
    fun recoveryRating() {
        assertEquals("gut", HeartRateRecovery.rating(25))
        assertEquals("schwach", HeartRateRecovery.rating(8))
    }
}

class IntervalPlanTest {
    private val plan = IntervalPlan(30, 15, 3)

    @Test
    fun phases() {
        assertEquals(30 * 3 + 15 * 2, plan.totalSeconds)
        assertEquals(IntervalPlan.Phase(true, 1, 30), plan.phaseAt(0))
        assertEquals(IntervalPlan.Phase(false, 1, 15), plan.phaseAt(30))
        assertEquals(IntervalPlan.Phase(true, 2, 30), plan.phaseAt(45))
        assertEquals(IntervalPlan.Phase(true, 3, 1), plan.phaseAt(119))
        assertNull(plan.phaseAt(120))
    }

    @Test
    fun encodesAndLabels() {
        assertEquals(plan, IntervalPlan.decode(plan.encode()))
        assertEquals("4 min / 3 min × 4", IntervalPlan(240, 180, 4).label)
        assertNull(IntervalPlan.decode("1,2"))
        assertNull(IntervalPlan.of(2, 10, 3))
    }
}
