package de.waschbeken.heliolink

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.waschbeken.heliolink.core.ActivitySample
import de.waschbeken.heliolink.core.SleepEdit
import de.waschbeken.heliolink.ui.home.HomeData
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** The start page switches from yesterday to today as soon as you are up, and stays there. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HomeDayTest {
    private val app get() = ApplicationProvider.getApplicationContext<HelioApp>()
    private val zone = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 3, 10)
    private val yesterday = today.minusDays(1)
    private fun at(h: Int, m: Int = 0) = today.atTime(h, m)
    private fun sec(t: LocalDateTime) = t.atZone(zone).toEpochSecond()

    /** Asleep 23:00–06:30, then [awakeMinutes] minutes worn and awake. */
    private fun night(awakeMinutes: Int = 0) {
        val samples = ArrayList<ActivitySample>()
        var t = sec(yesterday.atTime(23, 0))
        while (t < sec(at(6, 30))) {
            samples += ActivitySample(t, ActivitySample.KIND_SLEEP, 2, 0, 52, 0, 0, 0)
            t += 60
        }
        repeat(awakeMinutes) { samples += ActivitySample(t + it * 60L, 1, 40, 30, 75, 0, 0, 0) }
        app.db.insertActivity(samples)
    }

    private fun day(now: LocalDateTime, steps: Long? = null) = HomeData.homeDay(app.db, zone, steps, now)

    @Test
    fun staysYesterdayWhileStillInBed() {
        night()
        assertEquals(yesterday, day(at(7)))
        assertEquals(yesterday, day(at(7), steps = 20))
        assertEquals(today, day(at(12)))
    }

    @Test
    fun correctedNightSwitchesAtOnce() {
        night()
        assertEquals(yesterday, day(at(7)))
        // The strap thought you slept on; the correction says you got up at 06:40.
        app.db.saveSleepEdit(SleepEdit(today.toEpochDay(), sec(yesterday.atTime(23, 10)), sec(at(6, 40))))
        assertEquals(today, day(at(7)))
    }

    @Test
    fun correctionEndingLaterWaitsForThatTime() {
        night()
        app.db.saveSleepEdit(SleepEdit(today.toEpochDay(), sec(yesterday.atTime(23, 10)), sec(at(7, 30))))
        assertEquals(yesterday, day(at(7)))
        assertEquals(today, day(at(7, 31)))
    }

    @Test
    fun walkingAroundSwitchesAndItStays() {
        night()
        assertEquals(today, day(at(7), steps = HomeData.AWAKE_STEPS))
        // Back in bed for coffee, no live count yet after a restart: still today.
        assertEquals(today, day(at(8)))
        assertEquals(today.toEpochDay(), app.prefs.awakeDay)
    }

    @Test
    fun stepsRightAfterTheNightOrAtNightDoNotCount() {
        night()
        assertEquals(yesterday, day(at(6, 35), steps = 500))
        assertEquals(yesterday, day(at(4), steps = 500))
    }

    @Test
    fun awakeMinutesAfterTheNightStillWork() {
        night(awakeMinutes = 5)
        assertEquals(today, day(at(6, 40)))
    }

    @Test
    fun ichBinWach() {
        night()
        app.prefs.awakeDay = today.toEpochDay()
        assertEquals(today, day(at(7)))
        // Yesterday's tap does not count for tomorrow.
        app.prefs.awakeDay = yesterday.toEpochDay()
        assertEquals(yesterday, day(at(7)))
    }
}
