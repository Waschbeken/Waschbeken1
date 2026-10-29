package de.waschbeken.heliolink.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import de.waschbeken.heliolink.core.ActivitySample
import de.waschbeken.heliolink.core.FetchType
import de.waschbeken.heliolink.core.SleepRecordParser
import de.waschbeken.heliolink.core.StressSample
import de.waschbeken.heliolink.core.ValueSample
import de.waschbeken.heliolink.core.parseHex
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Takes over a Helio Strap from a Gadgetbridge data export (Gadgetbridge → Daten exportieren, a zip
 * with preferences/ and database/Gadgetbridge): auth key, MAC address and the whole history.
 */
class GadgetbridgeImport(private val context: Context, private val prefs: Prefs, private val db: HealthDb) {

    data class Result(
        val deviceName: String?,
        val address: String?,
        val keyFound: Boolean,
        val activityMinutes: Int,
        val nights: Int,
        val stress: Int,
        val spo2: Int,
        val restingHr: Int,
        val hrv: Int,
    ) {
        val summary: String
            get() = buildString {
                append(if (keyFound) "Auth-Key und Strap übernommen. " else "Kein Auth-Key gefunden. ")
                append("$activityMinutes Minuten Aktivität, $nights Nächte mit Schlafphasen, ")
                append("$stress Stresswerte, $spo2 SpO₂-Messungen, $restingHr Ruhepuls-Werte und $hrv HRV-Werte importiert.")
            }
    }

    class ImportException(message: String) : Exception(message)

    /** Reads the export. [progress] receives short status texts. Runs blocking, call it off the main thread. */
    fun run(uri: Uri, progress: (String) -> Unit): Result {
        val dir = File(context.cacheDir, "gb-import").apply { deleteRecursively(); mkdirs() }
        try {
            progress("Entpacke Export …")
            var dbFile: File? = null
            val devicePrefs = HashMap<String, JSONObject>()
            val input = context.contentResolver.openInputStream(uri) ?: throw ImportException("Datei lässt sich nicht öffnen")
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    when {
                        name == "database/Gadgetbridge" -> {
                            val f = File(dir, "gadgetbridge.db")
                            f.outputStream().use { zip.copyTo(it) }
                            dbFile = f
                        }
                        name.startsWith("preferences/device_") && name.endsWith(".json") -> {
                            val mac = name.removePrefix("preferences/device_").removeSuffix(".json")
                            devicePrefs[mac.uppercase()] = JSONObject(zip.readBytes().decodeToString())
                        }
                    }
                }
            }
            val file = dbFile ?: throw ImportException("Keine Gadgetbridge-Datenbank im Export gefunden. Bitte in Gadgetbridge „Daten exportieren“ verwenden.")

            val gb = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            gb.use { g ->
                val device = findStrap(g) ?: throw ImportException("Im Export ist kein Amazfit Helio Strap.")
                val (deviceId, name, address) = device
                val pref = devicePrefs[address.uppercase()]?.optJSONObject("preferences")
                val key = pref?.optJSONObject("authkey")?.optString("value")?.takeIf { parseHex(it)?.size == 16 }
                if (key != null) {
                    prefs.authKeyHex = key
                    prefs.deviceAddress = address
                    prefs.deviceName = name
                }

                progress("Importiere Aktivität …")
                val activity = importActivity(g, deviceId)
                progress("Importiere Schlaf …")
                val nights = importSleep(g, deviceId)
                progress("Importiere Stress, SpO₂ und Puls …")
                val stress = importStress(g, deviceId)
                val spo2 = importValues(g, deviceId, "HUAMI_SPO2_SAMPLE", "SPO2", HealthDb.SPO2)
                val resting = importValues(g, deviceId, "HUAMI_HEART_RATE_RESTING_SAMPLE", "HEART_RATE", HealthDb.RESTING_HR)
                val hrv = importValues(g, deviceId, "GENERIC_HRV_VALUE_SAMPLE", "VALUE", HealthDb.HRV)

                // Continue where Gadgetbridge stopped, so the next sync only fetches what is new.
                fun cursor(type: FetchType, key: String) {
                    val t = pref?.optJSONObject(key)?.optLong("value") ?: 0L
                    if (t > 0 && (prefs.get(type) ?: 0L) < t) prefs.set(type, t)
                }
                cursor(FetchType.ACTIVITY, "lastSyncTimeMillis")
                cursor(FetchType.STRESS, "lastStressAutoTimeMillis")
                cursor(FetchType.SPO2, "lastSpo2normalTimeMillis")
                cursor(FetchType.RESTING_HEART_RATE, "lastHeartRateRestingTimeMillis")
                cursor(FetchType.SLEEP, "lastSleepSessionTimeMillis")

                return Result(name, address, key != null, activity, nights, stress, spo2, resting, hrv)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun hasTable(g: SQLiteDatabase, table: String): Boolean =
        g.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }

    private fun findStrap(g: SQLiteDatabase): Triple<Long, String, String>? {
        if (!hasTable(g, "DEVICE")) return null
        val devices = ArrayList<Triple<Long, String, String>>()
        g.rawQuery("SELECT _id, NAME, IDENTIFIER, TYPE_NAME FROM DEVICE", null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1).orEmpty()
                val type = c.getString(3).orEmpty()
                if (name.contains("Helio", true) || type.contains("HELIO", true)) {
                    devices += Triple(c.getLong(0), name, c.getString(2))
                }
            }
        }
        return devices.firstOrNull()
    }

    private fun importActivity(g: SQLiteDatabase, device: Long): Int {
        if (!hasTable(g, "HUAMI_EXTENDED_ACTIVITY_SAMPLE")) return 0
        var count = 0
        val batch = ArrayList<ActivitySample>(5000)
        g.rawQuery(
            "SELECT TIMESTAMP, RAW_KIND, RAW_INTENSITY, STEPS, HEART_RATE, SLEEP, DEEP_SLEEP, REM_SLEEP " +
                "FROM HUAMI_EXTENDED_ACTIVITY_SAMPLE WHERE DEVICE_ID = ? ORDER BY TIMESTAMP",
            arrayOf(device.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val hr = c.getInt(4)
                batch += ActivitySample(
                    c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3),
                    if (hr in 1..254) hr else 0, c.getInt(5), c.getInt(6), c.getInt(7),
                )
                if (batch.size == 5000) {
                    db.insertActivity(batch)
                    count += batch.size
                    batch.clear()
                }
            }
        }
        db.insertActivity(batch)
        return count + batch.size
    }

    private fun importSleep(g: SQLiteDatabase, device: Long): Int {
        if (!hasTable(g, "HUAMI_SLEEP_SESSION_SAMPLE")) return 0
        val blobs = ArrayList<ByteArray>()
        g.rawQuery("SELECT DATA FROM HUAMI_SLEEP_SESSION_SAMPLE WHERE DEVICE_ID = ? ORDER BY TIMESTAMP", arrayOf(device.toString())).use { c ->
            while (c.moveToNext()) c.getBlob(0)?.let { blobs += it }
        }
        val records = blobs.mapNotNull { SleepRecordParser.parse(it) }
            .groupBy { it.day }
            .map { (_, list) -> list.maxBy { it.updated } }
        db.insertSleepRecords(records)
        return records.size
    }

    private fun importStress(g: SQLiteDatabase, device: Long): Int {
        if (!hasTable(g, "HUAMI_STRESS_SAMPLE")) return 0
        val samples = ArrayList<StressSample>()
        g.rawQuery("SELECT TIMESTAMP, STRESS FROM HUAMI_STRESS_SAMPLE WHERE DEVICE_ID = ?", arrayOf(device.toString())).use { c ->
            while (c.moveToNext()) {
                val v = c.getInt(1)
                if (v in 0..100) samples += StressSample(c.getLong(0) / 1000, v)
            }
        }
        db.insertStress(samples)
        return samples.size
    }

    /** Tables with TIMESTAMP in milliseconds and one integer value. */
    private fun importValues(g: SQLiteDatabase, device: Long, table: String, column: String, target: String): Int {
        if (!hasTable(g, table)) return 0
        val samples = ArrayList<ValueSample>()
        g.rawQuery("SELECT TIMESTAMP, $column FROM $table WHERE DEVICE_ID = ?", arrayOf(device.toString())).use { c ->
            while (c.moveToNext()) {
                val v = c.getInt(1)
                if (v > 0) samples += ValueSample(c.getLong(0) / 1000, v)
            }
        }
        db.insertValues(target, samples)
        return samples.size
    }
}
