package de.waschbeken.heliolink.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Value types of the Zepp OS configuration service (endpoint 0x000a). */
enum class ConfigType(val code: Int) {
    SHORT(0x01), INT(0x03), BOOL(0x0b), BYTE(0x10), BYTE_LIST(0x11),
    STRING(0x20), STRING_LIST(0x21), HH_MM(0x30), TIMESTAMP(0x40);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/** The health settings that make sense on a strap without display. */
enum class HealthSetting(val code: Int, val type: ConfigType) {
    HEART_RATE_INTERVAL(0x01, ConfigType.BYTE),
    HEART_RATE_HIGH_ALERT(0x02, ConfigType.BYTE),
    HEART_RATE_LOW_ALERT(0x03, ConfigType.BYTE),
    HEART_RATE_ACTIVITY_MONITORING(0x04, ConfigType.BOOL),
    HEART_RATE_BROADCAST(0x05, ConfigType.BOOL),
    SLEEP_HIGH_ACCURACY(0x11, ConfigType.BOOL),
    SLEEP_BREATHING_QUALITY(0x12, ConfigType.BOOL),
    STRESS_MONITORING(0x13, ConfigType.BOOL),
    STRESS_RELAXATION_REMINDER(0x14, ConfigType.BOOL),
    SPO2_ALL_DAY(0x31, ConfigType.BOOL),
    SPO2_LOW_ALERT(0x32, ConfigType.BYTE),
    INACTIVITY_WARNINGS(0x41, ConfigType.BOOL);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/**
 * Health settings as reported by the strap. Byte values are kept signed: for
 * [HealthSetting.HEART_RATE_INTERVAL] 0 means off, a negative value an automatic mode and a
 * positive value the interval in minutes.
 */
data class HealthConfig(
    val version: Int,
    val values: Map<HealthSetting, Int>,
    val options: Map<HealthSetting, List<Int>> = emptyMap(),
) {
    fun bool(s: HealthSetting): Boolean? = values[s]?.let { it != 0 }
}

object HealthConfigCodec {
    const val ENDPOINT = 0x000a
    private const val GROUP_HEALTH = 0x08
    private const val GROUP_VERSION = 0x02

    private const val CMD_REQUEST = 0x03
    const val CMD_RESPONSE = 0x04
    private const val CMD_SET = 0x05
    const val CMD_ACK = 0x06

    fun request(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CMD_REQUEST)
        out.write(1) // include constraints, so we learn the allowed values
        out.write(GROUP_HEALTH)
        out.write(HealthSetting.entries.size)
        HealthSetting.entries.forEach { out.write(it.code) }
        return out.toByteArray()
    }

    fun set(changes: Map<HealthSetting, Int>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CMD_SET)
        out.write(GROUP_HEALTH)
        out.write(GROUP_VERSION)
        out.write(0)
        out.write(changes.size)
        for ((setting, value) in changes) {
            out.write(setting.code)
            out.write(setting.type.code)
            out.write(value)
        }
        return out.toByteArray()
    }

    /** Parses a CMD_RESPONSE payload, returns null if it is not a health group response. */
    fun parse(payload: ByteArray): HealthConfig? {
        if (payload.size < 6 || payload.u8(0) != CMD_RESPONSE || payload.u8(1) != 1) return null
        if (payload.u8(2) != GROUP_HEALTH) return null
        val version = payload.u8(3)
        val constraints = payload.u8(4) == 1
        val count = payload.u8(5)
        val buf = ByteBuffer.wrap(payload, 6, payload.size - 6).order(ByteOrder.LITTLE_ENDIAN)
        val values = LinkedHashMap<HealthSetting, Int>()
        val options = LinkedHashMap<HealthSetting, List<Int>>()
        try {
            repeat(count) {
                if (!buf.hasRemaining()) return@repeat
                val setting = HealthSetting.of(buf.get().toInt() and 0xff)
                val type = ConfigType.of(buf.get().toInt() and 0xff) ?: return HealthConfig(version, values, options)
                when (type) {
                    ConfigType.BOOL -> {
                        val v = buf.get().toInt()
                        if (setting != null) values[setting] = if (v == 1) 1 else 0
                    }
                    ConfigType.BYTE -> {
                        val v = buf.get().toInt()
                        if (setting != null) values[setting] = v
                        if (constraints) {
                            val n = buf.get().toInt() and 0xff
                            val possible = List(n) { buf.get().toInt() }
                            if (setting != null) options[setting] = possible
                        }
                    }
                    ConfigType.SHORT -> {
                        buf.short
                        if (constraints) { buf.short; buf.short }
                    }
                    ConfigType.INT -> {
                        buf.int
                        if (constraints) { buf.int; buf.int }
                    }
                    ConfigType.BYTE_LIST -> {
                        val n = buf.get().toInt() and 0xff
                        repeat(n) { buf.get() }
                        if (constraints) {
                            val m = buf.get().toInt() and 0xff
                            repeat(m) { buf.get() }
                        }
                    }
                    ConfigType.STRING -> {
                        skipString(buf)
                        if (constraints) buf.get()
                    }
                    ConfigType.STRING_LIST -> {
                        skipString(buf)
                        if (constraints) {
                            buf.get()
                            val m = buf.get().toInt() and 0xff
                            repeat(m) { skipString(buf) }
                        }
                    }
                    ConfigType.HH_MM -> { buf.get(); buf.get() }
                    ConfigType.TIMESTAMP -> buf.long
                }
            }
        } catch (_: java.nio.BufferUnderflowException) {
            // Return what we could parse so far.
        }
        return HealthConfig(version, values, options)
    }

    private fun skipString(buf: ByteBuffer) {
        while (buf.get() != 0.toByte()) Unit
    }
}
