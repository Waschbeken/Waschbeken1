package de.waschbeken.heliolink.data

import android.database.sqlite.SQLiteDatabase

/** The data as CSV files for other programs. The GPS routes stay out (they show where you live); a workout gives its own as GPX or TCX. */
internal object HealthDbExport {
    /** Writes every table from [from] (epoch seconds, 0 = everything) as CSV files into [zip]. */
    fun csv(db: SQLiteDatabase, zip: java.util.zip.ZipOutputStream, from: Long) {
        val tables = listOf(
            Triple("aktivitaet.csv", "SELECT ts, kind, intensity, steps, hr, sleep, deep, rem FROM activity WHERE ts >= ? ORDER BY ts",
                "zeit,unix,typ,intensitaet,schritte,puls,schlaf,tiefschlaf,rem"),
            Triple("puls_app.csv", "SELECT ts, bpm FROM hr WHERE ts >= ? ORDER BY ts", "zeit,unix,puls"),
            Triple("stress.csv", "SELECT ts, value FROM stress WHERE ts >= ? ORDER BY ts", "zeit,unix,stress"),
            Triple("spo2.csv", "SELECT ts, value FROM spo2 WHERE ts >= ? ORDER BY ts", "zeit,unix,spo2"),
            Triple("ruhepuls.csv", "SELECT ts, value FROM resting_hr WHERE ts >= ? ORDER BY ts", "zeit,unix,ruhepuls"),
            Triple("hrv.csv", "SELECT ts, value FROM hrv WHERE ts >= ? ORDER BY ts", "zeit,unix,hrv_ms"),
            Triple("schlaf.csv", "SELECT start, end, score, segments FROM sleep_record WHERE end >= ? ORDER BY start", "zeit,unix_start,unix_ende,score,phasen"),
            // The routes themselves stay out of this export (they show where you live); a workout gives its own as GPX or TCX.
            Triple("trainings.csv", "SELECT start, end, sport, avg_hr, max_hr, kcal, zones, id, tags, note, distance FROM workout WHERE end > start AND start >= ? ORDER BY start",
                "zeit,unix_start,unix_ende,sport,puls_avg,puls_max,kcal,zonen_sekunden,id,stichwoerter,notiz,strecke_meter"),
            Triple("eintraege.csv", "SELECT ts, tag, note FROM event WHERE ts >= ? ORDER BY ts", "zeit,unix,eintrag,notiz"),
            Triple("pai.csv", "SELECT ts, today, total, low, moderate, high FROM pai WHERE ts >= ? ORDER BY ts",
                "zeit,unix,pai_heute,pai_gesamt,min_leicht,min_mittel,min_hoch"),
            Triple("hauttemperatur.csv", "SELECT ts, value FROM temperature WHERE ts >= ? ORDER BY ts", "zeit,unix,temperatur_hundertstel_grad"),
            Triple("atemfrequenz.csv", "SELECT ts, value FROM respiration WHERE ts >= ? ORDER BY ts", "zeit,unix,atemzuege_pro_minute"),
            Triple("akku.csv", "SELECT ts, value FROM battery WHERE ts >= ? ORDER BY ts", "zeit,unix,akku_prozent"),
            Triple("training_puls.csv", "SELECT ts, bpm, workout FROM workout_hr WHERE ts >= ? ORDER BY ts", "zeit,unix,puls,training_id"),
        )
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val zone = java.time.ZoneId.systemDefault()
        for ((name, sql, header) in tables) {
            zip.putNextEntry(java.util.zip.ZipEntry(name))
            val w = zip.bufferedWriter()
            w.write(header)
            w.write("\n")
            db.rawQuery(sql, arrayOf(from.toString())).use { c ->
                while (c.moveToNext()) {
                    w.write(java.time.Instant.ofEpochSecond(c.getLong(0)).atZone(zone).format(fmt))
                    for (i in 0 until c.columnCount) {
                        w.write(",")
                        val v = c.getString(i).orEmpty()
                        val clean = v.replace('\n', ' ')
                        w.write(if (clean.contains(',') || clean.contains(';') || clean.contains('"')) "\"" + clean.replace("\"", "\"\"") + "\"" else clean)
                    }
                    w.write("\n")
                }
            }
            w.flush()
            zip.closeEntry()
        }
    }

    /** The minute samples as one CSV, the format of the first versions. */
    fun activityCsv(db: SQLiteDatabase): String {
        val sb = StringBuilder("timestamp,kind,intensity,steps,heart_rate,sleep,deep_sleep,rem_sleep\n")
        db.rawQuery("SELECT ts, kind, intensity, steps, hr, sleep, deep, rem FROM activity ORDER BY ts", null).use { c ->
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
}
