package de.waschbeken.heliolink.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

internal object TimeCodec {
    private fun quarterHours(time: ZonedDateTime) = (time.offset.totalSeconds / (15 * 60)).toByte()

    /** Payload for the standard current time characteristic plus the Huami timezone suffix. */
    fun currentTime(now: ZonedDateTime): ByteArray = byteArrayOf(
        *le16(now.year),
        now.monthValue.toByte(),
        now.dayOfMonth.toByte(),
        now.hour.toByte(),
        now.minute.toByte(),
        now.second.toByte(),
        now.dayOfWeek.value.toByte(), // 1 = Monday ... 7 = Sunday
        0, // fractions256
        0,
        quarterHours(now),
    )

    /** Minute precision timestamp used by the fetch protocol. */
    fun fetchStart(time: ZonedDateTime): ByteArray = byteArrayOf(
        *le16(time.year),
        time.monthValue.toByte(),
        time.dayOfMonth.toByte(),
        time.hour.toByte(),
        time.minute.toByte(),
        0,
        quarterHours(time),
    )

    /** Decodes year(2) month day hour minute second [tz] into epoch millis. */
    fun decode(bytes: ByteArray, offset: Int, zone: ZoneId): Long {
        val local = LocalDateTime.of(
            bytes.u16(offset), bytes.u8(offset + 2), bytes.u8(offset + 3),
            bytes.u8(offset + 4), bytes.u8(offset + 5), bytes.u8(offset + 6),
        )
        val instant: Instant = if (bytes.size > offset + 7) {
            local.toInstant(ZoneOffset.ofTotalSeconds(bytes[offset + 7] * 15 * 60))
        } else {
            local.atZone(zone).toInstant()
        }
        return instant.toEpochMilli()
    }
}
