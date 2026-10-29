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

/** Configuration groups and the version we write (as the official app does). */
enum class ConfigGroup(val code: Int, val version: Int) {
    HEALTH(0x08, 0x02),
    WORKOUT(0x09, 0x01),
    BLUETOOTH(0x0b, 0x01);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/** The strap settings that make sense on a device without display (the Helio Strap reports all of these). */
enum class HealthSetting(val group: ConfigGroup, val code: Int, val type: ConfigType) {
    HEART_RATE_INTERVAL(ConfigGroup.HEALTH, 0x01, ConfigType.BYTE),
    HEART_RATE_HIGH_ALERT(ConfigGroup.HEALTH, 0x02, ConfigType.BYTE),
    HEART_RATE_LOW_ALERT(ConfigGroup.HEALTH, 0x03, ConfigType.BYTE),
    HEART_RATE_ACTIVITY_MONITORING(ConfigGroup.HEALTH, 0x04, ConfigType.BOOL),
    HEART_RATE_BROADCAST(ConfigGroup.HEALTH, 0x05, ConfigType.BOOL),
    SLEEP_HIGH_ACCURACY(ConfigGroup.HEALTH, 0x11, ConfigType.BOOL),
    SLEEP_BREATHING_QUALITY(ConfigGroup.HEALTH, 0x12, ConfigType.BOOL),
    STRESS_MONITORING(ConfigGroup.HEALTH, 0x13, ConfigType.BOOL),
    STRESS_RELAXATION_REMINDER(ConfigGroup.HEALTH, 0x14, ConfigType.BOOL),
    SPO2_ALL_DAY(ConfigGroup.HEALTH, 0x31, ConfigType.BOOL),
    SPO2_LOW_ALERT(ConfigGroup.HEALTH, 0x32, ConfigType.BYTE),
    INACTIVITY_WARNINGS(ConfigGroup.HEALTH, 0x41, ConfigType.BOOL),
    INACTIVITY_START(ConfigGroup.HEALTH, 0x42, ConfigType.HH_MM),
    INACTIVITY_END(ConfigGroup.HEALTH, 0x43, ConfigType.HH_MM),
    INACTIVITY_DND(ConfigGroup.HEALTH, 0x44, ConfigType.BOOL),
    INACTIVITY_DND_START(ConfigGroup.HEALTH, 0x45, ConfigType.HH_MM),
    INACTIVITY_DND_END(ConfigGroup.HEALTH, 0x46, ConfigType.HH_MM),
    GOAL_NOTIFICATION(ConfigGroup.HEALTH, 0x51, ConfigType.BOOL),

    /** INT from health group version 2 on, SHORT before. */
    GOAL_STEPS(ConfigGroup.HEALTH, 0x52, ConfigType.INT),
    WORKOUT_DETECTION_CATEGORIES(ConfigGroup.WORKOUT, 0x40, ConfigType.BYTE_LIST),
    WORKOUT_DETECTION_SENSITIVITY(ConfigGroup.WORKOUT, 0x42, ConfigType.BYTE),
    CONNECTED_ADVERTISING(ConfigGroup.BLUETOOTH, 0x02, ConfigType.BOOL);

    fun typeFor(groupVersion: Int?): ConfigType =
        if (this == GOAL_STEPS && groupVersion == 1) ConfigType.SHORT else type

    companion object {
        fun of(group: ConfigGroup, code: Int) = entries.firstOrNull { it.group == group && it.code == code }
    }
}

/**
 * Settings as reported by the strap. Byte values are kept signed: for
 * [HealthSetting.HEART_RATE_INTERVAL] 0 means off, a negative value an automatic mode and a
 * positive value the interval in minutes. Times ([ConfigType.HH_MM]) are minutes of the day.
 */
data class HealthConfig(
    val versions: Map<ConfigGroup, Int> = emptyMap(),
    val values: Map<HealthSetting, Int> = emptyMap(),
    val lists: Map<HealthSetting, List<Int>> = emptyMap(),
    val options: Map<HealthSetting, List<Int>> = emptyMap(),
) {
    fun bool(s: HealthSetting): Boolean? = values[s]?.let { it != 0 }

    fun has(s: HealthSetting) = s in values || s in lists

    /** Combines the answers of several groups. */
    fun merge(other: HealthConfig) = HealthConfig(
        versions + other.versions,
        values + other.values,
        lists + other.lists,
        options + other.options,
    )
}

object HealthConfigCodec {
    const val ENDPOINT = 0x000a

    private const val CMD_REQUEST = 0x03
    const val CMD_RESPONSE = 0x04
    private const val CMD_SET = 0x05
    const val CMD_ACK = 0x06

    /** One request per group, with constraints so we learn the allowed values. */
    fun requests(): List<ByteArray> = ConfigGroup.entries.map { group ->
        val settings = HealthSetting.entries.filter { it.group == group }
        val out = ByteArrayOutputStream()
        out.write(CMD_REQUEST)
        out.write(1)
        out.write(group.code)
        out.write(settings.size)
        settings.forEach { out.write(it.code) }
        out.toByteArray()
    }

    /** One command per group. [lists] holds the values of [ConfigType.BYTE_LIST] settings. */
    fun set(
        values: Map<HealthSetting, Int>,
        lists: Map<HealthSetting, List<Int>> = emptyMap(),
        versions: Map<ConfigGroup, Int> = emptyMap(),
    ): List<ByteArray> {
        val groups = (values.keys + lists.keys).map { it.group }.distinct()
        return groups.map { group ->
            val v = values.filterKeys { it.group == group }
            val l = lists.filterKeys { it.group == group }
            val out = ByteArrayOutputStream()
            out.write(CMD_SET)
            out.write(group.code)
            out.write(group.version)
            out.write(0)
            out.write(v.size + l.size)
            for ((setting, value) in v) {
                val type = setting.typeFor(versions[group])
                out.write(setting.code)
                out.write(type.code)
                when (type) {
                    ConfigType.BOOL, ConfigType.BYTE -> out.write(value)
                    ConfigType.SHORT -> out.write(le16(value))
                    ConfigType.INT -> out.write(le32(value))
                    ConfigType.HH_MM -> {
                        out.write(value / 60)
                        out.write(value % 60)
                    }
                    else -> throw IllegalArgumentException("$setting cannot be written")
                }
            }
            for ((setting, list) in l) {
                out.write(setting.code)
                out.write(ConfigType.BYTE_LIST.code)
                out.write(list.size)
                list.forEach { out.write(it) }
            }
            out.toByteArray()
        }
    }

    /** Parses a CMD_RESPONSE payload of one group. */
    fun parse(payload: ByteArray): HealthConfig? {
        if (payload.size < 6 || payload.u8(0) != CMD_RESPONSE || payload.u8(1) != 1) return null
        val group = ConfigGroup.of(payload.u8(2)) ?: return null
        val version = payload.u8(3)
        val constraints = payload.u8(4) == 1
        val count = payload.u8(5)
        val buf = ByteBuffer.wrap(payload, 6, payload.size - 6).order(ByteOrder.LITTLE_ENDIAN)
        val values = LinkedHashMap<HealthSetting, Int>()
        val lists = LinkedHashMap<HealthSetting, List<Int>>()
        val options = LinkedHashMap<HealthSetting, List<Int>>()
        fun result() = HealthConfig(mapOf(group to version), values, lists, options)
        try {
            repeat(count) {
                if (!buf.hasRemaining()) return@repeat
                val setting = HealthSetting.of(group, buf.get().toInt() and 0xff)
                val type = ConfigType.of(buf.get().toInt() and 0xff) ?: return result()
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
                        val v = buf.short.toInt() and 0xffff
                        if (setting != null) values[setting] = v
                        if (constraints) { buf.short; buf.short }
                    }
                    ConfigType.INT -> {
                        val v = buf.int
                        if (setting != null) values[setting] = v
                        if (constraints) { buf.int; buf.int }
                    }
                    ConfigType.BYTE_LIST -> {
                        val n = buf.get().toInt() and 0xff
                        val list = List(n) { buf.get().toInt() and 0xff }
                        if (setting != null) lists[setting] = list
                        if (constraints) {
                            val m = buf.get().toInt() and 0xff
                            val possible = List(m) { buf.get().toInt() and 0xff }
                            if (setting != null) options[setting] = possible
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
                    ConfigType.HH_MM -> {
                        val h = buf.get().toInt() and 0xff
                        val m = buf.get().toInt() and 0xff
                        if (setting != null) values[setting] = h * 60 + m
                    }
                    ConfigType.TIMESTAMP -> buf.long
                }
            }
        } catch (_: java.nio.BufferUnderflowException) {
            // Return what we could parse so far.
        }
        return result()
    }

    private fun skipString(buf: ByteBuffer) {
        while (buf.get() != 0.toByte()) Unit
    }
}
