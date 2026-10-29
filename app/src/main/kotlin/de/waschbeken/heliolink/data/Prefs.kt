package de.waschbeken.heliolink.data

import android.content.Context
import android.content.SharedPreferences
import de.waschbeken.heliolink.core.FetchType
import de.waschbeken.heliolink.core.HeartRateZones
import de.waschbeken.heliolink.core.SyncCursor
import de.waschbeken.heliolink.core.parseHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class UserProfile(
    val age: Int = 30,
    val weightKg: Int = 75,
    val heightCm: Int = 178,
    val male: Boolean = true,
    val maxHrOverride: Int = 0,
    val stepGoal: Int = 8000,
    val sleepGoalMinutes: Int = 480,
    /** 0 = take the strap's resting heart rate. */
    val restingHrManual: Int = 0,
    /** Zones from the heart rate reserve (Karvonen) instead of % of the maximum. */
    val zonesFromReserve: Boolean = false,
) {
    val maxHeartRate get() = if (maxHrOverride > 0) maxHrOverride else HeartRateZones.maxHeartRate(age)

    /** Resting heart rate for the zone calculation, or null for zones as % of the maximum. */
    fun zoneRest(strapResting: Int?): Int? =
        if (!zonesFromReserve) null else (restingHrManual.takeIf { it > 0 } ?: strapResting ?: 60)
}

enum class ThemeMode(val label: String) { SYSTEM("Wie System"), LIGHT("Hell"), DARK("Dunkel") }

class Prefs(context: Context) : SyncCursor {
    private val sp: SharedPreferences = context.getSharedPreferences("heliolink", Context.MODE_PRIVATE)

    private val _changes = MutableStateFlow(0)

    /** Increments on every change, so Compose can react to settings. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).apply()
        _changes.value++
    }

    // --- strap

    var deviceAddress: String?
        get() = sp.getString("address", null)
        set(v) = edit { putString("address", v) }

    var deviceName: String?
        get() = sp.getString("name", null)
        set(v) = edit { putString("name", v) }

    var authKeyHex: String?
        get() = sp.getString("auth_key", null)
        set(v) = edit { putString("auth_key", v) }

    val authKey: ByteArray? get() = authKeyHex?.let { parseHex(it) }?.takeIf { it.size == 16 }

    val isConfigured get() = deviceAddress != null && authKey != null

    // --- sync

    var autoSyncMinutes: Int
        get() = sp.getInt("auto_sync", 30)
        set(v) = edit { putInt("auto_sync", v) }

    var historyDays: Int
        get() = sp.getInt("history_days", 30)
        set(v) = edit { putInt("history_days", v) }

    /** How many candidate codes the sleep code search already tried (it continues after a disconnect). */
    var sleepProbeTried: Int
        get() = sp.getInt("sleep_probe_tried", 0)
        set(v) = edit { putInt("sleep_probe_tried", v) }

    /** Fetch code of the strap's sleep records: -1 not searched yet, 0 none found. */
    var sleepCode: Int
        get() = sp.getInt("sleep_code", -1)
        set(v) = edit { putInt("sleep_code", v) }

    var sleepOffset: Int
        get() = sp.getInt("sleep_offset", 0)
        set(v) = edit { putInt("sleep_offset", v) }

    var lastSync: Long
        get() = sp.getLong("last_sync", 0)
        set(v) = edit { putLong("last_sync", v) }

    // --- notifications

    /** Battery level at which a warning is shown, 0 = off. */
    var lowBatteryAlert: Int
        get() = sp.getInt("low_battery", 15)
        set(v) = edit { putInt("low_battery", v) }

    var lowBatteryNotified: Boolean
        get() = sp.getBoolean("low_battery_notified", false)
        set(v) = edit { putBoolean("low_battery_notified", v) }

    /** Reminder before bedtime (next alarm minus sleep goal), minutes before; 0 = off. */
    var bedtimeReminder: Int
        get() = sp.getInt("bedtime_reminder", 0)
        set(v) = edit { putInt("bedtime_reminder", v) }

    var bedtimeNotifiedDay: Long
        get() = sp.getLong("bedtime_notified", 0)
        set(v) = edit { putLong("bedtime_notified", v) }

    /** Morning summary (sleep and recovery) after the first sync of the day. */
    var morningSummary: Boolean
        get() = sp.getBoolean("morning_summary", true)
        set(v) = edit { putBoolean("morning_summary", v) }

    var morningSummaryDay: Long
        get() = sp.getLong("morning_summary_day", 0)
        set(v) = edit { putLong("morning_summary_day", v) }

    /** Highest heart rate the app offered as new maximum, so it does not ask twice. */
    var suggestedMaxHr: Int
        get() = sp.getInt("suggested_max_hr", 0)
        set(v) = edit { putInt("suggested_max_hr", v) }

    var notifySyncErrors: Boolean
        get() = sp.getBoolean("notify_sync_errors", false)
        set(v) = edit { putBoolean("notify_sync_errors", v) }

    // --- appearance

    var themeMode: ThemeMode
        get() = ThemeMode.entries.firstOrNull { it.name == sp.getString("theme", null) } ?: ThemeMode.SYSTEM
        set(v) = edit { putString("theme", v.name) }

    var dynamicColor: Boolean
        get() = sp.getBoolean("dynamic_color", false)
        set(v) = edit { putBoolean("dynamic_color", v) }

    var accentColor: Long
        get() = sp.getLong("accent", 0xFFE5532F)
        set(v) = edit { putLong("accent", v) }

    var fontScale: Float
        get() = sp.getFloat("font_scale", 1f)
        set(v) = edit { putFloat("font_scale", v) }

    /** Dashboard tiles the user hid. */
    var hiddenTiles: Set<String>
        get() = sp.getStringSet("hidden_tiles", emptySet()).orEmpty()
        set(v) = edit { putStringSet("hidden_tiles", v) }

    var use24h: Boolean
        get() = sp.getBoolean("use_24h", true)
        set(v) = edit { putBoolean("use_24h", v) }

    // --- updates

    var autoCheckUpdates: Boolean
        get() = sp.getBoolean("auto_updates", true)
        set(v) = edit { putBoolean("auto_updates", v) }

    var includePrereleases: Boolean
        get() = sp.getBoolean("prereleases", false)
        set(v) = edit { putBoolean("prereleases", v) }

    var updateRepo: String
        get() = sp.getString("update_repo", null) ?: DEFAULT_UPDATE_REPO
        set(v) = edit { putString("update_repo", v) }

    /** Install updates through Shizuku without Android's confirmation. */
    var shizukuInstall: Boolean
        get() = sp.getBoolean("shizuku_install", false)
        set(v) = edit { putBoolean("shizuku_install", v) }

    /** With Shizuku: install updates in the background while the app is not open. */
    var autoInstallUpdates: Boolean
        get() = sp.getBoolean("auto_install", false)
        set(v) = edit { putBoolean("auto_install", v) }

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0)
        set(v) = edit { putLong("last_update_check", v) }

    var notifiedUpdateVersion: String
        get() = sp.getString("notified_update", "").orEmpty()
        set(v) = edit { putString("notified_update", v) }

    var skippedVersion: String
        get() = sp.getString("skipped_update", "").orEmpty()
        set(v) = edit { putString("skipped_update", v) }

    // --- heart rate recording and workouts

    /** Seconds between heart rate values the app records itself (realtime mode), 0 = off. */
    var continuousHrSeconds: Int
        get() = sp.getInt("continuous_hr", 0)
        set(v) = edit { putInt("continuous_hr", v) }

    var defaultSport: String
        get() = sp.getString("default_sport", null) ?: "Laufen"
        set(v) = edit { putString("default_sport", v) }

    /** The strap vibrates during a workout above this heart rate, 0 = off. */
    var workoutHrUpper: Int
        get() = sp.getInt("workout_hr_upper", 0)
        set(v) = edit { putInt("workout_hr_upper", v) }

    /** The strap vibrates during a workout below this heart rate (after the first 3 minutes), 0 = off. */
    var workoutHrLower: Int
        get() = sp.getInt("workout_hr_lower", 0)
        set(v) = edit { putInt("workout_hr_lower", v) }

    var notificationShowsHr: Boolean
        get() = sp.getBoolean("notification_hr", true)
        set(v) = edit { putBoolean("notification_hr", v) }

    // --- profile

    var profile: UserProfile
        get() = UserProfile(
            age = sp.getInt("age", 30),
            weightKg = sp.getInt("weight", 75),
            heightCm = sp.getInt("height", 178),
            male = sp.getBoolean("male", true),
            maxHrOverride = sp.getInt("max_hr", 0),
            stepGoal = sp.getInt("step_goal", 8000),
            sleepGoalMinutes = sp.getInt("sleep_goal", 480),
            restingHrManual = sp.getInt("resting_hr", 0),
            zonesFromReserve = sp.getBoolean("zones_reserve", false),
        )
        set(p) = edit {
            putInt("age", p.age)
            putInt("weight", p.weightKg)
            putInt("height", p.heightCm)
            putBoolean("male", p.male)
            putInt("max_hr", p.maxHrOverride)
            putInt("step_goal", p.stepGoal)
            putInt("sleep_goal", p.sleepGoalMinutes)
            putInt("resting_hr", p.restingHrManual)
            putBoolean("zones_reserve", p.zonesFromReserve)
        }

    // --- sync cursors

    override fun get(type: FetchType): Long? = sp.getLong("cursor_${type.name}", 0).takeIf { it > 0 }

    override fun set(type: FetchType, epochMillis: Long) {
        sp.edit().putLong("cursor_${type.name}", epochMillis).apply()
    }

    fun resetCursors() = edit { FetchType.entries.forEach { remove("cursor_${it.name}") } }

    fun forgetDevice() = edit {
        remove("address")
        remove("name")
        remove("auth_key")
        FetchType.entries.forEach { remove("cursor_${it.name}") }
    }

    /** All settings as JSON, for the backup (contains the auth key). */
    fun exportAll(): JSONObject {
        val json = JSONObject()
        for ((key, value) in sp.all) {
            val entry = JSONObject()
            when (value) {
                is Boolean -> entry.put("type", "boolean").put("value", value)
                is Int -> entry.put("type", "int").put("value", value)
                is Long -> entry.put("type", "long").put("value", value)
                is Float -> entry.put("type", "float").put("value", value.toDouble())
                is String -> entry.put("type", "string").put("value", value)
                else -> continue
            }
            json.put(key, entry)
        }
        return json
    }

    fun importAll(json: JSONObject) = edit {
        clear()
        for (key in json.keys()) {
            val entry = json.optJSONObject(key) ?: continue
            when (entry.optString("type")) {
                "boolean" -> putBoolean(key, entry.optBoolean("value"))
                "int" -> putInt(key, entry.optInt("value"))
                "long" -> putLong(key, entry.optLong("value"))
                "float" -> putFloat(key, entry.optDouble("value").toFloat())
                "string" -> putString(key, entry.optString("value"))
            }
        }
    }

    companion object {
        const val DEFAULT_UPDATE_REPO = "Waschbeken/Waschbeken1"
    }
}
