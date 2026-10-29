package de.waschbeken.heliolink.core

import kotlin.test.Test
import kotlin.test.assertEquals

class HealthConfigTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun parsesAllTypesWithConstraints() {
        val payload = bytes(
            0x04, 0x01, 0x08, 0x02, 0x01, 0x05,
            0x01, 0x10, 0xfe, 0x06, 0x00, 0xfe, 0x01, 0x05, 0x0a, 0x1e, // interval -2, options
            0x04, 0x0b, 0x00, // activity monitoring off
            0x42, 0x30, 0x08, 0x00, // inactivity from 08:00
            0x52, 0x03, 0x40, 0x1f, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff, 0xff, 0x00, 0x00, // goal 8000
            0x51, 0x0b, 0x01,
        )
        val c = HealthConfigCodec.parse(payload)!!
        assertEquals(-2, c.values[HealthSetting.HEART_RATE_INTERVAL])
        assertEquals(listOf(0, -2, 1, 5, 10, 30), c.options[HealthSetting.HEART_RATE_INTERVAL])
        assertEquals(false, c.bool(HealthSetting.HEART_RATE_ACTIVITY_MONITORING))
        assertEquals(8 * 60, c.values[HealthSetting.INACTIVITY_START])
        assertEquals(8000, c.values[HealthSetting.GOAL_STEPS])
        assertEquals(true, c.bool(HealthSetting.GOAL_NOTIFICATION))
        assertEquals(2, c.versions[ConfigGroup.HEALTH])
    }

    @Test
    fun parsesWorkoutDetectionList() {
        val payload = bytes(0x04, 0x01, 0x09, 0x01, 0x01, 0x02, 0x40, 0x11, 0x00, 0x01, 0xdf, 0x42, 0x10, 0x01, 0x03, 0x00, 0x01, 0x02)
        val c = HealthConfigCodec.parse(payload)!!
        assertEquals(emptyList(), c.lists[HealthSetting.WORKOUT_DETECTION_CATEGORIES])
        assertEquals(listOf(0xdf), c.options[HealthSetting.WORKOUT_DETECTION_CATEGORIES])
        assertEquals(1, c.values[HealthSetting.WORKOUT_DETECTION_SENSITIVITY])
    }

    @Test
    fun encodesOneCommandPerGroup() {
        val cmds = HealthConfigCodec.set(
            mapOf(HealthSetting.INACTIVITY_END to 21 * 60 + 30, HealthSetting.GOAL_STEPS to 10000),
            mapOf(HealthSetting.WORKOUT_DETECTION_CATEGORIES to listOf(0xdf)),
            mapOf(ConfigGroup.HEALTH to 1),
        )
        assertEquals(2, cmds.size)
        assertEquals(
            bytes(0x05, 0x08, 0x02, 0x00, 0x02, 0x43, 0x30, 21, 30, 0x52, 0x01, 0x10, 0x27).toList(),
            cmds[0].toList(),
        )
        assertEquals(bytes(0x05, 0x09, 0x01, 0x00, 0x01, 0x40, 0x11, 0x01, 0xdf).toList(), cmds[1].toList())
    }
}
