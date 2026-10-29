package de.waschbeken.heliolink.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OxygenDropsTest {
    private fun night(values: List<Int>, everyMinutes: Int = 2) = values.mapIndexed { i, v -> ValueSample(i * everyMinutes * 60L, v) }

    @Test
    fun steadyNightHasNoDrops() {
        val r = OxygenDrops.analyze(night(List(200) { 96 + it % 2 }), 400)!!
        assertEquals(0, r.drops)
        assertEquals("unauffällig", r.rating)
        assertEquals(0, r.minutesBelow90)
    }

    @Test
    fun countsEachDropOnce() {
        val values = ArrayList<Int>()
        repeat(10) {
            values += List(10) { 96 }
            values += listOf(92, 91, 92) // one drop over three measurements
        }
        val r = OxygenDrops.analyze(night(values), 260)!!
        assertEquals(10, r.drops)
        assertEquals(91, r.lowest)
    }

    @Test
    fun minutesBelow90() {
        val r = OxygenDrops.analyze(night(List(20) { 96 } + List(5) { 88 } + List(20) { 96 }), 90)!!
        assertEquals(10, r.minutesBelow90)
    }

    @Test
    fun needsEnoughMeasurements() {
        assertNull(OxygenDrops.analyze(night(listOf(96, 95, 96)), 400))
    }
}

class BedtimeInsightTest {
    @Test
    fun findsTheBetterHour() {
        val nights = List(8) { (22 * 60 + 40) to 84 } + List(8) { (0 * 60 + 30) to 70 } + List(4) { (23 * 60 + 50) to 76 }
        val r = BedtimeInsight.analyze(nights)!!
        assertTrue(r.from in (22 * 60)..(22 * 60 + 40), "from=${r.from}")
        assertEquals(84.0, r.score, 0.01)
        assertTrue(r.difference > 8)
    }

    @Test
    fun noClearDifference() {
        assertNull(BedtimeInsight.analyze(List(12) { (22 * 60 + it * 10) to 80 }))
    }
}

class RhythmCheckTest {
    @Test
    fun regularRhythmWithBreathingIsRegular() {
        // sinus rhythm: 900 ms with smooth breathing variation
        val rr = List(80) { (900 + 40 * kotlin.math.sin(it / 4.0 * Math.PI)).toInt() }
        val w = RhythmCheck.analyze(rr)!!
        assertFalse(w.irregular, "$w")
    }

    @Test
    fun randomIntervalsAreIrregular() {
        val r = Random(7)
        val rr = List(80) { r.nextInt(500, 1100) }
        val w = RhythmCheck.analyze(rr)!!
        assertTrue(w.irregular, "$w")
    }

    @Test
    fun singleMissedBeatsAreIgnored() {
        val rr = List(80) { if (it % 20 == 10) 1800 else 860 + (it % 3) * 5 }
        val w = RhythmCheck.analyze(rr)
        assertNotNull(w)
        assertFalse(w.irregular)
    }

    @Test
    fun hintNeedsRepeats() {
        assertFalse(RhythmCheck.worthAHint(listOf(1L, 1L, 1L, 1L)))
        assertTrue(RhythmCheck.worthAHint(listOf(1L, 1L, 2L, 2L)))
    }
}
