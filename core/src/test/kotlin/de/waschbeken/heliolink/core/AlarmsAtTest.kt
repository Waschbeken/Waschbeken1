package de.waschbeken.heliolink.core

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class AlarmsAtTest {
    // Tuesday 17 November 2026, 06:45
    private val tuesday = ZonedDateTime.of(2026, 11, 17, 6, 45, 20, 0, ZoneId.of("Europe/Berlin"))

    @Test
    fun matchesTimeAndDay() {
        val weekdays = StrapAlarm(0, true, true, 6, 45, StrapAlarm.WEEKDAYS)
        val weekend = StrapAlarm(1, true, false, 6, 45, 0x60)
        val later = StrapAlarm(2, true, false, 7, 0, StrapAlarm.EVERY_DAY)
        // a one-time alarm the strap has just switched off still counts as the one that rang
        val once = StrapAlarm(3, false, false, 6, 45, 0)
        val offRepeating = StrapAlarm(4, false, false, 6, 45, StrapAlarm.EVERY_DAY)
        val all = listOf(weekdays, weekend, later, once, offRepeating)
        assertEquals(listOf(0, 3, 4), alarmsAt(all, tuesday).map { it.position })
        assertEquals(listOf(1, 3, 4), alarmsAt(all, tuesday.plusDays(4)).map { it.position })
        assertEquals(emptyList(), alarmsAt(all, tuesday.plusMinutes(1)))
    }
}
