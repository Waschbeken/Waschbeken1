package de.waschbeken.heliolink

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.waschbeken.heliolink.core.FetchType
import de.waschbeken.heliolink.data.GadgetbridgeImport
import de.waschbeken.heliolink.data.HealthDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Takes over a strap from a Gadgetbridge export like a user would: a zip with the database and the
 * device preferences. Only the Helio Strap's rows count, not those of another watch in the same export.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GadgetbridgeImportTest {
    private val app get() = ApplicationProvider.getApplicationContext<HelioApp>()
    private val mac = "C8:0F:10:AA:BB:CC"
    private val key = "0123456789abcdef0123456789abcdef"
    private val t0 = 1_760_000_000L // seconds

    /** A Gadgetbridge database with the strap (id 1) and another watch (id 2). */
    private fun gadgetbridgeDb(file: File) {
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { g ->
            g.execSQL("CREATE TABLE DEVICE (_id INTEGER PRIMARY KEY, NAME TEXT, IDENTIFIER TEXT, TYPE_NAME TEXT)")
            g.execSQL("INSERT INTO DEVICE VALUES (1, 'Amazfit Helio Strap', '$mac', 'AMAZFITHELIOSTRAP')")
            g.execSQL("INSERT INTO DEVICE VALUES (2, 'Mi Band 8', 'AA:AA:AA:AA:AA:AA', 'MIBAND8')")
            g.execSQL(
                "CREATE TABLE HUAMI_EXTENDED_ACTIVITY_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, RAW_KIND INTEGER, RAW_INTENSITY INTEGER, " +
                    "STEPS INTEGER, HEART_RATE INTEGER, SLEEP INTEGER, DEEP_SLEEP INTEGER, REM_SLEEP INTEGER)",
            )
            for (i in 0 until 120) {
                // 255 is Gadgetbridge's "no value" for the heart rate
                g.execSQL("INSERT INTO HUAMI_EXTENDED_ACTIVITY_SAMPLE VALUES (${t0 + i * 60}, 1, 1, 20, ${i % 30}, ${if (i == 5) 255 else 70}, 0, 0, 0)")
            }
            g.execSQL("INSERT INTO HUAMI_EXTENDED_ACTIVITY_SAMPLE VALUES ($t0, 2, 1, 20, 999, 80, 0, 0, 0)")
            g.execSQL("CREATE TABLE HUAMI_STRESS_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, STRESS INTEGER)")
            g.execSQL("INSERT INTO HUAMI_STRESS_SAMPLE VALUES (${t0 * 1000}, 1, 35)")
            g.execSQL("INSERT INTO HUAMI_STRESS_SAMPLE VALUES (${(t0 + 300) * 1000}, 1, 255)") // invalid
            g.execSQL("CREATE TABLE HUAMI_SPO2_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, SPO2 INTEGER)")
            g.execSQL("INSERT INTO HUAMI_SPO2_SAMPLE VALUES (${t0 * 1000}, 1, 96)")
            g.execSQL("INSERT INTO HUAMI_SPO2_SAMPLE VALUES (${t0 * 1000}, 2, 91)")
            g.execSQL("CREATE TABLE HUAMI_PAI_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, PAI_TODAY REAL, PAI_TOTAL REAL, TIME_LOW INTEGER, TIME_MODERATE INTEGER, TIME_HIGH INTEGER)")
            g.execSQL("INSERT INTO HUAMI_PAI_SAMPLE VALUES (${t0 * 1000}, 1, 4.5, 87.0, 30, 12, 3)")
            g.execSQL("CREATE TABLE GENERIC_TEMPERATURE_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, TEMPERATURE REAL)")
            g.execSQL("INSERT INTO GENERIC_TEMPERATURE_SAMPLE VALUES (${t0 * 1000}, 1, 34.56)")
            g.execSQL("CREATE TABLE ALARM (DEVICE_ID INTEGER, POSITION INTEGER, ENABLED INTEGER, SMART_WAKEUP INTEGER, HOUR INTEGER, MINUTE INTEGER, REPETITION INTEGER, UNUSED INTEGER, TITLE TEXT)")
            g.execSQL("INSERT INTO ALARM VALUES (1, 0, 1, 1, 6, 45, 31, 0, ' Arbeit ')")
            g.execSQL("INSERT INTO ALARM VALUES (1, 1, 0, 0, 9, 0, 0, 1, 'leer')")
            g.execSQL("CREATE TABLE BATTERY_LEVEL (TIMESTAMP INTEGER, DEVICE_ID INTEGER, LEVEL INTEGER)")
            g.execSQL("INSERT INTO BATTERY_LEVEL VALUES ($t0, 1, 77)")
        }
    }

    private fun export(withDb: Boolean = true, withKey: Boolean = true): Uri {
        val dir = File(app.cacheDir, "test-gb").apply { mkdirs() }
        val dbFile = File(dir, "Gadgetbridge")
        if (withDb) gadgetbridgeDb(dbFile)
        val zipFile = File(dir, "export.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            if (withDb) {
                zip.putNextEntry(ZipEntry("database/Gadgetbridge"))
                dbFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("preferences/device_${mac.lowercase()}.json"))
            val authkey = if (withKey) """"authkey":{"value":"$key"},""" else ""
            zip.write("""{"preferences":{$authkey"lastSyncTimeMillis":{"value":${(t0 + 7200) * 1000}}}}""".toByteArray())
            zip.closeEntry()
        }
        return Uri.fromFile(zipFile)
    }

    @Test
    fun takesOverStrapAndHistory() {
        val result = GadgetbridgeImport(app, app.prefs, app.db).run(export()) {}

        assertTrue(result.keyFound)
        assertEquals(key, app.prefs.authKeyHex)
        assertEquals(mac, app.prefs.deviceAddress)
        assertEquals("Amazfit Helio Strap", app.prefs.deviceName)

        assertEquals(120, result.activityMinutes)
        val activity = app.db.activity(t0, t0 + 7200)
        assertEquals(120, activity.size)
        assertEquals("255 means no heart rate", 0, activity[5].heartRate)
        assertTrue("the other watch's 999 steps are not taken", activity.none { it.steps == 999 })

        assertEquals(1, result.stress)
        assertEquals(35, app.db.stress(t0 - 1, t0 + 1000).single().stress)
        assertEquals(1, result.spo2)
        assertEquals(96, app.db.values(HealthDb.SPO2, t0 - 1, t0 + 1).single().value)
        assertEquals(87f, app.db.pai(t0 - 1, t0 + 1).single().total)
        assertEquals(3456, app.db.values(HealthDb.TEMPERATURE, t0 - 1, t0 + 1).single().value)
        assertEquals(77, app.db.values(HealthDb.BATTERY, t0 - 1, t0 + 1).single().value)

        // The unused alarm slot is skipped; the name is kept without the spaces.
        assertEquals(1, result.alarms)
        val alarm = app.db.alarms().single()
        assertEquals("Arbeit", alarm.label)
        assertEquals(6, alarm.alarm.hour)
        assertFalse("the strap already has it", alarm.pending)

        // The next sync continues where Gadgetbridge stopped.
        assertEquals((t0 + 7200) * 1000, app.prefs.get(FetchType.ACTIVITY))
        assertTrue(result.summary.startsWith("Auth-Key und Strap übernommen."))
    }

    @Test
    fun withoutKeyTheDataStillComes() {
        val result = GadgetbridgeImport(app, app.prefs, app.db).run(export(withKey = false)) {}
        assertFalse(result.keyFound)
        assertNull(app.prefs.authKeyHex)
        assertEquals(120, result.activityMinutes)
    }

    @Test
    fun exportWithoutDatabaseIsRefused() {
        val e = assertThrows(GadgetbridgeImport.ImportException::class.java) {
            GadgetbridgeImport(app, app.prefs, app.db).run(export(withDb = false)) {}
        }
        assertTrue(e.message!!.contains("Keine Gadgetbridge-Datenbank"))
    }
}
