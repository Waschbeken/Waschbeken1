package de.waschbeken.heliolink.data

import android.content.Context
import android.net.Uri
import de.waschbeken.heliolink.core.SleepStage
import de.waschbeken.heliolink.BuildConfig
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Data export (CSV, for others) and full backup/restore (database + settings incl. auth key). */
class Backup(private val context: Context, private val prefs: Prefs, private val db: HealthDb) {

    class BackupException(message: String) : Exception(message)

    enum class Range(val label: String, val days: Long?) {
        TODAY("Heute", 0),
        WEEK("Letzte 7 Tage", 7),
        MONTH("Letzte 30 Tage", 30),
        ALL("Alles", null),
    }

    /** CSV files of all data in [range], plus the connection log. Contains no auth key. */
    fun exportData(uri: Uri, range: Range, log: List<String>, info: String) {
        val from = range.days?.let { LocalDate.now().minusDays(it).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() } ?: 0L
        val out = context.contentResolver.openOutputStream(uri) ?: throw BackupException("Datei lässt sich nicht schreiben")
        ZipOutputStream(out.buffered()).use { zip ->
            db.exportCsv(zip, from)
            writeNights(zip, range.days ?: 730L)
            zip.putNextEntry(ZipEntry("protokoll.txt"))
            zip.write(log.reversed().joinToString("\n").toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("info.txt"))
            zip.write(info.toByteArray())
            zip.closeEntry()
            CrashReporter.errors(context)?.let { errors ->
                zip.putNextEntry(ZipEntry("fehler.txt"))
                zip.write(errors.toByteArray())
                zip.closeEntry()
            }
            CrashReporter.read(context)?.let { crash ->
                zip.putNextEntry(ZipEntry("absturz.txt"))
                zip.write(crash.toByteArray())
                zip.closeEntry()
            }
        }
    }

    /** Nights as the app shows them (with the user's corrections): one line per night and one per minute. */
    private fun writeNights(zip: ZipOutputStream, days: Long) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val first = today.minusDays(days.coerceAtMost(730))
        val nights = Queries.nights(db, first, today, zone)
        val tags = db.sleepTags(first.toEpochDay(), today.toEpochDay())
        val time = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        fun fmt(epoch: Long) = java.time.Instant.ofEpochSecond(epoch).atZone(zone).format(time)
        zip.putNextEntry(ZipEntry("schlaf_naechte.csv"))
        val summary = StringBuilder("datum;eingeschlafen;aufgewacht;schlaf_min;tief_min;leicht_min;rem_min;wach_min;score;quelle;tagebuch\n")
        for ((date, n) in nights) {
            val source = when {
                n.edited -> "korrigiert"
                n.estimated -> "geschaetzt"
                else -> "strap"
            }
            summary.append(
                "$date;${fmt(n.start)};${fmt(n.end)};${n.asleepMinutes};${n.minutes(SleepStage.DEEP)};${n.minutes(SleepStage.LIGHT)};" +
                    "${n.minutes(SleepStage.REM)};${n.minutes(SleepStage.AWAKE)};${n.score};$source;" +
                    tags[date.toEpochDay()].orEmpty().sorted().joinToString(",") + "\n",
            )
        }
        zip.write(summary.toString().toByteArray())
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("schlaf_phasen.csv"))
        val stages = StringBuilder("datum;zeit;phase\n")
        for ((date, n) in nights) for ((t, st) in n.stages) stages.append("$date;${fmt(t)};${st.name.lowercase()}\n")
        zip.write(stages.toString().toByteArray())
        zip.closeEntry()
    }

    /** Everything needed to set up the app again: database and settings (incl. auth key). */
    fun createBackup(uri: Uri) {
        val out = context.contentResolver.openOutputStream(uri) ?: throw BackupException("Datei lässt sich nicht schreiben")
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("heliolink-backup.json"))
            val meta = JSONObject()
                .put("format", 1)
                .put("app", BuildConfig.VERSION_NAME)
                .put("created", System.currentTimeMillis())
            zip.write(meta.toString().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write(prefs.exportAll().toString(2).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("health.db"))
            db.backupTo(zip)
            zip.closeEntry()
        }
    }

    /** Replaces database and settings with the backup. The strap connection must be stopped before. */
    fun restore(uri: Uri) {
        val tmp = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            var settings: JSONObject? = null
            var dbFile: File? = null
            var isBackup = false
            val input = context.contentResolver.openInputStream(uri) ?: throw BackupException("Datei lässt sich nicht öffnen")
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    when (e.name) {
                        "heliolink-backup.json" -> {
                            zip.readBytes()
                            isBackup = true
                        }
                        "settings.json" -> settings = JSONObject(zip.readBytes().decodeToString())
                        "health.db" -> dbFile = File(tmp, "health.db").also { f -> f.outputStream().use { zip.copyTo(it) } }
                    }
                }
            }
            val db2 = dbFile
            if (!isBackup || db2 == null) throw BackupException("Das ist keine HelioLink-Sicherung.")
            db.restoreFrom(db2)
            settings?.let { prefs.importAll(it) }
        } finally {
            tmp.deleteRecursively()
        }
    }

    companion object {
        fun fileDate(): String = LocalDate.now().toString()
    }
}
