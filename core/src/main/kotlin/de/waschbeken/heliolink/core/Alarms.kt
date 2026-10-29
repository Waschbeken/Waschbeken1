package de.waschbeken.heliolink.core

/**
 * An alarm on the strap. The strap has 10 slots ([position] 0–9) and wakes you by vibrating.
 * [repetition] is a bit set Monday = 1 … Sunday = 64, 0 = once. With [smart] the strap wakes you up
 * to 30 minutes earlier in a light sleep phase.
 */
data class StrapAlarm(
    val position: Int,
    val enabled: Boolean,
    val smart: Boolean,
    val hour: Int,
    val minute: Int,
    val repetition: Int,
) {
    fun repeatsOn(dayOfWeek: java.time.DayOfWeek) = repetition and (1 shl (dayOfWeek.value - 1)) != 0

    companion object {
        const val SLOTS = 10
        const val WEEKDAYS = 0x1f
        const val EVERY_DAY = 0x7f
    }
}

/** Alarm service of Zepp OS (endpoint 0x000f). */
object AlarmCodec {
    const val ENDPOINT = 0x000f
    private const val CMD_CREATE = 0x03
    const val CMD_CREATE_ACK = 0x04
    private const val CMD_DELETE = 0x05
    const val CMD_DELETE_ACK = 0x06
    const val CMD_UPDATE_ACK = 0x08
    private const val CMD_REQUEST = 0x09
    const val CMD_RESPONSE = 0x0a
    const val CMD_NOTIFY_CHANGE = 0x0f

    private const val FLAG_SMART = 0x01
    private const val FLAG_ENABLED = 0x04

    fun request() = byteArrayOf(CMD_REQUEST.toByte())

    /** Creates or overwrites the alarm in its slot. */
    fun set(a: StrapAlarm): ByteArray {
        var flags = 0
        if (a.enabled) flags = flags or FLAG_ENABLED
        if (a.smart) flags = flags or FLAG_SMART
        return byteArrayOf(
            CMD_CREATE.toByte(), 0x01, flags.toByte(), a.position.toByte(), a.hour.toByte(), a.minute.toByte(),
            a.repetition.toByte(), 0, 0, 0, 0, 0,
        )
    }

    fun delete(position: Int) = byteArrayOf(CMD_DELETE.toByte(), 0x01, position.toByte())

    /** Parses a CMD_RESPONSE: count, then 10 bytes per alarm (flags, position, hour, minute, repetition, …). */
    fun parse(payload: ByteArray): List<StrapAlarm>? {
        if (payload.size < 2 || payload.u8(0) != CMD_RESPONSE) return null
        val count = payload.u8(1)
        if (payload.size < 2 + count * 10) return null
        return List(count) { i ->
            val o = 2 + i * 10
            val flags = payload.u8(o)
            StrapAlarm(
                position = payload.u8(o + 1),
                enabled = flags and FLAG_ENABLED != 0,
                smart = flags and FLAG_SMART != 0,
                hour = payload.u8(o + 2),
                minute = payload.u8(o + 3),
                repetition = payload.u8(o + 4),
            )
        }
    }
}

/** The next time one of the enabled [alarms] rings, or null. */
fun nextAlarm(alarms: List<StrapAlarm>, now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): java.time.ZonedDateTime? {
    var best: java.time.ZonedDateTime? = null
    for (a in alarms.filter { it.enabled }) {
        for (d in 0..7L) {
            val date = now.toLocalDate().plusDays(d)
            if (a.repetition != 0 && !a.repeatsOn(date.dayOfWeek)) continue
            val t = date.atTime(a.hour, a.minute).atZone(now.zone)
            if (!t.isAfter(now)) continue
            if (best == null || t.isBefore(best)) best = t
            break
        }
    }
    return best
}
