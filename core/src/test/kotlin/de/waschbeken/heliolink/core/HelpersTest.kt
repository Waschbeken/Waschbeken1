package de.waschbeken.heliolink.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HrvMeasurementTest {
    @Test
    fun rmssdOfAlternatingBeats() {
        val rr = List(80) { if (it % 2 == 0) 1000 else 1040 }
        val r = HrvMeasurement.compute(rr)!!
        assertEquals(40, r.rmssd)
        assertEquals(59, r.heartRate)
    }

    @Test
    fun dropsArtefacts() {
        val rr = List(80) { if (it == 40) 2000 else if (it % 2 == 0) 1000 else 1020 }
        val r = HrvMeasurement.compute(rr)!!
        assertEquals(20, r.rmssd)
        assertTrue(r.artefacts >= 1)
    }

    @Test
    fun needsEnoughBeats() {
        assertNull(HrvMeasurement.compute(List(20) { 900 }))
    }
}

class StressMomentsTest {
    private val now = 100_000L * 60

    @Test
    fun findsLongHighStress() {
        val stress = (0 until 20).map { now - 40 * 60 + it * 60L to 80 }
        val m = StressMoments.find(stress, emptyMap(), now)
        assertNotNull(m)
        assertEquals(80, m.average)
    }

    @Test
    fun ignoresShortOrOldOrMoving() {
        val short = (0 until 8).map { now - 20 * 60 + it * 60L to 85 }
        assertNull(StressMoments.find(short, emptyMap(), now))
        val old = (0 until 20).map { now - 3 * 3600 + it * 60L to 85 }
        assertNull(StressMoments.find(old, emptyMap(), now))
        val stress = (0 until 20).map { now - 40 * 60 + it * 60L to 85 }
        val walking = stress.associate { it.first to 100 }
        assertNull(StressMoments.find(stress, walking, now))
    }
}
