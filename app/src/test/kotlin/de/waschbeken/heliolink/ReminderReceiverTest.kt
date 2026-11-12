package de.waschbeken.heliolink

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.waschbeken.heliolink.core.Reminder
import de.waschbeken.heliolink.core.Reminders
import de.waschbeken.heliolink.service.ReminderReceiver
import de.waschbeken.heliolink.service.ReminderScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

/** Reminders on the arm: planned with Android's alarm clock, shown as a notification, "Erledigt" notes an entry. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReminderReceiverTest {
    private val app get() = ApplicationProvider.getApplicationContext<HelioApp>()
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    /** A reminder two hours from now (on any day). */
    private fun reminder(): Reminder {
        val at = ZonedDateTime.now().plusHours(2)
        return Reminder(1, "Tabletten", at.hour, at.minute, pulses = 2)
    }

    @Test
    fun plansTheNextReminder() {
        val r = reminder()
        app.prefs.reminders = listOf(r)
        ReminderScheduler.schedule(app)
        val expected = Reminders.next(listOf(r), ZonedDateTime.now())!!.first.toInstant().toEpochMilli()
        assertEquals(expected, alarms.nextScheduledAlarm!!.triggerAtTime)

        // Without reminders nothing stays planned.
        app.prefs.reminders = emptyList()
        ReminderScheduler.schedule(app)
        assertNull(alarms.nextScheduledAlarm)
    }

    private fun due(atSeconds: Long) = Intent(app, ReminderReceiver::class.java)
        .putExtra(ReminderScheduler.EXTRA_IDS, intArrayOf(1))
        .putExtra(ReminderScheduler.EXTRA_AT, atSeconds)

    @Test
    fun dueReminderNotifiesAndDoneAddsAnEntry() {
        app.prefs.reminders = listOf(reminder())
        ReminderReceiver().onReceive(app, due(System.currentTimeMillis() / 1000))

        val shown = notifications.allNotifications.single()
        assertEquals("Tabletten", shown.extras.getString(android.app.Notification.EXTRA_TITLE))
        val done = shown.actions.single()
        assertEquals("Erledigt", done.title.toString())
        // The next one is planned right away.
        assertNotNull(alarms.nextScheduledAlarm)

        // "Erledigt": the entry is written in the background.
        done.actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        val now = System.currentTimeMillis() / 1000
        var entries = app.db.events(now - 60, now + 60)
        val until = System.currentTimeMillis() + 5_000
        while (entries.isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(50)
            entries = app.db.events(now - 60, now + 60)
        }
        val entry = entries.single()
        assertEquals("Tabletten", entry.tag)
        assertEquals("Erinnerung erledigt", entry.note)
        assertTrue("the notification is gone", notifications.allNotifications.isEmpty())
    }

    @Test
    fun muchTooLateIsLeftOut() {
        app.prefs.reminders = listOf(reminder())
        ReminderReceiver().onReceive(app, due(System.currentTimeMillis() / 1000 - 40 * 60))
        assertTrue(notifications.allNotifications.isEmpty())
        assertNotNull("but the next one is still planned", alarms.nextScheduledAlarm)
    }
}
