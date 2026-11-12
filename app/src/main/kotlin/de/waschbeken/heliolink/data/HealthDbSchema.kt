package de.waschbeken.heliolink.data

import android.database.sqlite.SQLiteDatabase

/**
 * The tables of the health database and how an older one is brought up to date. The app updates
 * itself over installed versions, so every change is a new version with its own step here –
 * never drop and recreate, or the data would be gone.
 */
internal object HealthDbSchema {
    const val VERSION = 10

    fun create(db: SQLiteDatabase) {
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
        upgrade(db, 1)
    }

    /** Every step from [oldVersion] on, in order. */
    fun upgrade(db: SQLiteDatabase, oldVersion: Int) {
        if (oldVersion < 2) v2(db)
        if (oldVersion < 3) v3(db)
        if (oldVersion < 4) v4(db)
        if (oldVersion < 5) v5(db)
        if (oldVersion < 6) v6(db)
        if (oldVersion < 7) v7(db)
        if (oldVersion < 8) v8(db)
        if (oldVersion < 9) v9(db)
        if (oldVersion < 10) v10(db)
    }

    /** Version 2: sleep records with stages, SpO2, resting heart rate and HRV. */
    private fun v2(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sleep_record (day INTEGER PRIMARY KEY, updated INTEGER, start INTEGER, " +
                "end INTEGER, score INTEGER, segments TEXT)"
        )
        for (t in listOf("spo2", "resting_hr", "hrv")) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $t (ts INTEGER PRIMARY KEY, value INTEGER)")
        }
    }

    /** Version 3: heart rate values the app records itself (more often than once a minute). */
    private fun v3(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS hr (ts INTEGER PRIMARY KEY, bpm INTEGER)")
    }

    /** Version 4: PAI, skin temperature, breathing rate, battery history and alarms. */
    private fun v4(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS pai (ts INTEGER PRIMARY KEY, today REAL, total REAL, low INTEGER, " +
                "moderate INTEGER, high INTEGER)"
        )
        for (t in listOf(HealthDb.TEMPERATURE, HealthDb.RESPIRATION, HealthDb.BATTERY)) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $t (ts INTEGER PRIMARY KEY, value INTEGER)")
        }
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS alarm (position INTEGER PRIMARY KEY, enabled INTEGER, smart INTEGER, " +
                "hour INTEGER, minute INTEGER, repetition INTEGER, label TEXT, pending INTEGER, deleted INTEGER)"
        )
    }

    /** Version 5: sleep corrections by hand. */
    private fun v5(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sleep_edit (night INTEGER PRIMARY KEY, start INTEGER, end INTEGER, ranges TEXT, deleted INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS sleep_tag (night INTEGER, tag TEXT, PRIMARY KEY (night, tag))")
    }

    /** Version 6: notes on recordings and quick entries (caffeine, nicotine, …) at any time. */
    private fun v6(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE workout ADD COLUMN tags TEXT")
        db.execSQL("ALTER TABLE workout ADD COLUMN note TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS event (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, tag TEXT, note TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS event_ts ON event (ts)")
    }

    /** Version 7: heart rhythm checks from the beat intervals while the pulse is streamed, heart rate recovery after workouts. */
    private fun v7(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS rhythm (ts INTEGER PRIMARY KEY, beats INTEGER, hr INTEGER, nrmssd INTEGER, irregular INTEGER)")
        db.execSQL("ALTER TABLE workout ADD COLUMN hrr INTEGER")
    }

    /** Version 8: the strap's SDNN values and its nightly oxygen drop summary. */
    private fun v8(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS ${HealthDb.SDNN} (ts INTEGER PRIMARY KEY, value INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS odi (day INTEGER PRIMARY KEY, valid INTEGER, odi INTEGER, score INTEGER, drops INTEGER)")
    }

    /** Version 9: how the user feels each day (1–5) with a note. */
    private fun v9(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS feeling (day INTEGER PRIMARY KEY, score INTEGER, note TEXT)")
    }

    /** Version 10: the route of a workout from the phone's GPS and its length. */
    private fun v10(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE workout ADD COLUMN distance INTEGER")
        db.execSQL("CREATE TABLE IF NOT EXISTS workout_track (workout INTEGER, ts INTEGER, lat REAL, lon REAL, alt REAL, acc REAL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS workout_track_idx ON workout_track (workout, ts)")
    }
}
