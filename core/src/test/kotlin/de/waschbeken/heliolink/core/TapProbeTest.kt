package de.waschbeken.heliolink.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TapProbeTest {
    private val quiet = TapProbe.Phase("Nicht berühren", 0, 10_000, tapping = false)
    private val tap = TapProbe.Phase("3× tippen", 10_000, 20_000, tapping = true)
    private val phases = listOf(quiet, tap)

    @Test
    fun onlyWhatComesWhileTappingCounts() {
        val messages = listOf(
            // the battery level comes now and then, also while tapping
            TapProbe.Message(2_000, "Endpunkt 0029", "0464"),
            TapProbe.Message(12_000, "Endpunkt 0029", "0463"),
            // a message that only comes with the taps, three times
            TapProbe.Message(13_000, "Kanal 0010", "0b01"),
            TapProbe.Message(13_400, "Kanal 0010", "0b02"),
            TapProbe.Message(13_800, "Kanal 0010", "0b03"),
        )
        val r = TapProbe.analyse(messages, phases)
        assertEquals(listOf("Kanal 0010 0b"), r.reactions)
        assertEquals(1, r.byPhase.getValue(quiet).size)
        assertEquals(4, r.byPhase.getValue(tap).size)
        val text = TapProbe.report(r, phases, "HelioLink Tipp-Test")
        assertTrue(text.contains("Reaktion: Kanal 0010 0b"), text)
        assertTrue(text.contains("+13.4 s  Kanal 0010  0b02"), text)
    }

    @Test
    fun nothingNewMeansNoReaction() {
        val messages = listOf(TapProbe.Message(2_000, "Endpunkt 0016", "03"), TapProbe.Message(15_000, "Endpunkt 0016", "03"))
        val r = TapProbe.analyse(messages, phases)
        assertTrue(r.reactions.isEmpty())
        assertTrue(TapProbe.report(r, phases, "x").contains("Keine Reaktion"))
        assertTrue(TapProbe.analyse(emptyList(), phases).reactions.isEmpty())
    }
}
