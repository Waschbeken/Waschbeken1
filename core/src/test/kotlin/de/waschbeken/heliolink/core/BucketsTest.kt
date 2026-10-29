package de.waschbeken.heliolink.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class BucketsTest {
    @Test
    fun weeksStartOnMondayAndAreCut() {
        // 2026-10-01 is a Thursday
        val b = Buckets.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), Granularity.WEEK)
        assertEquals(LocalDate.of(2026, 10, 1), b.first().from)
        assertEquals(LocalDate.of(2026, 10, 4), b.first().to)
        assertEquals(LocalDate.of(2026, 10, 26), b.last().from)
        assertEquals(LocalDate.of(2026, 10, 31), b.last().to)
        assertEquals(31, b.sumOf { it.days })
        assertEquals("Okt", b.first().label)
        assertEquals("", b[1].label)
    }

    @Test
    fun monthsLabelEveryMonthForAYearAndYearsForThree() {
        val year = Buckets.of(LocalDate.of(2025, 11, 1), LocalDate.of(2026, 10, 31), Granularity.MONTH)
        assertEquals(12, year.size)
        assertEquals("Nov", year.first().label)
        val three = Buckets.of(LocalDate.of(2023, 11, 1), LocalDate.of(2026, 10, 31), Granularity.MONTH)
        assertEquals(36, three.size)
        assertEquals("2024", three[2].label)
        assertEquals("Jul", three[8].label)
        assertEquals("", three[0].label)
    }

    @Test
    fun averageSkipsDaysWithoutValues() {
        val b = Buckets.of(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 8), Granularity.WEEK)
        val avg = Buckets.average(b) { d -> if (d.dayOfMonth == 27) 10.0 else if (d.dayOfMonth == 28) 20.0 else null }
        assertEquals(listOf(15f, null), avg)
    }
}
