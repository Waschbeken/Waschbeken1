package de.waschbeken.heliolink.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SleepRhythmTest {
    private val zone = ZoneId.of("Europe/Berlin")

    /** A night ending on [date], from [bed] (e.g. "23:30" or "00:40") to [wake]. */
    private fun night(date: LocalDate, bed: String, wake: String, score: Int = 80): SleepRhythm.Night {
        fun at(day: LocalDate, t: String) = day.atTime(t.substringBefore(':').toInt(), t.substringAfter(':').toInt()).atZone(zone).toEpochSecond()
        val bedDay = if (bed.substringBefore(':').toInt() >= 12) date.minusDays(1) else date
        return SleepRhythm.Night(date, at(bedDay, bed), at(date, wake), score)
    }

    // Monday 2 November 2026
    private val monday = LocalDate.of(2026, 11, 2)

    @Test
    fun midnightDoesNotBreakTheAverage() {
        // 23:40 and 00:20 average to midnight, not to noon
        val r = SleepRhythm.of(
            listOf(night(monday, "23:40", "07:00"), night(monday.plusDays(1), "00:20", "07:00"), night(monday.plusDays(2), "00:00", "07:00")),
            zone,
        )!!
        assertEquals("00:00", SleepRhythm.clock(r.bedtime))
        assertEquals(16, r.bedSpread)
        assertEquals("07:00", SleepRhythm.clock(r.wake))
        assertEquals(0, r.wakeSpread)
        assertEquals(SleepRhythm.Rating.VERY_REGULAR, r.rating)
    }

    @Test
    fun tooFewNights() {
        assertNull(SleepRhythm.of(listOf(night(monday, "23:00", "07:00"), night(monday.plusDays(1), "23:00", "07:00")), zone))
    }

    @Test
    fun weekendShiftFromTheMiddleOfTheNight() {
        // Monday to Friday 23:00–07:00 (middle 03:00), Saturday and Sunday 01:00–09:30 (middle 05:15)
        val nights = (0..6).map { i ->
            val d = monday.plusDays(i.toLong())
            if (i >= 5) night(d, "01:00", "09:30") else night(d, "23:00", "07:00")
        }
        val r = SleepRhythm.of(nights, zone)!!
        assertEquals(135, r.weekendShift)
        // without two free nights there is nothing to compare
        assertNull(SleepRhythm.of(nights.take(6), zone)!!.weekendShift)
    }

    @Test
    fun ownScoresNearAndFarFromTheUsualTime() {
        val nights = listOf(
            night(monday, "23:00", "07:00", 85), night(monday.plusDays(1), "23:10", "07:00", 83), night(monday.plusDays(2), "22:50", "07:00", 87),
            night(monday.plusDays(3), "23:05", "07:00", 84), night(monday.plusDays(4), "01:30", "08:00", 70),
            night(monday.plusDays(5), "01:40", "09:00", 66), night(monday.plusDays(6), "01:20", "08:30", 71),
        )
        val r = SleepRhythm.of(nights, zone)!!
        assertEquals("00:05", SleepRhythm.clock(r.bedtime))
        // the average lies between both groups: every night is an hour off it, none within 30 min
        assertNull(r.scoreNear)

        val usual = (0 until 10).map { i -> night(monday.plusDays(i.toLong()), "23:00", "07:00", 85) }
        val late = (10 until 13).map { i -> night(monday.plusDays(i.toLong()), "01:00", "08:00", 70) }
        val r2 = SleepRhythm.of(usual + late, zone)!!
        assertEquals(85, r2.scoreNear)
        assertEquals(70, r2.scoreFar)
    }

    @Test
    fun ratingFollowsTheSpread() {
        assertEquals(SleepRhythm.Rating.VERY_REGULAR, SleepRhythm.rating(20, 40))
        assertEquals(SleepRhythm.Rating.REGULAR, SleepRhythm.rating(40, 50))
        assertEquals(SleepRhythm.Rating.VARIABLE, SleepRhythm.rating(70, 60))
        assertEquals(SleepRhythm.Rating.IRREGULAR, SleepRhythm.rating(90, 80))
    }

    @Test
    fun clockWrapsAroundMidnight() {
        assertEquals("12:00", SleepRhythm.clock(0))
        assertEquals("23:30", SleepRhythm.clock(690))
        assertEquals("00:30", SleepRhythm.clock(750))
        assertEquals("07:15", SleepRhythm.clock(1155))
        assertEquals("11:00", SleepRhythm.clock(-60))
    }
}
