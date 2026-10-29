package de.waschbeken.heliolink.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import de.waschbeken.heliolink.core.ActivitySample
import de.waschbeken.heliolink.core.StressSample

data class Workout(
    val id: Long,
    val sport: String,
    val start: Long, // epoch seconds
    val end: Long,
    val avgHr: Int,
    val maxHr: Int,
    val calories: Int,
    val zoneSeconds: List<Int>,
)

data class HrPoint(val timestamp: Long, val bpm: Int)

class HealthDb(context: Context) : SQLiteOpenHelper(context, "health.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE activity (ts INTEGER PRIMARY KEY, kind INTEGER, intensity INTEGER, steps INTEGER, " +
                "hr INTEGER, sleep INTEGER, deep INTEGER, rem INTEGER)"
        )
        db.execSQL("CREATE TABLE stress (ts INTEGER PRIMARY KEY, value INTEGER)")
        db.execSQL(
            "CREATE TABLE workout (id INTEGER PRIMARY KEY AUTOINCREMENT, sport TEXT, start INTEGER, end INTEGER, " +
                "avg_hr INTEGER, max_hr INTEGER, kcal INTEGER, zones TEXT)"
        )
        db.execSQL("CREATE TABLE workout_hr (workout INTEGER, ts INTEGER, bpm INTEGER)")
        db.execSQL("CREATE INDEX workout_hr_idx ON workout_hr (workout, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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
        writableDatabase.update("workout", cv, "id = ?", arrayOf(w.id.toString()))
    }

    fun deleteWorkout(id: Long) = transaction { db ->
        db.delete("workout_hr", "workout = ?", arrayOf(id.toString()))
        db.delete("workout", "id = ?", arrayOf(id.toString()))
    }

    fun workouts(): List<Workout> {
        val out = ArrayList<Workout>()
        readableDatabase.rawQuery(
            "SELECT id, sport, start, end, avg_hr, max_hr, kcal, zones FROM workout WHERE end > start ORDER BY start DESC",
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

    fun exportActivityCsv(): String {
        val sb = StringBuilder("timestamp,kind,intensity,steps,heart_rate,sleep,deep_sleep,rem_sleep\n")
        readableDatabase.rawQuery("SELECT ts, kind, intensity, steps, hr, sleep, deep, rem FROM activity ORDER BY ts", null).use { c ->
            while (c.moveToNext()) {
                for (i in 0 until 8) {
                    if (i > 0) sb.append(',')
                    sb.append(c.getLong(i))
                }
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    /** Deletes all health data and workouts. */
    fun clearAll() = transaction { db ->
        listOf("activity", "stress", "workout", "workout_hr").forEach { db.delete(it, null, null) }
    }

    private inline fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
