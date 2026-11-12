package de.waschbeken.heliolink

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.waschbeken.heliolink.core.TrackPoint
import de.waschbeken.heliolink.data.HealthDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The app updates itself over installed versions, so an older database must be taken along:
 * a database of version 9 (before routes) keeps its workouts and gets the route table.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DbMigrationTest {
    @Test
    fun version9GetsRoutes() {
        val context = ApplicationProvider.getApplicationContext<HelioApp>()
        val name = "old-v9.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL(
                "CREATE TABLE workout (id INTEGER PRIMARY KEY AUTOINCREMENT, sport TEXT, start INTEGER, end INTEGER, " +
                    "avg_hr INTEGER, max_hr INTEGER, kcal INTEGER, zones TEXT, tags TEXT, note TEXT, hrr INTEGER)",
            )
            old.execSQL("CREATE TABLE workout_hr (workout INTEGER, ts INTEGER, bpm INTEGER)")
            old.execSQL("INSERT INTO workout (sport, start, end, avg_hr, max_hr, kcal, zones, hrr) VALUES ('Laufen', 1000, 4000, 140, 170, 300, '1,2,3,4,5', 22)")
            old.version = 9
        }

        val db = HealthDb(context, name)
        val w = db.workouts().single()
        assertEquals("Laufen", w.sport)
        assertEquals(22, w.hrr)
        assertEquals("a workout from before has no route", 0, w.distance)
        assertTrue(db.track(w.id).isEmpty())

        db.addTrackPoint(w.id, TrackPoint(1000, 52.0, 13.0, altitude = 40.0, accuracy = 5f))
        db.addTrackPoint(w.id, TrackPoint(1010, 52.0003, 13.0))
        db.finishWorkout(w.copy(distance = 33))
        assertEquals(33, db.workouts().single().distance)
        val track = db.track(w.id)
        assertEquals(2, track.size)
        assertEquals(40.0, track[0].altitude!!, 1e-9)
        assertEquals(5f, track[0].accuracy!!, 1e-6f)
        assertNull(track[1].altitude)
        assertNull(track[1].accuracy)

        db.deleteWorkout(w.id)
        assertTrue(db.track(w.id).isEmpty())
        db.close()
        file.delete()
    }

    /** Tables and their columns, as SQLite reports them. */
    private fun schema(db: SQLiteDatabase): Map<String, List<String>> {
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return tables.sorted().associateWith { t ->
            db.rawQuery("PRAGMA table_info($t)", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }.sorted()
        }
    }

    /** A database from the very first version ends up exactly like a new one, with every table under its right name. */
    @Test
    fun upgradeFromFirstVersionEqualsFreshInstall() {
        val context = ApplicationProvider.getApplicationContext<HelioApp>()
        val fresh = HealthDb(context, "fresh.db")
        val freshSchema = schema(fresh.readableDatabase)
        fresh.close()

        val file = context.getDatabasePath("old-v1.db")
        file.parentFile?.mkdirs()
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE activity (ts INTEGER PRIMARY KEY, kind INTEGER, intensity INTEGER, steps INTEGER, hr INTEGER, sleep INTEGER, deep INTEGER, rem INTEGER)")
            old.execSQL("CREATE TABLE stress (ts INTEGER PRIMARY KEY, value INTEGER)")
            old.execSQL("CREATE TABLE workout (id INTEGER PRIMARY KEY AUTOINCREMENT, sport TEXT, start INTEGER, end INTEGER, avg_hr INTEGER, max_hr INTEGER, kcal INTEGER, zones TEXT)")
            old.execSQL("CREATE TABLE workout_hr (workout INTEGER, ts INTEGER, bpm INTEGER)")
            old.version = 1
        }
        val upgraded = HealthDb(context, "old-v1.db")
        assertEquals(freshSchema, schema(upgraded.readableDatabase))
        upgraded.close()

        val expected = listOf(
            "activity", "alarm", "battery", "event", "feeling", "hr", "hrv", "hrv_sdnn", "odi", "pai", "respiration", "resting_hr",
            "rhythm", "sleep_edit", "sleep_record", "sleep_tag", "spo2", "stress", "temperature", "workout", "workout_hr", "workout_track",
        )
        assertEquals(expected, freshSchema.keys.toList())
        assertTrue(freshSchema.getValue("workout").containsAll(listOf("tags", "note", "hrr", "distance")))
        context.getDatabasePath("fresh.db").delete()
        file.delete()
    }
}
