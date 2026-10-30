package de.waschbeken.heliolink.core

import kotlin.math.abs

/**
 * Ready-made strap settings between "as much data as possible" and "as long as possible on one
 * charge". Only settings the strap reports are touched; the sleep stays accurate in every profile.
 */
object BatteryProfiles {
    enum class Profile(val label: String, val description: String, val syncMinutes: Int) {
        ACCURATE(
            "Maximale Genauigkeit",
            "Puls jede Minute und bei Bewegung öfter, Stress, SpO₂ ganztägig, Atmung im Schlaf. Die meisten Daten, kürzeste Laufzeit.",
            15,
        ),
        BALANCED(
            "Ausgewogen",
            "Puls intelligent, Stress, Schlaf mit Atmungsqualität (SpO₂ nachts), kein SpO₂ am Tag. Empfohlen.",
            30,
        ),
        SAVER(
            "Akku sparen",
            "Puls alle 10 Minuten, kein Stress, kein SpO₂ und keine Atmungsmessung. Der Schlaf bleibt genau. Hält deutlich länger.",
            60,
        ),
    }

    /** Stands for the strap's automatic heart rate interval (a negative value on the strap). */
    private const val SMART = -1

    private fun wanted(profile: Profile): Map<HealthSetting, Int> = when (profile) {
        Profile.ACCURATE -> mapOf(
            HealthSetting.HEART_RATE_INTERVAL to 1,
            HealthSetting.HEART_RATE_ACTIVITY_MONITORING to 1,
            HealthSetting.SLEEP_HIGH_ACCURACY to 1,
            HealthSetting.SLEEP_BREATHING_QUALITY to 1,
            HealthSetting.STRESS_MONITORING to 1,
            HealthSetting.SPO2_ALL_DAY to 1,
        )
        // With the automatic interval the strap decides about extra measurements during activity itself.
        Profile.BALANCED -> mapOf(
            HealthSetting.HEART_RATE_INTERVAL to SMART,
            HealthSetting.SLEEP_HIGH_ACCURACY to 1,
            HealthSetting.SLEEP_BREATHING_QUALITY to 1,
            HealthSetting.STRESS_MONITORING to 1,
            HealthSetting.SPO2_ALL_DAY to 0,
        )
        Profile.SAVER -> mapOf(
            HealthSetting.HEART_RATE_INTERVAL to 10,
            HealthSetting.HEART_RATE_ACTIVITY_MONITORING to 0,
            HealthSetting.HEART_RATE_BROADCAST to 0,
            HealthSetting.SLEEP_HIGH_ACCURACY to 1,
            HealthSetting.SLEEP_BREATHING_QUALITY to 0,
            HealthSetting.STRESS_MONITORING to 0,
            HealthSetting.STRESS_RELAXATION_REMINDER to 0,
            HealthSetting.SPO2_ALL_DAY to 0,
        )
    }

    /** The heart rate interval the strap offers that comes closest to [wanted]. */
    fun interval(wanted: Int, config: HealthConfig): Int {
        val options = config.options[HealthSetting.HEART_RATE_INTERVAL].orEmpty()
        if (wanted == SMART) {
            return options.firstOrNull { it < 0 } ?: config.values[HealthSetting.HEART_RATE_INTERVAL]?.takeIf { it < 0 } ?: -2
        }
        return options.filter { it > 0 }.minByOrNull { abs(it - wanted) } ?: wanted
    }

    /** The values to send for [profile], limited to what [config] reports. */
    fun settings(profile: Profile, config: HealthConfig): Map<HealthSetting, Int> =
        wanted(profile).filterKeys { it in config.values }.mapValues { (setting, value) ->
            if (setting == HealthSetting.HEART_RATE_INTERVAL) interval(value, config) else value
        }

    private fun matches(setting: HealthSetting, value: Int, config: HealthConfig): Boolean {
        val current = config.values[setting] ?: return true
        return when (setting) {
            HealthSetting.HEART_RATE_INTERVAL -> if (value < 0) current < 0 else current == value
            else -> (current != 0) == (value != 0)
        }
    }

    /** The profile the strap is set to, or null for own settings. */
    fun current(config: HealthConfig): Profile? = Profile.entries.firstOrNull { p ->
        val s = settings(p, config)
        s.isNotEmpty() && s.all { (setting, value) -> matches(setting, value, config) }
    }

    /**
     * Battery used per day while not charging, from the levels (epoch seconds, %) of the last days;
     * null with less than 12 hours of discharge.
     */
    fun drainPerDay(levels: List<ValueSample>): Double? {
        val sorted = levels.sortedBy { it.timestamp }
        var used = 0.0
        var seconds = 0L
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]
            val b = sorted[i]
            val gap = b.timestamp - a.timestamp
            // Rising level = charging; long gaps (strap not connected) say nothing reliable.
            if (b.value > a.value || gap > 12 * 3600) continue
            used += a.value - b.value
            seconds += gap
        }
        if (seconds < 12 * 3600) return null
        return used / seconds * 86_400
    }
}
