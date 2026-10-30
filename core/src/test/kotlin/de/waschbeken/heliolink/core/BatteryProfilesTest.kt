package de.waschbeken.heliolink.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BatteryProfilesTest {
    private val config = HealthConfig(
        values = mapOf(
            HealthSetting.HEART_RATE_INTERVAL to -2,
            HealthSetting.HEART_RATE_ACTIVITY_MONITORING to 0,
            HealthSetting.SLEEP_HIGH_ACCURACY to 1,
            HealthSetting.SLEEP_BREATHING_QUALITY to 1,
            HealthSetting.STRESS_MONITORING to 1,
            HealthSetting.SPO2_ALL_DAY to 0,
        ),
        options = mapOf(HealthSetting.HEART_RATE_INTERVAL to listOf(0, -2, 1, 5, 10, 30)),
    )

    @Test
    fun recognisesTheCurrentProfile() {
        assertEquals(BatteryProfiles.Profile.BALANCED, BatteryProfiles.current(config))
        val own = config.copy(values = config.values + (HealthSetting.SPO2_ALL_DAY to 1))
        assertNull(BatteryProfiles.current(own))
    }

    @Test
    fun onlySendsWhatTheStrapKnows() {
        val saver = BatteryProfiles.settings(BatteryProfiles.Profile.SAVER, config)
        assertEquals(10, saver[HealthSetting.HEART_RATE_INTERVAL])
        assertEquals(0, saver[HealthSetting.STRESS_MONITORING])
        assertEquals(1, saver[HealthSetting.SLEEP_HIGH_ACCURACY])
        assertNull(saver[HealthSetting.HEART_RATE_BROADCAST]) // not reported by this strap
        val applied = config.copy(values = config.values + saver)
        assertEquals(BatteryProfiles.Profile.SAVER, BatteryProfiles.current(applied))
    }

    @Test
    fun picksTheClosestInterval() {
        val coarse = config.copy(options = mapOf(HealthSetting.HEART_RATE_INTERVAL to listOf(0, -2, 1, 30)))
        assertEquals(1, BatteryProfiles.interval(1, coarse))
        assertEquals(1, BatteryProfiles.interval(10, coarse.copy(options = mapOf(HealthSetting.HEART_RATE_INTERVAL to listOf(1, 30)))))
        assertEquals(-2, BatteryProfiles.settings(BatteryProfiles.Profile.BALANCED, coarse)[HealthSetting.HEART_RATE_INTERVAL])
    }

    @Test
    fun drainPerDaySkipsCharging() {
        val h = 3600L
        val levels = listOf(
            ValueSample(0, 80), ValueSample(6 * h, 77), ValueSample(12 * h, 74), // 6 % in 12 h
            ValueSample(13 * h, 100), // charged
            ValueSample(19 * h, 97), ValueSample(25 * h, 94), // 6 % in 12 h
        )
        assertEquals(12.0, BatteryProfiles.drainPerDay(levels)!!, 0.01)
        assertNull(BatteryProfiles.drainPerDay(levels.take(2)))
    }
}
