package de.waschbeken.heliolink.core

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

/**
 * Transport independent implementation of the Zepp OS protocol subset that the Amazfit Helio
 * Strap needs: authentication, battery, realtime heart rate and steps, health settings, find
 * device and the history download ("fetch") of activity and stress data.
 *
 * The class is not thread safe; all calls must come from the same thread. Bytes that need to go
 * to the strap are handed to [write] in the order they must be sent, results are passed to [emit].
 */
class StrapProtocol(
    authKey: ByteArray,
    private val write: (GattWrite) -> Unit,
    private val emit: (StrapEvent) -> Unit,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val random: SecureRandom = SecureRandom(),
) {
    private val authKey = authKey.copyOf()
    private val encoder = ChunkedEncoder()
    private val decoder = ChunkedDecoder()
    private val encryptedEndpoints = HashSet<Int>()

    private var privateKey = ByteArray(0)
    private var sessionKey: ByteArray? = null

    var isAuthenticated = false
        private set
    var isReady = false
        private set

    init {
        require(authKey.size == 16) { "Der Auth-Key muss 16 Bytes (32 Hex-Zeichen) lang sein" }
    }

    // ------------------------------------------------------------------ connection lifecycle

    /** Starts the authentication handshake. Notifications on the chunked read characteristic must be enabled. */
    fun start() {
        encoder.reset()
        decoder.reset()
        encryptedEndpoints.clear()
        isAuthenticated = false
        isReady = false
        sessionKey = null
        privateKey = Ecdh163.generatePrivateKey(random)
        val publicKey = Ecdh163.publicKey(privateKey)
        sendChunked(Endpoint.AUTH, byteArrayOf(0x04, 0x02, 0x00, 0x02) + publicKey)
    }

    /** Called with the negotiated ATT MTU. The strap also announces its own limit, we use the smaller one. */
    fun onAttMtuChanged(mtu: Int) {
        attMtu = mtu
        if (strapMtu != null) encoder.mtu = minOf(mtu, strapMtu!!)
    }

    private var attMtu = 23
    private var strapMtu: Int? = null

    // ------------------------------------------------------------------ commands

    fun requestBattery() = sendChunked(Endpoint.BATTERY, byteArrayOf(0x03))

    fun requestSteps() = sendChunked(Endpoint.STEPS, byteArrayOf(0x03))

    fun setRealtimeSteps(enabled: Boolean) = sendChunked(Endpoint.STEPS, byteArrayOf(0x05, if (enabled) 1 else 0))

    /** Must be repeated with [HeartRateMode.CONTINUE] about every 10 seconds, otherwise the strap stops. */
    fun setRealtimeHeartRate(mode: HeartRateMode) = sendChunked(Endpoint.HEART_RATE, byteArrayOf(0x04, mode.code))

    fun findStrap(start: Boolean) = sendChunked(Endpoint.FIND_DEVICE, byteArrayOf(if (start) 0x03 else 0x06))

    fun requestHealthSettings() = sendChunked(HealthConfigCodec.ENDPOINT, HealthConfigCodec.request())

    fun changeHealthSettings(changes: Map<HealthSetting, Int>) {
        if (changes.isEmpty()) return
        sendChunked(HealthConfigCodec.ENDPOINT, HealthConfigCodec.set(changes))
    }

    fun syncTime() = write(GattWrite(WriteTarget.CURRENT_TIME, TimeCodec.currentTime(clock())))

    // ------------------------------------------------------------------ incoming data

    fun onChunkedNotification(value: ByteArray) {
        val result = decoder.decode(value)
        result.ack?.let { write(GattWrite(WriteTarget.CHUNKED_ACK, it)) }
        val message = result.message ?: return
        try {
            handleMessage(message.endpoint, message.payload)
        } catch (e: RuntimeException) {
            emit(StrapEvent.Log("Fehler bei Nachricht ${"%04x".format(message.endpoint)}: ${e.message}"))
        }
    }

    /** Standard heart rate measurement characteristic 0x2A37. */
    fun onHeartRateMeasurement(value: ByteArray) {
        if (value.size < 2) return
        val flags = value.u8(0)
        var i = 1
        val bpm: Int
        if (flags and 0x01 != 0) {
            if (value.size < 3) return
            bpm = value.u16(i); i += 2
        } else {
            bpm = value.u8(i); i += 1
        }
        if (flags and 0x08 != 0) i += 2
        val rr = ArrayList<Int>()
        if (flags and 0x10 != 0) {
            while (i + 1 < value.size) {
                rr += value.u16(i) * 1000 / 1024
                i += 2
            }
        }
        emit(StrapEvent.HeartRate(bpm, rr))
    }

    private fun handleMessage(endpoint: Int, payload: ByteArray) {
        if (payload.isEmpty()) return
        when (endpoint) {
            Endpoint.AUTH -> handleAuth(payload)
            Endpoint.SERVICES -> handleServices(payload)
            Endpoint.BATTERY -> handleBattery(payload)
            Endpoint.CONNECTION -> handleConnection(payload)
            Endpoint.HEART_RATE -> handleHeartRate(payload)
            Endpoint.STEPS -> handleSteps(payload)
            Endpoint.FIND_DEVICE -> handleFind(payload)
            HealthConfigCodec.ENDPOINT -> handleConfig(payload)
            else -> emit(StrapEvent.Log("Unbekannte Nachricht ${"%04x".format(endpoint)}: ${payload.toHex()}"))
        }
    }

    private fun handleAuth(payload: ByteArray) {
        if (payload.size < 3 || payload.u8(0) != 0x10) return
        val cmd = payload.u8(1)
        val status = payload.u8(2)
        when {
            cmd == 0x04 && status == 0x01 && payload.size >= 67 -> {
                val remoteRandom = payload.copyOfRange(3, 19)
                val remotePublic = payload.copyOfRange(19, 67)
                val shared = try {
                    Ecdh163.sharedSecret(privateKey, remotePublic)
                } catch (e: IllegalArgumentException) {
                    emit(StrapEvent.AuthFailed("Ungültiger Schlüssel vom Strap"))
                    return
                }
                val key = ByteArray(16) { (shared[it + 8].toInt() xor authKey[it].toInt()).toByte() }
                sessionKey = key
                encoder.setEncryption(shared.s32(0), key)
                decoder.setSessionKey(key)
                val answer = byteArrayOf(0x05) + Aes.encrypt(remoteRandom, authKey) + Aes.encrypt(remoteRandom, key)
                sendChunked(Endpoint.AUTH, answer)
            }
            cmd == 0x05 && status == 0x01 -> {
                isAuthenticated = true
                emit(StrapEvent.Authenticated)
                syncTime()
                sendChunked(Endpoint.CONNECTION, byteArrayOf(0x01)) // ask for the MTU
                requestBattery()
                sendChunked(Endpoint.SERVICES, byteArrayOf(0x03))
            }
            cmd == 0x05 -> emit(StrapEvent.AuthFailed("Der Strap hat den Auth-Key abgelehnt (Status $status)"))
            else -> emit(StrapEvent.AuthFailed("Unerwartete Antwort bei der Anmeldung: ${payload.toHex()}"))
        }
    }

    private fun handleServices(payload: ByteArray) {
        if (payload.u8(0) != 0x04 || payload.size < 3) return
        val count = payload.u16(1)
        var i = 3
        encryptedEndpoints.clear()
        repeat(count) {
            if (i + 2 >= payload.size) return@repeat
            val endpoint = payload.u16(i)
            if (payload.u8(i + 2) == 1) encryptedEndpoints += endpoint
            i += 3
        }
        isReady = true
        emit(StrapEvent.Ready(encryptedEndpoints.toSet()))
        requestHealthSettings()
        requestSteps()
    }

    private fun handleBattery(payload: ByteArray) {
        if (payload.u8(0) != 0x04 || payload.size < 4) return
        // payload[1] = ?, payload[2] = level, payload[3] = state (1 = charging)
        emit(StrapEvent.Battery(payload.u8(2), payload.u8(3) == 1))
    }

    private fun handleConnection(payload: ByteArray) {
        when (payload.u8(0)) {
            0x02 -> if (payload.size >= 3) {
                val mtu = payload.u16(1) + 3
                strapMtu = mtu
                encoder.mtu = minOf(mtu, attMtu)
            }
            0x03 -> sendChunked(Endpoint.CONNECTION, byteArrayOf(0x04)) // keep-alive ping
        }
    }

    private fun handleHeartRate(payload: ByteArray) {
        if (payload.u8(0) == 0x06 && payload.size >= 2) emit(StrapEvent.SleepState(payload.u8(1) == 1))
    }

    private fun handleSteps(payload: ByteArray) {
        val data = when {
            payload.u8(0) == 0x04 && payload.size == 15 -> payload.copyOfRange(2, 15)
            payload.u8(0) == 0x07 && payload.size == 14 -> payload.copyOfRange(1, 14)
            else -> return
        }
        emit(StrapEvent.Steps(data.u32(1), data.u32(5), data.u32(9)))
    }

    private fun handleFind(payload: ByteArray) {
        when (payload.u8(0)) {
            0x04 -> emit(StrapEvent.FindAcknowledged)
            0x07 -> emit(StrapEvent.FindStoppedOnStrap)
        }
    }

    private fun handleConfig(payload: ByteArray) {
        when (payload.u8(0)) {
            HealthConfigCodec.CMD_RESPONSE -> HealthConfigCodec.parse(payload)?.let { emit(StrapEvent.HealthSettings(it)) }
            HealthConfigCodec.CMD_ACK -> emit(StrapEvent.ConfigAck(if (payload.size > 1) payload.u8(1) else -1))
        }
    }

    private fun sendChunked(endpoint: Int, payload: ByteArray) {
        val encrypt = endpoint in encryptedEndpoints && encoder.hasSessionKey
        encoder.encode(endpoint, payload, encrypt).forEach { write(GattWrite(WriteTarget.CHUNKED_WRITE, it)) }
    }

    // ------------------------------------------------------------------ history download

    private class Fetch(val type: FetchType, val code: Int, val round: Int = 1, val probe: Boolean = false) {
        var start = 0L
        var expected = 0L
        var lastCounter = -1
        var valid = true
        var skipped = false
        val buffer = ByteArrayOutputStream()
    }

    private val queue = ArrayDeque<Fetch>()
    private var fetch: Fetch? = null
    private var cursor: SyncCursor? = null
    private var defaultHistory: Duration = Duration.ofDays(30)
    private var syncOk = true
    private var probing = false

    /** Byte offset of the first sleep record inside the fetched data (found by probing). */
    var sleepRecordOffset = 0

    val isSyncing get() = fetch != null

    /**
     * Downloads the stored history. Notifications on the fetch control and fetch data
     * characteristics must be enabled. [sleepCode] is the fetch code of the sleep records;
     * without it sleep stages are skipped.
     */
    fun startSync(
        cursor: SyncCursor,
        types: List<FetchType> = FetchType.entries,
        defaultHistory: Duration = Duration.ofDays(30),
        sleepCode: Int? = null,
    ) {
        if (fetch != null) return
        this.cursor = cursor
        this.defaultHistory = defaultHistory
        syncOk = true
        probing = false
        queue.clear()
        for (t in types) {
            val code = t.code ?: (if (t == FetchType.SLEEP) sleepCode else null) ?: continue
            queue.addLast(Fetch(t, code))
        }
        nextFetch()
    }

    /**
     * Finds the fetch code under which the strap delivers its sleep records by asking for each
     * candidate in turn. Unknown codes are rejected by the strap; data of other types is left on the
     * strap untouched. Emits [StrapEvent.SleepCodeProbed].
     */
    fun probeSleepCode(cursor: SyncCursor, candidates: List<Int> = SLEEP_CODE_CANDIDATES) {
        if (fetch != null) return
        this.cursor = cursor
        defaultHistory = Duration.ofDays(7)
        syncOk = true
        probing = true
        queue.clear()
        candidates.forEach { queue.addLast(Fetch(FetchType.SLEEP, it, probe = true)) }
        nextFetch()
    }

    /** Aborts a running sync, e.g. after a timeout or disconnect. */
    fun abortSync(reason: String) {
        if (fetch == null && queue.isEmpty()) return
        fetch = null
        queue.clear()
        if (probing) {
            probing = false
            emit(StrapEvent.Log("Suche nach Schlafphasen abgebrochen: $reason"))
            return
        }
        emit(StrapEvent.SyncFinished(false, reason))
    }

    private fun nextFetch() {
        val next = queue.removeFirstOrNull()
        fetch = next
        if (next == null) {
            if (probing) {
                probing = false
                emit(StrapEvent.SleepCodeProbed(null, 0))
            } else {
                emit(StrapEvent.SyncFinished(syncOk))
            }
            return
        }
        val now = clock()
        val sinceMillis = (if (next.probe) null else cursor?.get(next.type))
            ?: now.minus(defaultHistory).toInstant().toEpochMilli()
        val since = Instant.ofEpochMilli(sinceMillis).atZone(now.zone)
        write(GattWrite(WriteTarget.FETCH_CONTROL, byteArrayOf(0x01, next.code.toByte()) + TimeCodec.fetchStart(since)))
    }

    fun onFetchControlNotification(value: ByteArray) {
        val f = fetch ?: return
        if (value.size < 3 || value.u8(0) != 0x10) return
        val success = value.u8(2) == 0x01
        when (value.u8(1)) {
            0x01 -> {
                if (!success || value.size < 14) {
                    if (!f.probe) emit(StrapEvent.Log("${f.type.label}: vom Strap nicht unterstützt (${value.toHex()})"))
                    nextFetch()
                    return
                }
                f.expected = value.u32(3)
                f.start = TimeCodec.decode(value, 7, clock().zone)
                if (!f.probe) emit(StrapEvent.SyncProgress(f.type, f.start, f.expected, 0))
                if (f.expected == 0L || (f.probe && f.expected > PROBE_MAX_BYTES)) {
                    f.skipped = true
                    sendFetchAck(keepOnStrap = true)
                } else {
                    write(GattWrite(WriteTarget.FETCH_CONTROL, byteArrayOf(0x02)))
                }
            }
            0x02 -> {
                if (!success) {
                    emit(StrapEvent.Log("${f.type.label}: Übertragung fehlgeschlagen"))
                    if (!f.probe) syncOk = false
                    nextFetch()
                    return
                }
                val data = f.buffer.toByteArray()
                if (f.probe) {
                    val offset = listOf(0, 1).firstOrNull {
                        SleepRecordParser.looksLikeRecords(data, it, clock().toEpochSecond())
                    }
                    if (offset == null) {
                        f.skipped = true
                        sendFetchAck(keepOnStrap = true)
                        return
                    }
                    sleepRecordOffset = offset
                    queue.clear()
                    emit(StrapEvent.SleepCodeProbed(f.code, offset))
                }
                if (value.size >= 7 && f.type != FetchType.ACTIVITY && value.s32(3) != crc32(data)) {
                    emit(StrapEvent.Log("${f.type.label}: Prüfsumme falsch"))
                    f.valid = false
                }
                val stored = f.valid && process(f, data)
                if (!stored) syncOk = false
                sendFetchAck(keepOnStrap = !stored)
            }
            0x03 -> {
                if (!f.skipped && f.valid && !f.probe && needsAnotherRound(f)) {
                    queue.addFirst(Fetch(f.type, f.code, f.round + 1))
                }
                nextFetch()
            }
        }
    }

    fun onFetchDataNotification(value: ByteArray) {
        val f = fetch ?: return
        if (value.isEmpty()) return
        val counter = value.u8(0)
        if (counter != (f.lastCounter + 1) and 0xff) {
            f.valid = false
            emit(StrapEvent.Log("${f.type.label}: Paket $counter nach ${f.lastCounter} – Daten verworfen"))
        }
        f.lastCounter = counter
        f.buffer.write(value, 1, value.size - 1)
        if (!f.probe) emit(StrapEvent.SyncProgress(f.type, f.start, f.expected, f.buffer.size().toLong()))
    }

    private fun sendFetchAck(keepOnStrap: Boolean) {
        // 0x01: data is stored on the phone and can be dropped, 0x09: keep it
        write(GattWrite(WriteTarget.FETCH_CONTROL, byteArrayOf(0x03, if (keepOnStrap) 0x09 else 0x01)))
    }

    private var lastProcessedEnd = 0L

    private fun process(f: Fetch, data: ByteArray): Boolean {
        val startSec = f.start / 1000
        // Epoch seconds up to which data is now stored, or null to leave the cursor alone.
        val end: Long?
        when (f.type) {
            FetchType.ACTIVITY -> {
                if (data.size % 8 != 0) {
                    emit(StrapEvent.Log("Aktivität: unerwartete Länge ${data.size}"))
                    return false
                }
                val samples = List(data.size / 8) { n ->
                    val o = n * 8
                    val hr = data.u8(o + 3)
                    ActivitySample(
                        timestamp = startSec + n * 60L,
                        rawKind = data.u8(o),
                        intensity = data.u8(o + 1),
                        steps = data.u8(o + 2),
                        heartRate = if (hr in 1..254) hr else 0,
                        sleep = data.u8(o + 5),
                        deepSleep = data.u8(o + 6),
                        remSleep = data.u8(o + 7),
                    )
                }
                if (samples.isNotEmpty()) emit(StrapEvent.ActivityData(samples))
                end = startSec + samples.size * 60L
            }
            FetchType.STRESS -> {
                val samples = ArrayList<StressSample>()
                data.forEachIndexed { n, b ->
                    val v = b.toInt() and 0xff
                    if (v != 0xff) samples += StressSample(startSec + n * 60L, v)
                }
                if (samples.isNotEmpty()) emit(StrapEvent.StressData(samples))
                end = startSec + data.size * 60L
            }
            FetchType.SPO2 -> {
                // version(1), then 65 byte records: timestamp(4) spo2(1, bit 7 = automatic) 60 bytes more
                if (data.isEmpty() || (data.size - 1) % 65 != 0 || data.u8(0) != 2) {
                    emit(StrapEvent.Log("SpO₂: unbekanntes Format (${data.size} Bytes)"))
                    return false
                }
                val samples = (0 until (data.size - 1) / 65).map { n ->
                    val o = 1 + n * 65
                    ValueSample(data.u32(o), data.u8(o + 4) and 0x7f)
                }.filter { it.value in 50..100 }
                if (samples.isNotEmpty()) emit(StrapEvent.Spo2Data(samples))
                end = samples.maxOfOrNull { it.timestamp }?.plus(60)
            }
            FetchType.RESTING_HEART_RATE -> {
                // timestamp(4) utc offset in quarter hours(1) heart rate(1)
                if (data.size % 6 != 0) {
                    emit(StrapEvent.Log("Ruhepuls: unbekanntes Format (${data.size} Bytes)"))
                    return false
                }
                val samples = (0 until data.size / 6).map { n -> ValueSample(data.u32(n * 6), data.u8(n * 6 + 5)) }
                    .filter { it.value in 25..150 }
                if (samples.isNotEmpty()) emit(StrapEvent.RestingHeartRateData(samples))
                end = samples.maxOfOrNull { it.timestamp }?.plus(60)
            }
            FetchType.SLEEP -> {
                val records = SleepRecordParser.parseAll(data, sleepRecordOffset)
                if (records.isNotEmpty()) emit(StrapEvent.SleepData(records))
                end = records.maxOfOrNull { it.updated }?.plus(60)
            }
        }
        lastProcessedEnd = (end ?: startSec) * 1000
        if (end != null) cursor?.set(f.type, end * 1000)
        return true
    }

    private fun needsAnotherRound(f: Fetch): Boolean {
        if (f.round >= 6 || f.expected == 0L) return false
        if (lastProcessedEnd - f.start < 1000L) return false
        return lastProcessedEnd < clock().toInstant().toEpochMilli() - 60_000L
    }

    companion object {
        private const val PROBE_MAX_BYTES = 300_000L

        /** Fetch codes that are not used by any data type we know, tried when looking for sleep records. */
        val SLEEP_CODE_CANDIDATES: List<Int> =
            ((0x27..0x50) + (0x14..0x24) + (0x08..0x0c) + (0x0e..0x11) + listOf(0x03, 0x04))
                .filter { it !in setOf(0x2c, 0x2e, 0x38, 0x3a, 0x3d) }
    }
}

internal object Endpoint {
    const val SERVICES = 0x0000
    const val CONNECTION = 0x0015
    const val STEPS = 0x0016
    const val FIND_DEVICE = 0x001a
    const val HEART_RATE = 0x001d
    const val BATTERY = 0x0029
    const val AUTH = 0x0082
}
