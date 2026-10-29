package de.waschbeken.heliolink.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnalysisTest {
    private val zone = ZoneId.of("Europe/Berlin")

    private fun minute(t: Long, kind: Int, hr: Int = 60, deep: Int = 0, rem: Int = 0, steps: Int = 0) =
        ActivitySample(t, kind, 0, steps, hr, 0, deep, rem)

    @Test
    fun detectsNightWithStagesAndShortWakePhase() {
        val start = LocalDate.of(2026, 10, 28).atTime(23, 0).atZone(zone).toEpochSecond()
        val samples = ArrayList<ActivitySample>()
        for (m in 0 until 480) {
            val t = start + m * 60
            samples += when {
                m in 200..210 -> minute(t, 1, hr = 70) // woke up for 11 minutes
                m < 100 -> minute(t, 120, deep = 50)
                m < 300 -> minute(t, 120)
                else -> minute(t, 120, rem = 60)
            }
        }
        samples += minute(start + 480 * 60, 1, steps = 30)
        val sessions = SleepAnalyzer.sessions(samples)
        assertEquals(1, sessions.size)
        val night = assertNotNull(SleepAnalyzer.nightOf(LocalDate.of(2026, 10, 29), sessions, zone))
        assertEquals(100, night.minutes(SleepStage.DEEP))
        assertEquals(11, night.minutes(SleepStage.AWAKE))
        assertEquals(180, night.minutes(SleepStage.REM))
        assertTrue(night.score in 60..100)
    }

    @Test
    fun zones() {
        val max = HeartRateZones.maxHeartRate(30)
        assertEquals(187, max)
        assertEquals(-1, HeartRateZones.zoneOf(80, max))
        assertEquals(2, HeartRateZones.zoneOf(140, max))
        assertEquals(4, HeartRateZones.zoneOf(180, max))
    }
}
