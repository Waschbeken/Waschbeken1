package de.waschbeken.heliolink.core

import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InsightsTest {
    @Test
    fun alarmRoundTrip() {
        val a = StrapAlarm(0, enabled = true, smart = true, hour = 6, minute = 0, repetition = StrapAlarm.WEEKDAYS)
        val cmd = AlarmCodec.set(a)
        assertEquals(listOf(0x03, 0x01, 0x05, 0x00, 6, 0, 0x1f, 0, 0, 0, 0, 0), cmd.map { it.toInt() })
        // strap answer with one alarm
        val response = byteArrayOf(0x0a, 0x01, 0x05, 0x00, 6, 0, 0x1f, 0, 0, 0, 1, 0)
        assertEquals(listOf(a), AlarmCodec.parse(response))
        assertTrue(a.repeatsOn(DayOfWeek.FRIDAY))
        assertTrue(!a.repeatsOn(DayOfWeek.SUNDAY))
    }

    @Test
    fun detectsRun() {
        val samples = (0 until 60).map { m ->
            val running = m in 10..40
            ActivitySample(m * 60L, 1, if (running) 90 else 5, if (running) 165 else 0, if (running) 180 else 70, 0, 0, 0)
        }
        val found = ActivityDetector.detect(samples, 60)
        assertEquals(1, found.size)
        assertEquals(DetectedActivity.Kind.RUN, found[0].kind)
        assertEquals(31, found[0].minutes)
    }

    @Test
    fun readinessAndBattery() {
        val r = assertNotNull(Readiness.calculate(80, 60.0, 50.0, 55, 58.0))
        assertTrue(r.score in 80..100, "score ${r.score}")
        val days = BatteryForecast.daysLeft(listOf(ValueSample(0, 100), ValueSample(86400, 90), ValueSample(2 * 86400L, 80)))
        assertEquals(8.0, days!!, 0.01)
    }
}

class NextAlarmTest {
    @Test
    fun findsNextWeekday() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        // Friday 2026-10-30 22:00 -> next weekday alarm is Monday 06:00
        val now = java.time.ZonedDateTime.of(2026, 10, 30, 22, 0, 0, 0, zone)
        val alarm = StrapAlarm(0, true, false, 6, 0, StrapAlarm.WEEKDAYS)
        val next = nextAlarm(listOf(alarm), now)!!
        assertEquals(java.time.DayOfWeek.MONDAY, next.dayOfWeek)
        assertEquals(6, next.hour)
    }
}
