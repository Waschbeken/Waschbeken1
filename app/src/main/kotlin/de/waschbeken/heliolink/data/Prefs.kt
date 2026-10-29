package de.waschbeken.heliolink.data

import android.content.Context
import de.waschbeken.heliolink.core.FetchType
import de.waschbeken.heliolink.core.HeartRateZones
import de.waschbeken.heliolink.core.SyncCursor
import de.waschbeken.heliolink.core.parseHex

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

class Prefs(context: Context) : SyncCursor {
    private val sp = context.getSharedPreferences("heliolink", Context.MODE_PRIVATE)

    var deviceAddress: String?
        get() = sp.getString("address", null)
        set(v) = sp.edit().putString("address", v).apply()

    var deviceName: String?
        get() = sp.getString("name", null)
        set(v) = sp.edit().putString("name", v).apply()

    var authKeyHex: String?
        get() = sp.getString("auth_key", null)
        set(v) = sp.edit().putString("auth_key", v).apply()

    val authKey: ByteArray? get() = authKeyHex?.let { parseHex(it) }?.takeIf { it.size == 16 }

    val isConfigured get() = deviceAddress != null && authKey != null

    var autoSyncMinutes: Int
        get() = sp.getInt("auto_sync", 30)
        set(v) = sp.edit().putInt("auto_sync", v).apply()

    var lastSync: Long
        get() = sp.getLong("last_sync", 0)
        set(v) = sp.edit().putLong("last_sync", v).apply()

    var profile: UserProfile
        get() = UserProfile(
            age = sp.getInt("age", 30),
            weightKg = sp.getInt("weight", 75),
            heightCm = sp.getInt("height", 178),
            male = sp.getBoolean("male", true),
            maxHrOverride = sp.getInt("max_hr", 0),
            stepGoal = sp.getInt("step_goal", 8000),
        )
        set(p) = sp.edit()
            .putInt("age", p.age)
            .putInt("weight", p.weightKg)
            .putInt("height", p.heightCm)
            .putBoolean("male", p.male)
            .putInt("max_hr", p.maxHrOverride)
            .putInt("step_goal", p.stepGoal)
            .apply()

    override fun get(type: FetchType): Long? = sp.getLong("cursor_${type.name}", 0).takeIf { it > 0 }

    override fun set(type: FetchType, epochMillis: Long) {
        sp.edit().putLong("cursor_${type.name}", epochMillis).apply()
    }

    fun resetCursors() {
        val e = sp.edit()
        FetchType.entries.forEach { e.remove("cursor_${it.name}") }
        e.apply()
    }

    fun forgetDevice() {
        sp.edit().remove("address").remove("name").remove("auth_key").apply()
        resetCursors()
    }
}
