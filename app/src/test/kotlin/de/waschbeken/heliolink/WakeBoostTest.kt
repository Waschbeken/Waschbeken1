package de.waschbeken.heliolink

import android.app.AlarmManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.waschbeken.heliolink.core.StrapAlarm
import de.waschbeken.heliolink.service.WakeBoost
import de.waschbeken.heliolink.service.WakeBoostReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

/** "Länger wecken": planned 20 s after the next alarm, started and stopped through the service. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WakeBoostTest {
    private val app get() = ApplicationProvider.getApplicationContext<HelioApp>()
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))

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

    @Test
    fun receiverStartsAndStops() {
        val strap = FakeStrap(app)
        app.repo.controller = strap
        app.prefs.alarmBoostMinutes = 10
        WakeBoostReceiver().onReceive(app, Intent(app, WakeBoostReceiver::class.java).setAction(WakeBoost.ACTION_FIRE))
        assertEquals(listOf(600), strap.wakeStarts)
        WakeBoostReceiver().onReceive(app, Intent(app, WakeBoostReceiver::class.java).setAction(WakeBoost.ACTION_STOP))
        assertEquals(1, strap.wakeStops)
    }
}
