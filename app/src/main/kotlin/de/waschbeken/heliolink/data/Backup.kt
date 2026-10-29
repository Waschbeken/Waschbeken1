package de.waschbeken.heliolink.data

import android.content.Context
import android.net.Uri
import de.waschbeken.heliolink.core.BackupCrypto
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
        createBackup(out)
    }

    /**
     * Writes the backup to [out] and closes it. With a backup password the content is encrypted
     * ([BackupCrypto]); the file stays a zip with only a small unencrypted note inside.
     */
    fun createBackup(out: java.io.OutputStream) {
        val password = prefs.backupPassword
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(META))
            zip.write(meta(encrypted = password != null).toString().toByteArray())
            zip.closeEntry()
            if (password == null) {
                writeContent(zip)
            } else {
                zip.putNextEntry(ZipEntry(ENCRYPTED))
                val sealed = BackupCrypto.encrypt(NotClosing(zip), password.toCharArray())
                ZipOutputStream(sealed).use { inner ->
                    inner.putNextEntry(ZipEntry(META))
                    inner.write(meta(encrypted = false).toString().toByteArray())
                    inner.closeEntry()
                    writeContent(inner)
                }
                zip.closeEntry()
            }
        }
    }

    private fun meta(encrypted: Boolean) = JSONObject()
        .put("format", 1)
        .put("app", BuildConfig.VERSION_NAME)
        .put("created", System.currentTimeMillis())
        .put("encrypted", encrypted)

    private fun writeContent(zip: ZipOutputStream) {
        zip.putNextEntry(ZipEntry("settings.json"))
        zip.write(prefs.exportAll().apply { remove(Prefs.KEY_BACKUP_PASSWORD) }.toString(2).toByteArray())
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("health.db"))
        db.backupTo(zip)
        zip.closeEntry()
    }

    /** Lets the encrypted part finish without closing the zip around it. */
    private class NotClosing(out: java.io.OutputStream) : java.io.FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = out.flush()
    }

    /** True if the backup in [uri] needs its password. */
    fun isEncrypted(uri: Uri): Boolean {
        val input = context.contentResolver.openInputStream(uri) ?: throw BackupException("Datei lässt sich nicht öffnen")
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: return false
                if (e.name == ENCRYPTED) return true
                if (e.name == "health.db") return false
            }
        }
    }

    /**
     * Replaces database and settings with the backup. Everything is read and checked first; only then
     * [beforeReplace] runs (to stop the strap connection) and the data on the phone is replaced.
     * Throws [BackupCrypto.WrongPassword] for an encrypted backup without the right [password].
     */
    fun restore(uri: Uri, password: String? = null, beforeReplace: () -> Unit = {}) {
        val tmp = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            val found = Found()
            val input = context.contentResolver.openInputStream(uri) ?: throw BackupException("Datei lässt sich nicht öffnen")
            ZipInputStream(input.buffered()).use { readEntries(it, tmp, found, password) }
            val db2 = found.db
            if (!found.isBackup || db2 == null) throw BackupException("Das ist keine HelioLink-Sicherung.")
            beforeReplace()
            val keepPassword = if (found.encrypted) password else prefs.backupPassword
            db.restoreFrom(db2)
            found.settings?.let { prefs.importAll(it) }
            prefs.backupPassword = keepPassword
        } finally {
            tmp.deleteRecursively()
        }
    }

    private class Found {
        var settings: JSONObject? = null
        var db: File? = null
        var isBackup = false
        var encrypted = false
    }

    private fun readEntries(zip: ZipInputStream, tmp: File, found: Found, password: String?) {
        while (true) {
            val e = zip.nextEntry ?: break
            when (e.name) {
                META -> {
                    zip.readBytes()
                    found.isBackup = true
                }
                "settings.json" -> found.settings = JSONObject(zip.readBytes().decodeToString())
                "health.db" -> found.db = File(tmp, "health.db").also { f -> f.outputStream().use { zip.copyTo(it) } }
                ENCRYPTED -> {
                    if (password == null) throw BackupCrypto.WrongPassword()
                    found.encrypted = true
                    val plain = try {
                        BackupCrypto.decrypt(zip, password.toCharArray())
                    } catch (d: BackupCrypto.Damaged) {
                        throw BackupException(d.message ?: "Die Sicherung ist beschädigt.")
                    }
                    try {
                        // not closed: that would close the outer zip
                        readEntries(ZipInputStream(plain), tmp, found, null)
                        // read to the end, so the last chunk is checked as well
                        val rest = ByteArray(8192)
                        while (plain.read(rest) >= 0) Unit
                    } catch (d: BackupCrypto.Damaged) {
                        throw BackupException(d.message ?: "Die Sicherung ist beschädigt.")
                    }
                }
            }
        }
    }

    companion object {
        private const val META = "heliolink-backup.json"
        private const val ENCRYPTED = "daten.verschluesselt"

        fun fileDate(): String = LocalDate.now().toString()
    }
}
