package de.waschbeken.heliolink

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.waschbeken.heliolink.core.SleepEdit
import de.waschbeken.heliolink.data.Queries
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** The nights are computed once and again only after the data or the sleep settings changed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NightsCacheTest {
    private val app get() = ApplicationProvider.getApplicationContext<HelioApp>()

    @Test
    fun cacheFollowsChanges() {
        DemoData.fill(app.db, days = 10)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val first = Queries.nights(app.db, today.minusDays(5), today, zone)
        assertSame(first, Queries.nights(app.db, today.minusDays(5), today, zone))
        assertNotNull(first[yesterday])

        // A correction ("Das war kein Schlaf") must show at once.
        app.db.saveSleepEdit(SleepEdit(yesterday.toEpochDay(), 0, 0, deleted = true))
        assertNull(Queries.nights(app.db, today.minusDays(5), today, zone)[yesterday])

        // So must a different sleep goal.
        val before = Queries.nights(app.db, today.minusDays(5), today, zone)
        app.prefs.profile = app.prefs.profile.copy(sleepGoalMinutes = 420)
        val after = Queries.nights(app.db, today.minusDays(5), today, zone)
        assertNotSame(before, after)
        assertTrue(after.values.all { it.goalMinutes == 420 })
    }
}
