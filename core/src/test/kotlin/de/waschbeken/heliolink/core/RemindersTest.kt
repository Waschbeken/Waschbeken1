package de.waschbeken.heliolink.core

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemindersTest {
    private val zone = ZoneId.of("Europe/Berlin")

    // Friday, 30 Oct 2026, 07:30
    private val now = ZonedDateTime.of(2026, 10, 30, 7, 30, 0, 0, zone)

    @Test
    fun nextOnTheSameDayOrLater() {
        val pills = Reminder(1, "Tabletten", 8, 0, days = 0x1f)
        val water = Reminder(2, "Trinken", 7, 0)
        val (at, due) = Reminders.next(listOf(pills, water), now)!!
        assertEquals(ZonedDateTime.of(2026, 10, 30, 8, 0, 0, 0, zone), at)
        assertEquals(listOf(pills), due)
        // After 08:00 on Friday, the weekday reminder waits until Monday; the daily one comes first.
        val (at2, due2) = Reminders.next(listOf(pills, water), now.withHour(9))!!
        assertEquals(ZonedDateTime.of(2026, 10, 31, 7, 0, 0, 0, zone), at2)
        assertEquals(listOf(water), due2)
        val (at3, _) = Reminders.next(listOf(pills), now.withHour(9))!!
        assertEquals(ZonedDateTime.of(2026, 11, 2, 8, 0, 0, 0, zone), at3)
    }

    @Test
    fun remindersAtTheSameTimeComeTogether() {
        val a = Reminder(1, "A", 8, 0)
        val b = Reminder(2, "B", 8, 0)
        assertEquals(listOf(a, b), Reminders.next(listOf(a, b), now)!!.second)
        assertNull(Reminders.next(listOf(a.copy(enabled = false)), now))
    }

    @Test
    fun repeatsWithinTheWindow() {
        val water = Reminder(3, "Trinken", 9, 0, repeatMinutes = 120, endHour = 18, endMinute = 0)
        assertEquals(listOf(540, 660, 780, 900, 1020), water.times())
        assertEquals(ZonedDateTime.of(2026, 10, 30, 9, 0, 0, 0, zone), Reminders.next(listOf(water), now)!!.first)
        assertEquals(ZonedDateTime.of(2026, 10, 30, 15, 0, 0, 0, zone), Reminders.next(listOf(water), now.withHour(13).withMinute(5))!!.first)
        // After the last one of the day: tomorrow at the start.
        assertEquals(ZonedDateTime.of(2026, 10, 31, 9, 0, 0, 0, zone), Reminders.next(listOf(water), now.withHour(17).withMinute(30))!!.first)
        assertEquals("09:00–18:00 alle 2 h · täglich", water.schedule)
        assertEquals(water, Reminder.decode(water.encode()))
    }

    @Test
    fun readsTheOldFormat() {
        val old = Reminder.decode("4|8|15|31|2|1|Tabletten")!!
        assertEquals(Reminder(4, "Tabletten", 8, 15, days = 31, pulses = 2), old)
        assertEquals(listOf(495), old.times())
    }

    @Test
    fun encodesAndDecodes() {
        val r = Reminder(7, "Pause | Augen", 21, 30, days = 0x60, pulses = 2)
        val back = Reminder.decode(r.encode())!!
        assertEquals(r.copy(text = "Pause   Augen"), back)
        assertEquals("21:30 · Sa, So", r.schedule)
        assertEquals("08:00 · Mo–Fr", Reminder(1, "x", 8, 0, days = 0x1f).schedule)
        assertEquals("12:00 · Mo, Mi, Fr", Reminder(1, "x", 12, 0, days = 0b10101).schedule)
    }
}
