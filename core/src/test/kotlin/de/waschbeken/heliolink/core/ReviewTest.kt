package de.waschbeken.heliolink.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReviewTest {
    private val d = LocalDate.of(2026, 10, 1)

    @Test
    fun averagesSkipDaysWithoutData() {
        val days = listOf(
            Review.Day(d, sleepMinutes = 420, sleepScore = 80, bedtime = -30, steps = 8000, activeMinutes = 40, restingHr = 55),
            Review.Day(d.plusDays(1), sleepMinutes = 480, sleepScore = 90, bedtime = 30, steps = 12000, activeMinutes = 60, restingHr = 57),
            Review.Day(d.plusDays(2)), // not worn
        )
        val s = Review.stats(days, listOf(30, 45), listOf("Koffein", "Nikotin", "Koffein"))
        assertEquals(3, s.days)
        assertEquals(2, s.nights)
        assertEquals(450, s.sleepMinutes)
        assertEquals(85, s.sleepScore)
        assertEquals(0, s.bedtime)
        assertEquals(10000, s.stepsPerDay)
        assertEquals(20000L, s.stepsTotal)
        assertEquals(56, s.restingHr)
        assertEquals(2, s.workouts)
        assertEquals(75, s.workoutMinutes)
        assertEquals(d.plusDays(1) to 90, s.bestNight)
        assertEquals(d.plusDays(1) to 12000, s.mostSteps)
        assertEquals(listOf("Koffein" to 2, "Nikotin" to 1), s.tags)
    }

    @Test
    fun emptyPeriod() {
        val s = Review.stats(emptyList(), emptyList(), emptyList())
        assertNull(s.sleepMinutes)
        assertNull(s.bestNight)
        assertEquals(0, s.workouts)
    }

    @Test
    fun changesAndJudgement() {
        assertEquals(5, Review.change(60, 55))
        assertEquals(0, Review.change(60, 59, minimum = 2))
        assertNull(Review.change(null, 55))
        assertEquals(false, Review.judge(5, Review.Direction.LOWER_IS_BETTER))
        assertEquals(true, Review.judge(5, Review.Direction.HIGHER_IS_BETTER))
        assertNull(Review.judge(0, Review.Direction.HIGHER_IS_BETTER))
        assertEquals(-30, Review.bedtimeOf(23 * 60 + 30))
        assertEquals(45, Review.bedtimeOf(45))
    }
}
