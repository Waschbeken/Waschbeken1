package de.waschbeken.heliolink.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalsTest {
    private val today = LocalDate.of(2026, 10, 29) // Thursday

    @Test
    fun bedtimeAcrossMidnight() {
        val g = Goal(Goal.Kind.BEDTIME, 23 * 60 + 30, 5)
        assertEquals(true, g.met(DayFacts(today, sleepStart = 23 * 60)))
        assertEquals(false, g.met(DayFacts(today, sleepStart = 30)))
        assertEquals(true, g.met(DayFacts(today, sleepStart = 22 * 60)))
        assertNull(g.met(DayFacts(today)))
    }

    @Test
    fun weekAndStreak() {
        val g = Goal(Goal.Kind.STEPS, 8000, 3)
        val facts = (0L..30L).associate { back ->
            val d = today.minusDays(back)
            d to DayFacts(d, steps = if (d.dayOfWeek.value <= 3) 9000 else 4000) // Mon–Wed reached
        }
        val week = Goals.week(g, facts, today, today)
        assertEquals(3, week.done)
        assertTrue(week.reached)
        assertNull(week.days[4]) // Friday is still to come
        assertTrue(Goals.streak(g, facts, today) >= 3)
    }

    @Test
    fun encodesGoals() {
        val g = Goal(Goal.Kind.WITHOUT, 0, 7, "Nikotin")
        assertEquals(g, Goal.decode(g.encode()))
        assertEquals("Ohne Nikotin jeden Tag", g.title)
        assertEquals(true, g.met(DayFacts(today, tags = setOf("Koffein"))))
        assertEquals(false, g.met(DayFacts(today, tags = setOf("Nikotin"))))
    }

    @Test
    fun abstinence() {
        val r = Abstinence.of(listOf(today.minusDays(20), today.minusDays(5)), today.minusDays(40), today)
        assertEquals(5L, r.daysSince)
        assertEquals(20L, r.longest) // the 20 days before the first entry
        assertEquals(2, r.entries)
        val none = Abstinence.of(emptyList(), today.minusDays(10), today)
        assertNull(none.daysSince)
        assertEquals(11L, none.longest) // today-10 … today
    }
}
