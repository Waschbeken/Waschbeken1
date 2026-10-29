package de.waschbeken.heliolink.core

import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyCheckTest {
    private val today = LocalDate.of(2026, 10, 29)

    /** 30 ordinary nights with a little noise. */
    private fun normalNights(seed: Int = 1): List<NightVitals> {
        val r = Random(seed)
        return (30 downTo 1).map { back ->
            NightVitals(
                today.minusDays(back.toLong()),
                restingHr = 56 + r.nextDouble(-1.5, 1.5),
                hrv = 60 + r.nextDouble(-6.0, 6.0),
                temperature = 34.0 + r.nextDouble(-0.15, 0.15),
                breath = 14.5 + r.nextDouble(-0.4, 0.4),
            )
        }
    }

    @Test
    fun ordinaryNightIsNormal() {
        val res = BodyCheck.check(normalNights() + NightVitals(today, 56.5, 58.0, 34.05, 14.6))!!
        assertEquals(BodyCheck.Level.NORMAL, res.level)
        assertTrue(res.signals.isEmpty())
        assertEquals(4, res.all.size)
        assertTrue(res.all.none { it.flagged })
    }

    @Test
    fun feverishNightAlerts() {
        val res = BodyCheck.check(normalNights() + NightVitals(today, 63.0, 41.0, 34.8, 16.4))!!
        assertEquals(BodyCheck.Level.ALERT, res.level)
        assertEquals(4, res.signals.size)
        assertTrue(res.signals.first { it.vital == BodyCheck.Vital.TEMPERATURE }.strong)
    }

    @Test
    fun oneSlightlyHighValueIsNotAWarning() {
        val res = BodyCheck.check(normalNights() + NightVitals(today, 60.0, 59.0, 34.0, 14.5))!!
        assertEquals(BodyCheck.Level.NORMAL, res.level)
    }

    @Test
    fun temperatureAloneIsWorthWatching() {
        val res = BodyCheck.check(normalNights() + NightVitals(today, 56.0, 60.0, 34.45, 14.5))!!
        assertEquals(BodyCheck.Level.WATCH, res.level)
    }

    @Test
    fun countsNightsInARow() {
        val nights = normalNights().dropLast(1) + NightVitals(today.minusDays(1), 62.0, 44.0, 34.6, 15.8) +
            NightVitals(today, 63.0, 42.0, 34.7, 16.0)
        assertEquals(2, BodyCheck.check(nights)!!.nights)
    }

    @Test
    fun needsHistory() {
        assertNull(BodyCheck.check(normalNights().takeLast(4) + NightVitals(today, 70.0, 30.0, 36.0, 20.0)))
    }
}

class HrvStatusTest {
    private val today = LocalDate.of(2026, 10, 29)

    private fun nights(recent: Double): Map<LocalDate, Double> {
        val r = Random(3)
        return (0L..50L).associate { back ->
            today.minusDays(back) to (if (back < 7) recent else 60.0) + r.nextDouble(-5.0, 5.0)
        }
    }

    @Test
    fun usualWeekIsBalanced() {
        assertEquals(HrvStatus.Status.BALANCED, HrvStatus.status(nights(60.0), today)!!.status)
    }

    @Test
    fun lowWeek() {
        val s = HrvStatus.status(nights(50.0), today)!!.status
        assertTrue(s == HrvStatus.Status.LOW || s == HrvStatus.Status.POOR)
        assertEquals(HrvStatus.Status.POOR, HrvStatus.status(nights(40.0), today)!!.status)
    }

    @Test
    fun highWeek() {
        assertEquals(HrvStatus.Status.HIGH, HrvStatus.status(nights(72.0), today)!!.status)
    }

    @Test
    fun needsTwoWeeks() {
        assertNull(HrvStatus.status(nights(60.0).filterKeys { it > today.minusDays(10) }, today))
    }
}

class SubstancesTest {
    @Test
    fun halvesPerHalfLife() {
        val coffee = listOf(Substances.Intake(0, 100.0))
        assertEquals(100.0, Substances.level(coffee, Substances.Kind.CAFFEINE, 0), 0.01)
        assertEquals(50.0, Substances.level(coffee, Substances.Kind.CAFFEINE, 5 * 3600), 0.01)
        assertEquals(0.0, Substances.level(coffee, Substances.Kind.CAFFEINE, -10), 0.01)
    }

    @Test
    fun findsWhenItIsGone() {
        val t = Substances.timeBelow(listOf(Substances.Intake(0, 1.0)), Substances.Kind.NICOTINE, 0.1, 0)!!
        // 10 % after log2(10) ≈ 3.32 half-lives of 2 h
        assertTrue(t in (6 * 3600 + 30 * 60)..(6 * 3600 + 45 * 60), "t=$t")
    }

    @Test
    fun recognisesEntries() {
        assertEquals(Substances.Kind.CAFFEINE, Substances.of("Koffein")?.first)
        assertEquals(35.0, Substances.of("Cola")?.second)
        assertEquals(Substances.Kind.NICOTINE, Substances.of("Nikotin")?.first)
        assertNull(Substances.of("Essen"))
    }
}

class EntryEffectsTest {
    private fun day(start: Long, hr: (Int) -> Int): List<ActivitySample> =
        (0 until 24 * 60).map { m -> ActivitySample(start + m * 60L, 80, 10, 0, hr(m), 0, 0, 0) }

    @Test
    fun findsRaisedPulseAfterEntry() {
        val days = 20
        val samples = ArrayList<ActivitySample>()
        val events = ArrayList<Pair<Long, String>>()
        for (d in 0 until days) {
            val start = d * 86_400L
            val coffeeDay = d % 4 == 0
            samples += day(start) { m -> if (coffeeDay && m in 600..660) 72 else 62 }
            if (coffeeDay) events += start + 600 * 60L to "Koffein"
        }
        val e = EntryEffects.compute(events, samples).single()
        assertEquals("Koffein", e.tag)
        assertTrue(e.count >= 3)
        assertTrue(e.firstHour in 8.0..11.0, "firstHour=${e.firstHour}")
        assertEquals(60, e.lasts)
    }

    @Test
    fun ignoresMovement() {
        val samples = ArrayList<ActivitySample>()
        val events = ArrayList<Pair<Long, String>>()
        for (d in 0 until 20) {
            val start = d * 86_400L
            val walkDay = d % 4 == 0
            samples += (0 until 24 * 60).map { m ->
                val walking = walkDay && m in 600..660
                ActivitySample(start + m * 60L, 80, 10, if (walking) 100 else 0, if (walking) 110 else 62, 0, 0, 0)
            }
            if (walkDay) events += start + 600 * 60L to "Kaffee"
        }
        // only minutes without steps count, and in the first hour there are none on those days
        val e = EntryEffects.compute(events, samples).singleOrNull()
        assertTrue(e == null || kotlin.math.abs(e.firstHour) < 2, "effect=$e")
    }
}

class BatteryForecastTest {
    @Test
    fun usesHistoryRightAfterCharging() {
        val h = 3600L
        val levels = listOf(
            ValueSample(0, 100), ValueSample(48 * h, 80), ValueSample(96 * h, 60), // 20 % per 2 days
            ValueSample(97 * h, 100), ValueSample(99 * h, 99), // just charged
        )
        val days = BatteryForecast.daysLeft(levels)
        assertNotNull(days)
        assertTrue(days in 8.0..11.0, "days=$days")
    }
}
