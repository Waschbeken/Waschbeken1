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

data class UserProfile(
    val age: Int = 30,
    val weightKg: Int = 75,
    val heightCm: Int = 178,
    val male: Boolean = true,
    val maxHrOverride: Int = 0,
    val stepGoal: Int = 8000,
) {
    val maxHeartRate get() = if (maxHrOverride > 0) maxHrOverride else HeartRateZones.maxHeartRate(age)
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

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0)
        set(v) = edit { putLong("last_update_check", v) }

    var notifiedUpdateVersion: String
        get() = sp.getString("notified_update", "").orEmpty()
        set(v) = edit { putString("notified_update", v) }

    var skippedVersion: String
        get() = sp.getString("skipped_update", "").orEmpty()
        set(v) = edit { putString("skipped_update", v) }

    // --- profile

    var profile: UserProfile
        get() = UserProfile(
            age = sp.getInt("age", 30),
            weightKg = sp.getInt("weight", 75),
            heightCm = sp.getInt("height", 178),
            male = sp.getBoolean("male", true),
            maxHrOverride = sp.getInt("max_hr", 0),
            stepGoal = sp.getInt("step_goal", 8000),
        )
        set(p) = edit {
            putInt("age", p.age)
            putInt("weight", p.weightKg)
            putInt("height", p.heightCm)
            putBoolean("male", p.male)
            putInt("max_hr", p.maxHrOverride)
            putInt("step_goal", p.stepGoal)
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

    companion object {
        const val DEFAULT_UPDATE_REPO = "Waschbeken/Waschbeken1"
    }
}
