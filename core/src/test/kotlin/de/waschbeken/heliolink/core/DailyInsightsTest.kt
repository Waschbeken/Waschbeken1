package de.waschbeken.heliolink.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DailyInsightsTest {
    @Test
    fun nothingWhenEverythingIsUsual() {
        val input = DailyInsights.Input(
            sleepMinutes = 440, usualSleepMinutes = 450.0,
            bedtime = -20, usualBedtime = -10.0,
            restingHr = 56, usualRestingHr = 55.0,
            hrv = 50.0, usualHrv = 52.0,
            temperatureDeviation = 0.1,
            stepsYesterday = 8_500, usualSteps = 8_000.0,
        )
        assertTrue(DailyInsights.of(input).isEmpty())
    }

    @Test
    fun longLateNightAndHighRestingHr() {
        val input = DailyInsights.Input(
            sleepMinutes = 736, usualSleepMinutes = 440.0,
            bedtime = 68, usualBedtime = -20.0,
            restingHr = 61, usualRestingHr = 56.0,
            stepsYesterday = 2_000, usualSteps = 8_000.0,
        )
        val list = DailyInsights.of(input)
        assertEquals(3, list.size)
        assertTrue(list[0].text.startsWith("Du hast 4:56 h länger geschlafen"), list[0].text)
        assertTrue(list.any { it.text.contains("Ruhepuls 5 bpm über") })
        assertTrue(list.any { it.text.contains("1:28 h später eingeschlafen") && it.text.contains("23:40") })
    }

    @Test
    fun temperatureAndHrvUseGermanNumbers() {
        val list = DailyInsights.of(DailyInsights.Input(temperatureDeviation = 0.55, hrv = 40.0, usualHrv = 50.0))
        assertTrue(list.any { it.text.contains("+0,6 °C") || it.text.contains("+0,5 °C") }, list.toString())
        assertTrue(list.any { it.text.contains("HRV 20 % unter") }, list.toString())
    }
}
