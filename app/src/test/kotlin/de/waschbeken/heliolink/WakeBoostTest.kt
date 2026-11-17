package de.waschbeken.heliolink

import android.app.AlarmManager
import android.app.KeyguardManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.waschbeken.heliolink.core.StrapAlarm
import de.waschbeken.heliolink.service.WakeBoost
import de.waschbeken.heliolink.service.WakeBoostReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

/**
 * "Länger wecken": planned 20 s after the next alarm, started only when an alarm really rang and
 * the phone is not in use, a one-time alarm only once.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WakeBoostTest {
    private val app get() = ApplicationProvider.getApplicationContext<HelioApp>()
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))

    /** The phone lies locked on the bedside table. */
    @Before
    fun phoneLocked() {
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
    }

    @After
    fun noController() {
        app.repo.controller = null
    }

    @Test
    fun plannedJustAfterTheNextAlarm() {
        val at = ZonedDateTime.now().plusHours(3).withSecond(0).withNano(0)
        app.db.saveAlarm(StrapAlarm(0, true, true, at.hour, at.minute, 0), "Arbeit", pending = false)
        // a deleted one earlier on does not count
        val earlier = ZonedDateTime.now().plusHours(1)
        app.db.saveAlarm(StrapAlarm(1, true, false, earlier.hour, earlier.minute, 0), "", pending = true, deleted = true)
        app.prefs.alarmBoostMinutes = 5

        WakeBoost.schedule(app)
        assertEquals(at.plusSeconds(WakeBoost.DELAY_SECONDS).toInstant().toEpochMilli(), alarms.nextScheduledAlarm!!.triggerAtTime)

        // "Aus": nothing stays planned
        app.prefs.alarmBoostMinutes = 0
        WakeBoost.schedule(app)
        assertNull(alarms.nextScheduledAlarm)
    }

    /** An alarm that rang [WakeBoost.DELAY_SECONDS] ago. */
    private fun alarmJustRang(repetition: Int, enabled: Boolean = true): ZonedDateTime {
        var rang = ZonedDateTime.now().minusSeconds(WakeBoost.DELAY_SECONDS)
        // Not right before a new minute begins, or the receiver would look at the next one.
        if (rang.second >= 55) {
            Thread.sleep((61 - rang.second) * 1000L)
            rang = ZonedDateTime.now().minusSeconds(WakeBoost.DELAY_SECONDS)
        }
        app.db.saveAlarm(StrapAlarm(0, enabled, false, rang.hour, rang.minute, repetition), "", pending = false)
        return rang
    }

    private fun fire(strap: FakeStrap): Boolean {
        WakeBoostReceiver().onReceive(app, Intent(app, WakeBoostReceiver::class.java).setAction(WakeBoost.ACTION_FIRE))
        // the receiver decides off the main thread
        val until = System.currentTimeMillis() + 3_000
        while (strap.wakeStarts.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        return strap.wakeStarts.isNotEmpty()
    }

    @Test
    fun startsAfterAnAlarmAndStops() {
        val strap = FakeStrap(app)
        app.repo.controller = strap
        app.prefs.alarmBoostMinutes = 10
        alarmJustRang(StrapAlarm.EVERY_DAY)
        assertTrue(fire(strap))
        assertEquals(listOf(600), strap.wakeStarts)
        WakeBoostReceiver().onReceive(app, Intent(app, WakeBoostReceiver::class.java).setAction(WakeBoost.ACTION_STOP))
        assertEquals(1, strap.wakeStops)
    }

    @Test
    fun notWithoutAnAlarmAtThatTime() {
        val strap = FakeStrap(app)
        app.repo.controller = strap
        app.prefs.alarmBoostMinutes = 5
        // an alarm an hour from now, and a repeating one at this time that is switched off
        val later = ZonedDateTime.now().plusHours(1)
        app.db.saveAlarm(StrapAlarm(1, true, false, later.hour, later.minute, StrapAlarm.EVERY_DAY), "", pending = false)
        alarmJustRang(StrapAlarm.EVERY_DAY, enabled = false)
        assertFalse(fire(strap))
    }

    @Test
    fun notWhileThePhoneIsInUse() {
        val strap = FakeStrap(app)
        app.repo.controller = strap
        app.prefs.alarmBoostMinutes = 5
        alarmJustRang(StrapAlarm.EVERY_DAY)
        // screen on and unlocked: someone is awake and holds the phone
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        assertTrue(WakeBoost.phoneInUse(app))
        assertFalse(fire(strap))
    }

    @Test
    fun oneTimeAlarmIsBoostedOnce() {
        app.prefs.alarmBoostMinutes = 5
        // The strap switched the one-time alarm off when it rang; it still counts as the one that rang.
        val rang = alarmJustRang(repetition = 0, enabled = false)
        assertTrue(WakeBoost.rang(app))
        // ... but only once: not again, and not tomorrow, should the app still list it as on
        assertFalse(WakeBoost.rang(app))
        app.db.saveAlarm(StrapAlarm(0, true, false, rang.hour, rang.minute, 0), "", pending = false)
        assertNull(WakeBoost.next(app))
        // saved again by the user: it rings and is boosted again
        WakeBoost.alarmChanged(app, 0)
        assertEquals(rang.plusDays(1).withSecond(0).withNano(0).plusSeconds(WakeBoost.DELAY_SECONDS), WakeBoost.next(app)!!.withNano(0))
    }

    @Test
    fun aboutToFireIsLeftAlone() {
        app.prefs.alarmBoostMinutes = 5
        val soon = ZonedDateTime.now().plusSeconds(10)
        // planned for in ten seconds; the strap then reports the one-time alarm as switched off
        app.prefs.wakeBoostAt = soon.toInstant().toEpochMilli()
        WakeBoost.schedule(app)
        assertEquals("still planned", soon.toInstant().toEpochMilli(), app.prefs.wakeBoostAt)
    }
}
