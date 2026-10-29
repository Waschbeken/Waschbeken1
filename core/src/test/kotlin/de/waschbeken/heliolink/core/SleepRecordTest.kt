package de.waschbeken.heliolink.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SleepRecordTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val day = LocalDate.of(2026, 10, 29).atStartOfDay(zone).toEpochSecond()

    /** Builds a record like the strap does: 23:33 light, deep, REM, awake until 06:41. */
    private fun record(updated: Long, score: Int = 75): ByteArray {
        val b = ByteBuffer.allocate(SleepRecordParser.RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, updated.toInt())
        b.putInt(4, day.toInt())
        b.put(8, 1).put(9, 1)
        b.put(22, score.toByte())
        val segments = listOf(Triple(1413, 1499, 4), Triple(1500, 1589, 5), Triple(1590, 1830, 8), Triple(1831, 1841, 7))
        b.putShort(84, segments.size.toShort())
        segments.forEachIndexed { i, (from, to, stage) ->
            val o = 86 + i * 5
            b.putShort(o, from.toShort())
            b.putShort(o + 2, to.toShort())
            b.put(o + 4, stage.toByte())
        }
        return b.array()
    }

    @Test
    fun parsesStagesAndTimes() {
        val r = assertNotNull(SleepRecordParser.parse(record(day + 7 * 3600)))
        assertEquals(LocalDate.of(2026, 10, 28).atTime(23, 33).atZone(zone).toEpochSecond(), r.start)
        assertEquals(LocalDate.of(2026, 10, 29).atTime(6, 42).atZone(zone).toEpochSecond(), r.end)
        assertEquals(75, r.score)
        val session = r.toSession()
        assertEquals(87, session.minutes(SleepStage.LIGHT))
        assertEquals(90, session.minutes(SleepStage.DEEP))
        assertEquals(241, session.minutes(SleepStage.REM))
        assertEquals(11, session.minutes(SleepStage.AWAKE))
        assertEquals(75, session.score)
    }

    @Test
    fun keepsNewestRecordPerNight() {
        val data = record(day + 6 * 3600, score = 60) + record(day + 7 * 3600, score = 80)
        val all = SleepRecordParser.parseAll(data)
        assertEquals(1, all.size)
        assertEquals(80, all[0].score)
        assertTrue(SleepRecordParser.looksLikeRecords(data, 0, day + 8 * 3600))
    }

    @Test
    fun probeFindsSleepCode() {
        val key = ByteArray(16)
        val events = ArrayList<StrapEvent>()
        val writes = ArrayList<GattWrite>()
        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(day + 8 * 3600), zone)
        val p = StrapProtocol(key, { writes += it }, { events += it }, { now })
        val cursor = object : SyncCursor {
            override fun get(type: FetchType): Long? = null
            override fun set(type: FetchType, epochMillis: Long) = Unit
        }
        p.probeSleepCode(cursor, listOf(0x30, 0x31))
        // 0x30 is rejected
        assertEquals(0x30, writes.last().value[1].toInt())
        p.onFetchControlNotification(byteArrayOf(0x10, 0x01, 0x02))
        assertEquals(0x31, writes.last().value[1].toInt())
        val data = record(day + 7 * 3600)
        p.onFetchControlNotification(byteArrayOf(0x10, 0x01, 0x01) + le32(data.size) + byteArrayOf(0xea.toByte(), 0x07, 10, 29, 0, 0, 0, 4))
        data.toList().chunked(200).forEachIndexed { i, chunk ->
            p.onFetchDataNotification(byteArrayOf(i.toByte()) + chunk.toByteArray())
        }
        p.onFetchControlNotification(byteArrayOf(0x10, 0x02, 0x01) + le32(crc32(data)))
        val probed = events.filterIsInstance<StrapEvent.SleepCodeProbed>().single()
        assertEquals(0x31, probed.code)
        assertEquals(1, events.filterIsInstance<StrapEvent.SleepData>().single().records.size)
        // stored, but kept on the strap like all fetched data
        assertEquals(listOf<Byte>(0x03, 0x09), writes.last().value.toList())
    }
}
