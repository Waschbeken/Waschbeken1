package de.waschbeken.heliolink.core

import java.io.ByteArrayOutputStream

/**
 * Encoder for the Zepp OS "2021 chunked" transport (always with the extended header).
 *
 * First chunk:  03 flags 00 handle count len(4) endpoint(2) payload...
 * Next chunks:  03 flags 00 handle count payload...
 *
 * Flags: 0x01 first chunk, 0x02 last chunk, 0x04 ack requested, 0x08 encrypted.
 */
class ChunkedEncoder {
    private var writeHandle: Byte = 0
    private var sequenceNumber = 0
    private var sessionKey: ByteArray? = null

    /** ATT MTU used to size the chunks. Starts at the BLE minimum. */
    var mtu: Int = 23
        set(value) {
            field = value.coerceAtLeast(23)
        }

    val hasSessionKey get() = sessionKey != null

    fun setEncryption(sequenceNumber: Int, sessionKey: ByteArray) {
        this.sequenceNumber = sequenceNumber
        this.sessionKey = sessionKey.copyOf()
    }

    fun reset() {
        sessionKey = null
        sequenceNumber = 0
        mtu = 23
    }

    fun encode(endpoint: Int, payload: ByteArray, encrypt: Boolean): List<ByteArray> {
        writeHandle++
        val handle = writeHandle
        val length = payload.size
        var data = payload

        if (encrypt) {
            val key = sessionKey ?: throw IllegalStateException("no session key for encrypted write")
            val messageKey = ByteArray(16) { (key[it].toInt() xor handle.toInt()).toByte() }
            var encLength = length + 8
            if (encLength % 16 != 0) encLength += 16 - encLength % 16
            val plain = ByteArray(encLength)
            payload.copyInto(plain)
            le32(sequenceNumber).copyInto(plain, length)
            sequenceNumber++
            le32(crc32(plain, 0, length + 4)).copyInto(plain, length + 4)
            data = Aes.encrypt(plain, messageKey)
        }

        val chunks = ArrayList<ByteArray>()
        var remaining = data.size
        var count = 0
        var headerSize = 11
        do {
            val maxChunk = mtu - 3 - headerSize
            val n = minOf(remaining, maxChunk)
            val chunk = ByteArray(n + headerSize)
            var flags = 0
            if (encrypt) flags = flags or 0x08
            if (count == 0) {
                flags = flags or 0x01
                le32(length).copyInto(chunk, 5)
                le16(endpoint).copyInto(chunk, 9)
            }
            if (remaining <= maxChunk) flags = flags or 0x06
            chunk[0] = 0x03
            chunk[1] = flags.toByte()
            chunk[2] = 0
            chunk[3] = handle
            chunk[4] = count.toByte()
            data.copyInto(chunk, headerSize, data.size - remaining, data.size - remaining + n)
            chunks += chunk
            remaining -= n
            headerSize = 5
            count++
        } while (remaining > 0)
        return chunks
    }
}

/** Reassembles and decrypts chunked messages coming from the strap. */
class ChunkedDecoder {
    data class Message(val endpoint: Int, val payload: ByteArray, val encrypted: Boolean)

    data class Result(val message: Message?, val ack: ByteArray?)

    private var sessionKey: ByteArray? = null
    private var currentHandle: Byte? = null
    private var currentEndpoint = 0
    private var currentLength = 0
    private var encrypted = false
    private var buffer = ByteArrayOutputStream()

    fun setSessionKey(key: ByteArray) {
        sessionKey = key.copyOf()
    }

    fun reset() {
        sessionKey = null
        currentHandle = null
        buffer = ByteArrayOutputStream()
    }

    fun decode(data: ByteArray): Result {
        if (data.size < 5 || data[0] != 0x03.toByte()) return Result(null, null)
        val flags = data.u8(1)
        val isEncrypted = flags and 0x08 != 0
        val first = flags and 0x01 != 0
        val last = flags and 0x02 != 0
        val needsAck = flags and 0x04 != 0
        var i = 3 // skip extended header byte
        val handle = data[i++]
        val count = data[i++]
        val ack = if (needsAck) byteArrayOf(0x04, 0x00, handle, 0x01, count) else null

        if (first) {
            if (data.size < 11) return Result(null, ack)
            currentLength = data.s32(i)
            i += 4
            currentEndpoint = data.u16(i)
            i += 2
            currentHandle = handle
            encrypted = isEncrypted
            buffer = ByteArrayOutputStream()
        } else if (currentHandle != handle) {
            return Result(null, ack)
        }
        buffer.write(data, i, data.size - i)

        if (!last) return Result(null, ack)

        currentHandle = null
        var payload = buffer.toByteArray()
        if (encrypted) {
            val key = sessionKey ?: return Result(null, ack)
            val messageKey = ByteArray(16) { (key[it].toInt() xor handle.toInt()).toByte() }
            if (payload.isEmpty() || payload.size % 16 != 0) return Result(null, ack)
            payload = Aes.decrypt(payload, messageKey)
            if (currentLength > payload.size) return Result(null, ack)
            payload = payload.copyOf(currentLength)
        }
        return Result(Message(currentEndpoint, payload, encrypted), ack)
    }
}
