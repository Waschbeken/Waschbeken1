package de.waschbeken.heliolink.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A night as summarised by the strap itself, with sleep stages. The Helio Strap does not put stages
 * into the per-minute activity samples, they only come in these records.
 */
data class SleepRecord(
    /** Local midnight of the morning the night ended, as epoch seconds; identifies the night. */
    val day: Long,
    /** Time the record was written by the strap (epoch seconds). */
    val updated: Long,
    val start: Long,
    val end: Long,
    val score: Int?,
    val segments: List<Segment>,
) {
    data class Segment(val start: Long, val end: Long, val stage: SleepStage)

    fun toSession(heartRates: List<ActivitySample> = emptyList()): SleepSession {
        val minutes = ArrayList<Pair<Long, SleepStage>>()
        for (s in segments) {
            var t = s.start
            while (t < s.end) {
                minutes += t to s.stage
                t += 60
            }
        }
        val hr = heartRates.filter { it.timestamp in start until end && it.heartRate > 0 }.map { it.heartRate }
        return SleepSession(
            start = start,
            end = end,
            stages = minutes,
            avgHeartRate = if (hr.isEmpty()) null else hr.average().toInt(),
            minHeartRate = hr.minOrNull(),
            strapScore = score,
        )
    }
}

/**
 * Parser for the strap's sleep records (594 bytes each).
 *
 * Layout (little endian): updated(4) day(4) … score at 22 … segment count(2) at 84, then segments of
 * 5 bytes: start minute(2) end minute(2) stage(1). Minutes count from the midnight *before* [SleepRecord.day],
 * so 1413 is 23:33 the evening before and 1841 is 06:41 in the morning. Stage codes: 4 light, 5 deep,
 * 7 awake, 8 REM (deep sleep clusters in the first half of the night, REM in the second).
 */
object SleepRecordParser {
    const val RECORD_SIZE = 594
    private const val COUNT_OFFSET = 84
    private const val SCORE_OFFSET = 22

    fun stageOf(code: Int): SleepStage? = when (code) {
        4 -> SleepStage.LIGHT
        5 -> SleepStage.DEEP
        7 -> SleepStage.AWAKE
        8 -> SleepStage.REM
        else -> null
    }

    fun parse(record: ByteArray, offset: Int = 0): SleepRecord? {
        if (offset < 0) return null
        if (record.size - offset < COUNT_OFFSET + 2) return null
        val buf = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
        val updated = buf.getInt(offset).toLong() and 0xffffffffL
        val day = buf.getInt(offset + 4).toLong() and 0xffffffffL
        val count = buf.getShort(offset + COUNT_OFFSET).toInt() and 0xffff
        if (count == 0 || count > 200 || record.size - offset < COUNT_OFFSET + 2 + count * 5) return null
        val base = day - 24 * 3600
        val segments = ArrayList<SleepRecord.Segment>(count)
        for (k in 0 until count) {
            val o = offset + COUNT_OFFSET + 2 + k * 5
            val from = buf.getShort(o).toInt() and 0xffff
            val to = buf.getShort(o + 2).toInt() and 0xffff
            val stage = stageOf(record[o + 4].toInt() and 0xff) ?: continue
            if (to < from) return null
            segments += SleepRecord.Segment(base + from * 60L, base + (to + 1) * 60L, stage)
        }
        if (segments.isEmpty()) return null
        val score = (record[offset + SCORE_OFFSET].toInt() and 0xff).takeIf { it in 1..100 }
        return SleepRecord(day, updated, segments.first().start, segments.last().end, score, segments)
    }

    /** Parses a buffer of consecutive records; returns the newest record per night. */
    fun parseAll(data: ByteArray, offset: Int = 0): List<SleepRecord> {
        val result = HashMap<Long, SleepRecord>()
        var o = offset
        while (data.size - o >= COUNT_OFFSET + 2) {
            parse(data.copyOfRange(o, minOf(data.size, o + RECORD_SIZE)))?.let { r ->
                val known = result[r.day]
                if (known == null || r.updated >= known.updated) result[r.day] = r
            }
            o += RECORD_SIZE
        }
        return result.values.sortedBy { it.day }
    }

    /**
     * Finds where the sleep records start: two recent timestamps (updated ≥ day - 1 day) within the
     * first bytes. Returns null if the data does not look like sleep records.
     */
    fun findOffset(data: ByteArray, nowSeconds: Long): Int? {
        if (data.size < COUNT_OFFSET + 2) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val range = (nowSeconds - 400L * 86400)..(nowSeconds + 2L * 86400)
        for (offset in 0..8) {
            if (data.size - offset < COUNT_OFFSET + 2) break
            val updated = buf.getInt(offset).toLong() and 0xffffffffL
            val day = buf.getInt(offset + 4).toLong() and 0xffffffffL
            if (updated in range && day in range && updated >= day - 86400 && parse(data, offset) != null) return offset
        }
        return null
    }

    /** Whether [data] plausibly starts with sleep records (two recent timestamps) at [offset]. */
    fun looksLikeRecords(data: ByteArray, offset: Int, nowSeconds: Long): Boolean {
        if (data.size - offset < RECORD_SIZE) return false
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val updated = buf.getInt(offset).toLong() and 0xffffffffL
        val day = buf.getInt(offset + 4).toLong() and 0xffffffffL
        val range = (nowSeconds - 400L * 86400)..(nowSeconds + 2L * 86400)
        return updated in range && day in range && (data.size - offset) % RECORD_SIZE == 0
    }
}
