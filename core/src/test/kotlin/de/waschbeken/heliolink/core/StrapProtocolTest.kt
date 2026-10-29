package de.waschbeken.heliolink.core

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StrapProtocolTest {
    private val key = parseHex("0x00112233445566778899aabbccddeeff")!!
    private val now = ZonedDateTime.of(2026, 10, 29, 8, 30, 0, 0, ZoneId.of("Europe/Berlin"))

    private class Harness(key: ByteArray, now: ZonedDateTime, accept: Boolean = true) {
        val strap = FakeStrap(key).apply { acceptKey = accept }
        val events = ArrayList<StrapEvent>()
        val writes = ArrayList<GattWrite>()
        val protocol = StrapProtocol(key, { writes += it; strap.onWrite(it) }, { events += it }, { now })

        fun pump() {
            while (strap.outgoing.isNotEmpty()) protocol.onChunkedNotification(strap.outgoing.removeFirst())
        }
    }

    @Test
    fun authenticatesAndInitialises() {
        val h = Harness(key, now)
        h.protocol.start()
        h.pump()
        assertTrue(h.protocol.isAuthenticated)
        assertTrue(h.protocol.isReady)
        assertTrue(h.events.any { it is StrapEvent.Battery && it.level == 77 })
        val ready = h.events.filterIsInstance<StrapEvent.Ready>().single()
        assertTrue(HealthConfigCodec.ENDPOINT in ready.encryptedEndpoints)
        // The health settings request goes to an encrypted endpoint and must decrypt on the strap.
        val config = h.strap.received.last { it.first == HealthConfigCodec.ENDPOINT }
        assertEquals(HealthConfigCodec.requests().last().toList(), config.second.toList())
        assertTrue(h.writes.any { it.target == WriteTarget.CURRENT_TIME })
    }

    @Test
    fun reportsRejectedKey() {
        val h = Harness(key, now, accept = false)
        h.protocol.start()
        h.pump()
        assertIs<StrapEvent.AuthFailed>(h.events.last())
    }

    @Test
    fun encryptedMessagesFromStrapAreDecoded() {
        val h = Harness(key, now)
        h.protocol.start()
        h.pump()
        val payload = byteArrayOf(0x04, 0x01, 0x08, 0x02, 0x00, 0x02, 0x05, 0x0b, 0x01, 0x01, 0x10, 0x0a)
        h.strap.send(HealthConfigCodec.ENDPOINT, payload, encrypt = true)
        h.pump()
        val settings = h.events.filterIsInstance<StrapEvent.HealthSettings>().single().settings
        assertEquals(true, settings.bool(HealthSetting.HEART_RATE_BROADCAST))
        assertEquals(10, settings.values[HealthSetting.HEART_RATE_INTERVAL])
    }

    @Test
    fun largeMessagesAreSplitAndReassembled() {
        val enc = ChunkedEncoder().apply { mtu = 23 }
        val dec = ChunkedDecoder()
        val payload = ByteArray(300) { it.toByte() }
        val chunks = enc.encode(0x1234, payload, false)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.size <= 20 })
        val messages = chunks.mapNotNull { dec.decode(it).message }
        assertEquals(1, messages.size)
        assertEquals(0x1234, messages[0].endpoint)
        assertEquals(payload.toList(), messages[0].payload.toList())
    }

    @Test
    fun fetchesActivityData() {
        val h = Harness(key, now)
        val cursor = object : SyncCursor {
            val map = HashMap<FetchType, Long>()
            override fun get(type: FetchType) = map[type]
            override fun set(type: FetchType, epochMillis: Long) { map[type] = epochMillis }
        }
        h.protocol.startSync(cursor, listOf(FetchType.ACTIVITY))
        val startCmd = h.writes.last()
        assertEquals(WriteTarget.FETCH_CONTROL, startCmd.target)
        assertEquals(0x01, startCmd.value[0].toInt())

        // Strap: 16 bytes = 2 samples starting 2026-10-29 06:00 local, tz +1h (4 quarters)
        val response = byteArrayOf(0x10, 0x01, 0x01) + le32(16) + byteArrayOf(*le16(2026), 10, 29, 6, 0, 0, 4)
        h.protocol.onFetchControlNotification(response)
        assertEquals(0x02, h.writes.last().value[0].toInt())
        h.protocol.onFetchDataNotification(byteArrayOf(0, 120, 10, 0, 55, 0, 30, 1, 60))
        h.protocol.onFetchDataNotification(byteArrayOf(1, 1, 50, 20, 80, 0, 0, 0, 0))
        h.protocol.onFetchControlNotification(byteArrayOf(0x10, 0x02, 0x01) + le32(0))
        // the data stays on the strap, HelioLink keeps its own cursor
        assertEquals(listOf<Byte>(0x03, 0x09), h.writes.last().value.toList())

        val samples = h.events.filterIsInstance<StrapEvent.ActivityData>().single().samples
        assertEquals(2, samples.size)
        assertEquals(ZonedDateTime.of(2026, 10, 29, 5, 0, 0, 0, ZoneId.of("UTC")).toEpochSecond(), samples[0].timestamp)
        assertEquals(SleepStage.REM, samples[0].sleepStage)
        assertEquals(80, samples[1].heartRate)
        assertEquals(20, samples[1].steps)

        h.protocol.onFetchControlNotification(byteArrayOf(0x10, 0x03, 0x01))
        // Last sample was 05:01 UTC, still far from "now" (07:30 UTC) -> another round is started.
        assertEquals(0x01, h.writes.last().value[0].toInt())
        assertEquals(samples[1].timestamp * 1000 + 60_000, cursor.map[FetchType.ACTIVITY])
    }

    @Test
    fun fetchesHrv() {
        val h = Harness(key, now)
        val cursor = object : SyncCursor {
            val map = HashMap<FetchType, Long>()
            override fun get(type: FetchType) = map[type]
            override fun set(type: FetchType, epochMillis: Long) { map[type] = epochMillis }
        }
        h.protocol.startSync(cursor, listOf(FetchType.HRV))
        assertEquals(0x49, h.writes.last().value[1].toInt() and 0xff)
        val t = ZonedDateTime.of(2026, 10, 29, 2, 0, 0, 0, ZoneId.of("UTC")).toEpochSecond().toInt()
        // records: timestamp(4) tz(1) hrv(1); the last one is empty (0xff)
        val data = le32(t) + byteArrayOf(4, 48) + le32(t + 120) + byteArrayOf(4, 52) + le32(t + 240) + byteArrayOf(4, -1)
        h.protocol.onFetchControlNotification(byteArrayOf(0x10, 0x01, 0x01) + le32(data.size) + byteArrayOf(*le16(2026), 10, 29, 3, 0, 0, 4))
        h.protocol.onFetchDataNotification(byteArrayOf(0) + data)
        h.protocol.onFetchControlNotification(byteArrayOf(0x10, 0x02, 0x01) + le32(crc32(data)))
        val samples = h.events.filterIsInstance<StrapEvent.HrvData>().single().samples
        assertEquals(listOf(48, 52), samples.map { it.value })
        assertEquals(t.toLong() + 120, samples[1].timestamp)
    }

    /** Runs one fetch of [type] that answers with [data] and returns the events. */
    private fun fetchOnce(type: FetchType, data: ByteArray): List<StrapEvent> {
        val h = Harness(key, now)
        val cursor = object : SyncCursor {
            override fun get(type: FetchType): Long? = null
            override fun set(type: FetchType, epochMillis: Long) {}
        }
        h.protocol.startSync(cursor, listOf(type))
        assertEquals(type.code, h.writes.last().value[1].toInt() and 0xff)
        h.protocol.onFetchControlNotification(byteArrayOf(0x10, 0x01, 0x01) + le32(data.size) + byteArrayOf(*le16(2026), 10, 29, 3, 0, 0, 4))
        data.toList().chunked(200).forEachIndexed { i, part -> h.protocol.onFetchDataNotification(byteArrayOf(i.toByte()) + part.toByteArray()) }
        h.protocol.onFetchControlNotification(byteArrayOf(0x10, 0x02, 0x01) + le32(crc32(data)))
        return h.events
    }

    @Test
    fun fetchesSdnn() {
        val t = 1_793_000_000
        val data = le32(t) + byteArrayOf(4) + le32(4_250) + le32(t + 3600) + byteArrayOf(4) + le32(-1)
        val samples = fetchOnce(FetchType.HRV_SDNN, data).filterIsInstance<StrapEvent.SdnnData>().single().samples
        assertEquals(listOf(43), samples.map { it.value })
    }

    @Test
    fun fetchesSleepSpo2() {
        val t = 1_793_000_000
        val rec = { ts: Int, v: Int -> le32(ts) + byteArrayOf(v.toByte(), 60) + ByteArray(24) }
        val data = byteArrayOf(2) + rec(t, 95) + rec(t + 300, 91)
        val samples = fetchOnce(FetchType.SPO2_SLEEP, data).filterIsInstance<StrapEvent.Spo2Data>().single().samples
        assertEquals(listOf(95, 91), samples.map { it.value })
    }

    @Test
    fun fetchesOxygenDropSummary() {
        val day = 1_793_000_000
        val night = ByteArray(2896).also { r ->
            le32(day).copyInto(r, 0)
            le32(25_000).copyInto(r, 4)
            le32(320).copyInto(r, 8)
            r[12] = 88
            r[15] = 12
        }
        val empty = ByteArray(2896).also { le32(day - 86_400).copyInto(it, 0) }
        val nights = fetchOnce(FetchType.ODI, empty + night).filterIsInstance<StrapEvent.OdiData>().single().nights
        assertEquals(2, nights.size)
        assertEquals(0, nights[0].drops)
        assertEquals(null, nights[0].score)
        assertEquals(12, nights[1].drops)
        assertEquals(88, nights[1].score)
        assertEquals(320L, nights[1].odi)
    }

    @Test
    fun emptyStartTimeDoesNotCrash() {
        val h = Harness(key, now)
        val cursor = object : SyncCursor {
            override fun get(type: FetchType): Long? = null
            override fun set(type: FetchType, epochMillis: Long) {}
        }
        h.protocol.startSync(cursor, listOf(FetchType.STRESS, FetchType.ACTIVITY))
        // No data: the strap answers with zeros instead of a time (month 0).
        h.protocol.onFetchControlNotification(byteArrayOf(0x10, 0x01, 0x01) + le32(0) + ByteArray(8))
        assertEquals(listOf<Byte>(0x03, 0x09), h.writes.last().value.toList())
        h.protocol.onFetchControlNotification(byteArrayOf(0x10, 0x03, 0x01))
        // Data but an invalid time: skipped and kept on the strap.
        assertEquals(0x01, h.writes.last().value[0].toInt())
        h.protocol.onFetchControlNotification(byteArrayOf(0x10, 0x01, 0x01) + le32(16) + byteArrayOf(*le16(2026), 13, 40, 0, 0, 0, 4))
        assertEquals(listOf<Byte>(0x03, 0x09), h.writes.last().value.toList())
        assertTrue(h.events.any { it is StrapEvent.Log && "ungültige Startzeit" in it.message })
    }

    @Test
    fun decodeRejectsInvalidTimes() {
        val zone = ZoneId.of("Europe/Berlin")
        assertEquals(null, TimeCodec.decode(ByteArray(8), 0, zone))
        assertEquals(null, TimeCodec.decode(byteArrayOf(*le16(2026), 10, 29, 6, 0, 0, 127), 0, zone)) // tz +31:45
        assertEquals(null, TimeCodec.decode(byteArrayOf(1, 2, 3), 0, zone))
        assertEquals(
            ZonedDateTime.of(2026, 10, 29, 5, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(),
            TimeCodec.decode(byteArrayOf(*le16(2026), 10, 29, 6, 0, 0, 4), 0, zone),
        )
    }

    @Test
    fun parsesHeartRateMeasurement() {
        val h = Harness(key, now)
        h.protocol.onHeartRateMeasurement(byteArrayOf(0x10, 72, 0x00, 0x04))
        val hr = h.events.single() as StrapEvent.HeartRate
        assertEquals(72, hr.bpm)
        assertEquals(listOf(1000), hr.rrIntervalsMs)
    }
}

private fun byteArrayOf(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
