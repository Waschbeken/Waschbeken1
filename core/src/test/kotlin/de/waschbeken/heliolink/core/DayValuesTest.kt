package de.waschbeken.heliolink.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DayValuesTest {
    @Test
    fun minuteRmssdNeedsEnoughCleanBeats() {
        assertNull(minuteRmssd(List(20) { 800 }))
        // alternating 780/820 ms: successive differences of 40 ms
        val rr = List(70) { if (it % 2 == 0) 780 else 820 }
        assertEquals(40, minuteRmssd(rr))
        // a single artefact (missed beat) is ignored
        val withArtefact = rr.toMutableList().apply { set(30, 1600) }
        assertEquals(40, minuteRmssd(withArtefact))
    }

    @Test
    fun temperatureInCentiOrDeciCelsius() {
        assertEquals(3350, TemperatureCodec.centiCelsius(3350))
        assertEquals(3350, TemperatureCodec.centiCelsius(335))
        assertNull(TemperatureCodec.centiCelsius(0))
        assertNull(TemperatureCodec.centiCelsius(-1))
        assertNull(TemperatureCodec.centiCelsius(9999))
    }
}
