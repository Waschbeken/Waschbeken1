package de.waschbeken.heliolink.data

import android.content.ContentValues
import de.waschbeken.heliolink.core.OdiNight
import de.waschbeken.heliolink.core.RhythmCheck
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import de.waschbeken.heliolink.core.ActivitySample
import de.waschbeken.heliolink.core.PaiSample
import de.waschbeken.heliolink.core.StrapAlarm
import de.waschbeken.heliolink.core.SleepEdit
import de.waschbeken.heliolink.core.SleepRecord
import de.waschbeken.heliolink.core.SleepStage
import de.waschbeken.heliolink.core.StressSample
import de.waschbeken.heliolink.core.TrackPoint
import de.waschbeken.heliolink.core.ValueSample

data class Workout(
    val id: Long,
    val sport: String,
    val start: Long, // epoch seconds
    val end: Long,
    val avgHr: Int,
    val maxHr: Int,
    val calories: Int,
    val zoneSeconds: List<Int>,
    /** What the user did or took (recordings), e.g. "Sitzen", "Koffein". */
    val tags: List<String> = emptyList(),
    val note: String = "",
    /** Heart rate drop in the first minute after the end (bpm), null if not measured. */
    val hrr: Int? = null,
    /** Metres along the route recorded with the phone's GPS, 0 without one. */
    val distance: Int = 0,
)

data class HrPoint(val timestamp: Long, val bpm: Int)

class HealthDb(private val context: Context, name: String = DB_NAME) : SQLiteOpenHelper(context, name, null, HealthDbSchema.VERSION) {

    override fun onCreate(db: SQLiteDatabase) = HealthDbSchema.create(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = HealthDbSchema.upgrade(db, oldVersion)

    /** The app's settings (sleep tuning is read while building nights). */
    val prefs: Prefs get() = (context.applicationContext as de.waschbeken.heliolink.HelioApp).prefs

    fun insertOdi(nights: List<OdiNight>) = transaction { db ->
        val cv = ContentValues()
        for (n in nights) {
            cv.clear()
            cv.put("day", n.day)
            cv.put("valid", n.validTime)
            cv.put("odi", n.odi)
            if (n.score != null) cv.put("score", n.score) else cv.putNull("score")
            cv.put("drops", n.drops)
            db.insertWithOnConflict("odi", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** Oxygen drop summaries whose day (local midnight of the wake day, epoch seconds) is in [from, to). */
    fun odi(from: Long, to: Long): List<OdiNight> {
        val out = ArrayList<OdiNight>()
        readableDatabase.rawQuery("SELECT day, valid, odi, score, drops FROM odi WHERE day >= ? AND day < ? ORDER BY day", arrayOf(from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) out += OdiNight(c.getLong(0), c.getLong(1), c.getLong(2), if (c.isNull(3)) null else c.getInt(3), c.getInt(4))
        }
        return out
    }

    data class Feeling(val day: Long, val score: Int, val note: String)

    /** [day] is the epoch day; score 1 (schlecht) to 5 (super), 0 = only a note. */
    fun setFeeling(day: Long, score: Int, note: String) {
        val cv = ContentValues()
        cv.put("day", day)
        cv.put("score", score)
        cv.put("note", note)
        writableDatabase.insertWithOnConflict("feeling", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun feelings(fromDay: Long, toDay: Long): Map<Long, Feeling> {
        val out = HashMap<Long, Feeling>()
        readableDatabase.rawQuery("SELECT day, score, note FROM feeling WHERE day >= ? AND day <= ?", arrayOf(fromDay.toString(), toDay.toString())).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = Feeling(c.getLong(0), c.getInt(1), c.getString(2).orEmpty())
        }
        return out
    }

    // ------------------------------------------------------------------ heart rhythm

    data class RhythmRow(val timestamp: Long, val heartRate: Int, val irregular: Boolean)

    fun insertRhythm(ts: Long, w: RhythmCheck.Window) {
        val cv = ContentValues()
        cv.put("ts", ts)
        cv.put("beats", w.beats)
        cv.put("hr", w.heartRate)
        cv.put("nrmssd", (w.nRmssd * 1000).toInt())
        cv.put("irregular", if (w.irregular) 1 else 0)
        writableDatabase.insertWithOnConflict("rhythm", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun rhythm(from: Long, to: Long): List<RhythmRow> {
        val out = ArrayList<RhythmRow>()
        readableDatabase.rawQuery("SELECT ts, hr, irregular FROM rhythm WHERE ts >= ? AND ts < ? ORDER BY ts", arrayOf(from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) out += RhythmRow(c.getLong(0), c.getInt(1), c.getInt(2) == 1)
        }
        return out
    }

    // ------------------------------------------------------------------ quick entries

    data class Event(val id: Long, val timestamp: Long, val tag: String, val note: String)

    fun addEvent(ts: Long, tag: String, note: String = ""): Long {
        val cv = ContentValues()
        cv.put("ts", ts)
        cv.put("tag", tag)
        cv.put("note", note)
        return writableDatabase.insert("event", null, cv)
    }

    fun deleteEvent(id: Long) {
        writableDatabase.delete("event", "id = ?", arrayOf(id.toString()))
    }

    fun setEventTime(id: Long, ts: Long) {
        val cv = ContentValues()
        cv.put("ts", ts)
        writableDatabase.update("event", cv, "id = ?", arrayOf(id.toString()))
    }

    fun events(from: Long, to: Long): List<Event> {
        val out = ArrayList<Event>()
        readableDatabase.rawQuery(
            "SELECT id, ts, tag, note FROM event WHERE ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            while (c.moveToNext()) out += Event(c.getLong(0), c.getLong(1), c.getString(2).orEmpty(), c.getString(3).orEmpty())
        }
        return out
    }

    // ------------------------------------------------------------------ sleep diary

    /** Diary tags per night (epoch day of the morning). */
    fun sleepTags(fromDay: Long, toDay: Long): Map<Long, Set<String>> {
        val out = HashMap<Long, MutableSet<String>>()
        readableDatabase.rawQuery(
            "SELECT night, tag FROM sleep_tag WHERE night >= ? AND night <= ?",
            arrayOf(fromDay.toString(), toDay.toString()),
        ).use { c ->
            while (c.moveToNext()) out.getOrPut(c.getLong(0)) { HashSet() } += c.getString(1)
        }
        return out
    }

    fun setSleepTag(night: Long, tag: String, on: Boolean) {
        if (on) {
            val cv = ContentValues()
            cv.put("night", night)
            cv.put("tag", tag)
            writableDatabase.insertWithOnConflict("sleep_tag", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        } else {
            writableDatabase.delete("sleep_tag", "night = ? AND tag = ?", arrayOf(night.toString(), tag))
        }
    }

    // ------------------------------------------------------------------ sleep corrections

    /** Corrections for the nights (epoch days of the mornings) [fromDay]..[toDay]. */
    fun sleepEdits(fromDay: Long, toDay: Long): Map<Long, SleepEdit> {
        val out = HashMap<Long, SleepEdit>()
        readableDatabase.rawQuery(
            "SELECT night, start, end, ranges, deleted FROM sleep_edit WHERE night >= ? AND night <= ?",
            arrayOf(fromDay.toString(), toDay.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out[c.getLong(0)] = SleepEdit(c.getLong(0), c.getLong(1), c.getLong(2), SleepEdit.parseRanges(c.getString(3)), c.getInt(4) == 1)
            }
        }
        return out
    }

    fun saveSleepEdit(edit: SleepEdit) {
        val cv = ContentValues()
        cv.put("night", edit.night)
        cv.put("start", edit.start)
        cv.put("end", edit.end)
        cv.put("ranges", edit.rangesText())
        cv.put("deleted", if (edit.deleted) 1 else 0)
        writableDatabase.insertWithOnConflict("sleep_edit", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    fun deleteSleepEdit(night: Long) {
        writableDatabase.delete("sleep_edit", "night = ?", arrayOf(night.toString()))
        changed()
    }

    fun sleepEditCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM sleep_edit", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun clearSleepEdits() {
        writableDatabase.delete("sleep_edit", null, null)
        changed()
    }

    // ------------------------------------------------------------------ PAI

    fun insertPai(samples: List<PaiSample>) = transaction { db ->
        val cv = ContentValues()
        for (p in samples) {
            cv.clear()
            cv.put("ts", p.timestamp)
            cv.put("today", p.today)
            cv.put("total", p.total)
            cv.put("low", p.lowMinutes)
            cv.put("moderate", p.moderateMinutes)
            cv.put("high", p.highMinutes)
            db.insertWithOnConflict("pai", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun pai(from: Long, to: Long): List<PaiSample> {
        val out = ArrayList<PaiSample>()
        readableDatabase.rawQuery(
            "SELECT ts, today, total, low, moderate, high FROM pai WHERE ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            while (c.moveToNext()) out += PaiSample(c.getLong(0), c.getFloat(1), c.getFloat(2), c.getInt(3), c.getInt(4), c.getInt(5))
        }
        return out
    }

    // ------------------------------------------------------------------ alarms

    data class AlarmRow(val alarm: StrapAlarm, val label: String, val pending: Boolean, val deleted: Boolean)

    fun alarms(): List<AlarmRow> {
        val out = ArrayList<AlarmRow>()
        readableDatabase.rawQuery(
            "SELECT position, enabled, smart, hour, minute, repetition, label, pending, deleted FROM alarm ORDER BY hour, minute",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += AlarmRow(
                    StrapAlarm(c.getInt(0), c.getInt(1) == 1, c.getInt(2) == 1, c.getInt(3), c.getInt(4), c.getInt(5)),
                    c.getString(6).orEmpty(),
                    c.getInt(7) == 1,
                    c.getInt(8) == 1,
                )
            }
        }
        return out
    }

    fun saveAlarm(alarm: StrapAlarm, label: String, pending: Boolean, deleted: Boolean = false) {
        val cv = ContentValues()
        cv.put("position", alarm.position)
        cv.put("enabled", if (alarm.enabled) 1 else 0)
        cv.put("smart", if (alarm.smart) 1 else 0)
        cv.put("hour", alarm.hour)
        cv.put("minute", alarm.minute)
        cv.put("repetition", alarm.repetition)
        cv.put("label", label)
        cv.put("pending", if (pending) 1 else 0)
        cv.put("deleted", if (deleted) 1 else 0)
        writableDatabase.insertWithOnConflict("alarm", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeAlarm(position: Int) {
        writableDatabase.delete("alarm", "position = ?", arrayOf(position.toString()))
    }

    /** Takes over the strap's alarms; local changes that were not sent yet stay. */
    fun mergeStrapAlarms(fromStrap: List<StrapAlarm>) = transaction { db ->
        val local = alarms().associateBy { it.alarm.position }
        val strapPositions = fromStrap.map { it.position }.toSet()
        for (a in fromStrap) {
            val row = local[a.position]
            if (row != null && row.pending) continue
            val cv = ContentValues()
            cv.put("position", a.position)
            cv.put("enabled", if (a.enabled) 1 else 0)
            cv.put("smart", if (a.smart) 1 else 0)
            cv.put("hour", a.hour)
            cv.put("minute", a.minute)
            cv.put("repetition", a.repetition)
            cv.put("label", row?.label.orEmpty())
            cv.put("pending", 0)
            cv.put("deleted", 0)
            db.insertWithOnConflict("alarm", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
        for ((pos, row) in local) {
            if (pos !in strapPositions && !row.pending) db.delete("alarm", "position = ?", arrayOf(pos.toString()))
        }
    }

    // ------------------------------------------------------------------ backup without closing the database

    /** Copies a consistent snapshot of the database to [out] while writers wait. */
    fun backupTo(out: java.io.OutputStream) {
        val db = writableDatabase
        db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        db.beginTransaction() // blocks other writers while the file is copied
        try {
            databaseFile().inputStream().use { it.copyTo(out) }
        } finally {
            db.endTransaction()
        }
    }

    /** Replaces all rows with the ones from the backup database [file]. */
    fun restoreFrom(file: java.io.File) {
        val db = writableDatabase
        db.execSQL("ATTACH DATABASE ? AS bak", arrayOf(file.path))
        try {
            db.beginTransaction()
            try {
                for (table in ALL_TABLES) {
                    val exists = db.rawQuery("SELECT name FROM bak.sqlite_master WHERE type='table' AND name=?", arrayOf(table))
                        .use { it.moveToFirst() }
                    if (!exists) continue
                    val cols = columns(db, "main", table).intersect(columns(db, "bak", table).toSet())
                    db.execSQL("DELETE FROM main.$table")
                    if (cols.isNotEmpty()) {
                        val list = cols.joinToString(",")
                        db.execSQL("INSERT OR REPLACE INTO main.$table ($list) SELECT $list FROM bak.$table")
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.execSQL("DETACH DATABASE bak")
            changed()
        }
    }

    private fun columns(db: SQLiteDatabase, schema: String, table: String): List<String> {
        val out = ArrayList<String>()
        db.rawQuery("PRAGMA $schema.table_info($table)", null).use { c ->
            while (c.moveToNext()) out += c.getString(1)
        }
        return out
    }

    fun latestValue(table: String): ValueSample? {
        require(table in VALUE_TABLES)
        return readableDatabase.rawQuery("SELECT ts, value FROM $table ORDER BY ts DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst()) ValueSample(c.getLong(0), c.getInt(1)) else null
        }
    }

    fun insertHr(ts: Long, bpm: Int) {
        val cv = ContentValues()
        cv.put("ts", ts)
        cv.put("bpm", bpm)
        writableDatabase.insertWithOnConflict("hr", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun hr(from: Long, to: Long): List<HrPoint> {
        val out = ArrayList<HrPoint>()
        readableDatabase.rawQuery("SELECT ts, bpm FROM hr WHERE ts >= ? AND ts < ? ORDER BY ts", arrayOf(from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) out += HrPoint(c.getLong(0), c.getInt(1))
        }
        return out
    }

    /** Writes every table from [from] (epoch seconds, 0 = everything) as CSV files into [zip]. */
    fun exportCsv(zip: java.util.zip.ZipOutputStream, from: Long) = HealthDbExport.csv(readableDatabase, zip, from)

    /** The database file for the backup; call [close] first so everything is written. */
    fun databaseFile(): java.io.File = context.getDatabasePath(databaseName)

    fun insertSleepRecords(records: List<SleepRecord>) = transaction { db ->
        val cv = ContentValues()
        for (r in records) {
            // Keep the newest version of a night.
            val known = db.rawQuery("SELECT updated FROM sleep_record WHERE day = ?", arrayOf(r.day.toString())).use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
            if (known != null && known > r.updated) continue
            cv.clear()
            cv.put("day", r.day)
            cv.put("updated", r.updated)
            cv.put("start", r.start)
            cv.put("end", r.end)
            if (r.score != null) cv.put("score", r.score) else cv.putNull("score")
            cv.put("segments", r.segments.joinToString(";") { "${it.start},${it.end},${it.stage.name}" })
            db.insertWithOnConflict("sleep_record", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** Sleep records whose night ended between [from] and [to] (epoch seconds). */
    fun sleepRecords(from: Long, to: Long): List<SleepRecord> {
        val out = ArrayList<SleepRecord>()
        readableDatabase.rawQuery(
            "SELECT day, updated, start, end, score, segments FROM sleep_record WHERE end > ? AND end <= ? ORDER BY end",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val segments = c.getString(5).split(";").mapNotNull { part ->
                    val f = part.split(",")
                    if (f.size != 3) return@mapNotNull null
                    val stage = SleepStage.entries.firstOrNull { it.name == f[2] } ?: return@mapNotNull null
                    SleepRecord.Segment(f[0].toLong(), f[1].toLong(), stage)
                }
                if (segments.isEmpty()) continue
                out += SleepRecord(
                    c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3),
                    if (c.isNull(4)) null else c.getInt(4), segments,
                )
            }
        }
        return out
    }

    fun insertValues(table: String, samples: List<ValueSample>) = transaction { db ->
        require(table in VALUE_TABLES)
        val cv = ContentValues()
        for (s in samples) {
            cv.clear()
            cv.put("ts", s.timestamp)
            cv.put("value", s.value)
            db.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun values(table: String, from: Long, to: Long): List<ValueSample> {
        require(table in VALUE_TABLES)
        val out = ArrayList<ValueSample>()
        readableDatabase.rawQuery(
            "SELECT ts, value FROM $table WHERE ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            while (c.moveToNext()) out += ValueSample(c.getLong(0), c.getInt(1))
        }
        return out
    }

    companion object {
        const val DB_NAME = "health.db"
        const val SPO2 = "spo2"
        const val RESTING_HR = "resting_hr"
        const val HRV = "hrv"
        const val TEMPERATURE = "temperature"
        const val RESPIRATION = "respiration"
        const val BATTERY = "battery"
        const val SDNN = "hrv_sdnn"
        private val VALUE_TABLES = setOf(SPO2, RESTING_HR, HRV, TEMPERATURE, RESPIRATION, BATTERY, SDNN)
        private val ALL_TABLES = listOf(
            "activity", "stress", "workout", "workout_hr", "workout_track", "sleep_record", SPO2, RESTING_HR, HRV, "hr",
            "pai", TEMPERATURE, RESPIRATION, BATTERY, "alarm", "sleep_edit", "sleep_tag", "event", "rhythm", SDNN, "odi", "feeling",
        )
    }

    fun insertActivity(samples: List<ActivitySample>) = transaction { db ->
        val cv = ContentValues()
        for (s in samples) {
            cv.clear()
            cv.put("ts", s.timestamp)
            cv.put("kind", s.rawKind)
            cv.put("intensity", s.intensity)
            cv.put("steps", s.steps)
            cv.put("hr", s.heartRate)
            cv.put("sleep", s.sleep)
            cv.put("deep", s.deepSleep)
            cv.put("rem", s.remSleep)
            db.insertWithOnConflict("activity", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun insertStress(samples: List<StressSample>) = transaction { db ->
        val cv = ContentValues()
        for (s in samples) {
            cv.clear()
            cv.put("ts", s.timestamp)
            cv.put("value", s.stress)
            db.insertWithOnConflict("stress", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** Totals of the minute values per hour – for long periods instead of millions of rows. */
    class HourTotals(
        val hour: Long, // epoch seconds of the hour
        val steps: Int,
        val hrMin: Int,
        val hrMax: Int,
        val hrSum: Long,
        val hrCount: Int,
        val activeMinutes: Int,
        /** Minutes per heart rate zone 1–5. */
        val zones: IntArray,
    )

    /** [zoneLower]: lower bpm of zones 1–5. */
    fun hourTotals(from: Long, to: Long, zoneLower: IntArray): List<HourTotals> {
        val z = zoneLower
        val zoneSql = (0 until 5).joinToString(", ") { i ->
            if (i < 4) "SUM(CASE WHEN hr >= ${z[i]} AND hr < ${z[i + 1]} THEN 1 ELSE 0 END)" else "SUM(CASE WHEN hr >= ${z[i]} THEN 1 ELSE 0 END)"
        }
        val sql = "SELECT ts / 3600, SUM(steps), MIN(CASE WHEN hr > 0 THEN hr END), MAX(CASE WHEN hr > 0 THEN hr END), " +
            "SUM(CASE WHEN hr > 0 THEN hr ELSE 0 END), SUM(CASE WHEN hr > 0 THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN steps >= 30 OR (kind != ${ActivitySample.KIND_SLEEP} AND intensity >= 40) THEN 1 ELSE 0 END), $zoneSql " +
            "FROM activity WHERE ts >= ? AND ts < ? GROUP BY ts / 3600 ORDER BY 1"
        val out = ArrayList<HourTotals>()
        readableDatabase.rawQuery(sql, arrayOf(from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) {
                out += HourTotals(
                    c.getLong(0) * 3600, c.getInt(1),
                    if (c.isNull(2)) 0 else c.getInt(2), if (c.isNull(3)) 0 else c.getInt(3),
                    c.getLong(4), c.getInt(5), c.getInt(6),
                    IntArray(5) { c.getInt(7 + it) },
                )
            }
        }
        return out
    }

    fun activity(from: Long, to: Long): List<ActivitySample> {
        val out = ArrayList<ActivitySample>()
        readableDatabase.rawQuery(
            "SELECT ts, kind, intensity, steps, hr, sleep, deep, rem FROM activity WHERE ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out += ActivitySample(
                    c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7)
                )
            }
        }
        return out
    }

    fun stress(from: Long, to: Long): List<StressSample> {
        val out = ArrayList<StressSample>()
        readableDatabase.rawQuery(
            "SELECT ts, value FROM stress WHERE ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            while (c.moveToNext()) out += StressSample(c.getLong(0), c.getInt(1))
        }
        return out
    }

    fun latestActivityTimestamp(): Long? =
        readableDatabase.rawQuery("SELECT MAX(ts) FROM activity", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    fun firstActivityTimestamp(): Long? =
        readableDatabase.rawQuery("SELECT MIN(ts) FROM activity", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    fun firstEventTimestamp(): Long? =
        readableDatabase.rawQuery("SELECT MIN(ts) FROM event", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    fun startWorkout(sport: String, start: Long): Long {
        val cv = ContentValues()
        cv.put("sport", sport)
        cv.put("start", start)
        cv.put("end", start)
        cv.put("avg_hr", 0)
        cv.put("max_hr", 0)
        cv.put("kcal", 0)
        cv.put("zones", "")
        return writableDatabase.insert("workout", null, cv)
    }

    fun addWorkoutHr(workout: Long, ts: Long, bpm: Int) {
        val cv = ContentValues()
        cv.put("workout", workout)
        cv.put("ts", ts)
        cv.put("bpm", bpm)
        writableDatabase.insert("workout_hr", null, cv)
    }

    fun finishWorkout(w: Workout) {
        val cv = ContentValues()
        cv.put("end", w.end)
        cv.put("avg_hr", w.avgHr)
        cv.put("max_hr", w.maxHr)
        cv.put("kcal", w.calories)
        cv.put("zones", w.zoneSeconds.joinToString(","))
        cv.put("distance", w.distance)
        writableDatabase.update("workout", cv, "id = ?", arrayOf(w.id.toString()))
    }

    /** A position of the running workout's route. */
    fun addTrackPoint(workout: Long, p: TrackPoint) {
        val cv = ContentValues()
        cv.put("workout", workout)
        cv.put("ts", p.timestamp)
        cv.put("lat", p.lat)
        cv.put("lon", p.lon)
        // Locals: a property from the core module cannot be smart-cast here.
        val altitude = p.altitude
        val accuracy = p.accuracy
        if (altitude != null) cv.put("alt", altitude) else cv.putNull("alt")
        if (accuracy != null) cv.put("acc", accuracy) else cv.putNull("acc")
        writableDatabase.insert("workout_track", null, cv)
    }

    /** The route of a workout in the order it was recorded, empty without GPS. */
    fun track(workout: Long): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        readableDatabase.rawQuery(
            "SELECT ts, lat, lon, alt, acc FROM workout_track WHERE workout = ? ORDER BY ts",
            arrayOf(workout.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out += TrackPoint(
                    c.getLong(0), c.getDouble(1), c.getDouble(2),
                    if (c.isNull(3)) null else c.getDouble(3),
                    if (c.isNull(4)) null else c.getFloat(4),
                )
            }
        }
        return out
    }

    fun setWorkoutHrr(id: Long, drop: Int) {
        val cv = ContentValues()
        cv.put("hrr", drop)
        writableDatabase.update("workout", cv, "id = ?", arrayOf(id.toString()))
    }

    /** What the user did or took during a recording, and a free note. */
    fun setWorkoutNote(id: Long, tags: List<String>, note: String) {
        val cv = ContentValues()
        cv.put("tags", tags.joinToString(","))
        cv.put("note", note)
        writableDatabase.update("workout", cv, "id = ?", arrayOf(id.toString()))
    }

    /** Tags and note of a workout, also while it is still running. */
    fun workoutNote(id: Long): Pair<List<String>, String> =
        readableDatabase.rawQuery("SELECT tags, note FROM workout WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0).orEmpty().split(",").filter { it.isNotBlank() } to c.getString(1).orEmpty() else emptyList<String>() to ""
        }

    fun deleteWorkout(id: Long) = transaction { db ->
        db.delete("workout_hr", "workout = ?", arrayOf(id.toString()))
        db.delete("workout_track", "workout = ?", arrayOf(id.toString()))
        db.delete("workout", "id = ?", arrayOf(id.toString()))
    }

    fun workouts(): List<Workout> {
        val out = ArrayList<Workout>()
        readableDatabase.rawQuery(
            "SELECT id, sport, start, end, avg_hr, max_hr, kcal, zones, tags, note, hrr, distance FROM workout WHERE end > start ORDER BY start DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += Workout(
                    id = c.getLong(0),
                    sport = c.getString(1),
                    start = c.getLong(2),
                    end = c.getLong(3),
                    avgHr = c.getInt(4),
                    maxHr = c.getInt(5),
                    calories = c.getInt(6),
                    zoneSeconds = c.getString(7).split(",").mapNotNull { it.toIntOrNull() },
                    tags = c.getString(8).orEmpty().split(",").filter { it.isNotBlank() },
                    note = c.getString(9).orEmpty(),
                    hrr = if (c.isNull(10)) null else c.getInt(10),
                    distance = if (c.isNull(11)) 0 else c.getInt(11),
                )
            }
        }
        return out
    }

    fun workoutHr(id: Long): List<HrPoint> {
        val out = ArrayList<HrPoint>()
        readableDatabase.rawQuery(
            "SELECT ts, bpm FROM workout_hr WHERE workout = ? ORDER BY ts",
            arrayOf(id.toString()),
        ).use { c ->
            while (c.moveToNext()) out += HrPoint(c.getLong(0), c.getInt(1))
        }
        return out
    }

    fun exportActivityCsv(): String = HealthDbExport.activityCsv(readableDatabase)

    /** Deletes all health data and workouts. */
    fun clearAll() = transaction { db ->
        ALL_TABLES.filter { it != "alarm" }.forEach { db.delete(it, null, null) }
    }

    private inline fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            changed()
        }
    }

    /**
     * Grows with every change of the stored data (all inserts run in [transaction], plus sleep
     * corrections and restores); caches of computed values, like the nights, compare it.
     */
    @Volatile
    var generation = 0L
        private set

    private fun changed() {
        generation++
    }
}
