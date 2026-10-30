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

    // --- sleep

    /** Awake periods shorter than this many minutes count as light sleep, 0 = keep all. */
    var sleepIgnoreAwake: Int
        get() = sp.getInt("sleep_ignore_awake", 0)
        set(v) = edit { putInt("sleep_ignore_awake", v) }

    /** Awake gaps up to this many minutes stay inside one night. */
    var sleepMaxGap: Int
        get() = sp.getInt("sleep_max_gap", 45)
        set(v) = edit { putInt("sleep_max_gap", v) }

    /** Shorter sleep is not shown as a night. */
    var sleepMinMinutes: Int
        get() = sp.getInt("sleep_min_minutes", 20)
        set(v) = edit { putInt("sleep_min_minutes", v) }

    /** Always estimate the stages, even when a strap or Gadgetbridge record exists. */
    var sleepAlwaysEstimate: Boolean
        get() = sp.getBoolean("sleep_always_estimate", false)
        set(v) = edit { putBoolean("sleep_always_estimate", v) }

    /** Ask "Stimmt dein Schlaf?" on the sleep page each morning. */
    var sleepAskCheck: Boolean
        get() = sp.getBoolean("sleep_ask_check", true)
        set(v) = edit { putBoolean("sleep_ask_check", v) }

    /** Morning (epoch day) for which the user already confirmed or corrected the night. */
    var sleepCheckedDay: Long
        get() = sp.getLong("sleep_checked_day", 0)
        set(v) = edit { putLong("sleep_checked_day", v) }

    var sleepOnsetDelay: Int
        get() = sp.getInt("sleep_onset_delay", 0)
        set(v) = edit { putInt("sleep_onset_delay", v) }

    var sleepWakeEarlier: Int
        get() = sp.getInt("sleep_wake_earlier", 0)
        set(v) = edit { putInt("sleep_wake_earlier", v) }

    val sleepTuning get() = de.waschbeken.heliolink.core.SleepTuning(
        sleepIgnoreAwake, sleepMaxGap, sleepMinMinutes, sleepOnsetDelay, sleepWakeEarlier,
    )

    // --- automatic backup

    /** Days between automatic backups, 0 = off. */
    var autoBackupDays: Int
        get() = sp.getInt("auto_backup_days", 1)
        set(v) = edit { putInt("auto_backup_days", v) }

    /** How many automatic backups are kept. */
    var autoBackupKeep: Int
        get() = sp.getInt("auto_backup_keep", 7)
        set(v) = edit { putInt("auto_backup_keep", v) }

    /** Folder (document tree) chosen for automatic backups, null = Downloads/HelioLink. */
    var autoBackupTree: String?
        get() = sp.getString("auto_backup_tree", null)
        set(v) = edit { if (v == null) remove("auto_backup_tree") else putString("auto_backup_tree", v) }

    var autoBackupBeforeUpdate: Boolean
        get() = sp.getBoolean("auto_backup_update", true)
        set(v) = edit { putBoolean("auto_backup_update", v) }

    var lastAutoBackup: Long
        get() = sp.getLong("last_auto_backup", 0)
        set(v) = edit { putLong("last_auto_backup", v) }

    /** Where the last automatic backup went, or the error of the last attempt. */
    var lastAutoBackupInfo: String
        get() = sp.getString("last_auto_backup_info", "").orEmpty()
        set(v) = edit { putString("last_auto_backup_info", v) }

    var lastAutoBackupFailed: Boolean
        get() = sp.getBoolean("last_auto_backup_failed", false)
        set(v) = edit { putBoolean("last_auto_backup_failed", v) }

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

    /** Weekly report on Monday morning: sleep, score and steps compared with the week before. */
    var weeklySummary: Boolean
        get() = sp.getBoolean("weekly_summary", true)
        set(v) = edit { putBoolean("weekly_summary", v) }

    var weeklySummaryDay: Long
        get() = sp.getLong("weekly_summary_day", 0)
        set(v) = edit { putLong("weekly_summary_day", v) }

    var morningSummaryDay: Long
        get() = sp.getLong("morning_summary_day", 0)
        set(v) = edit { putLong("morning_summary_day", v) }

    /** Notify when the night values look like the body is fighting something (see BodyCheck). */
    var bodyCheckNotify: Boolean
        get() = sp.getBoolean("body_check_notify", true)
        set(v) = edit { putBoolean("body_check_notify", v) }

    /** Time of the newest irregular heart rhythm the user already acknowledged. */
    var rhythmHintSeen: Long
        get() = sp.getLong("rhythm_hint_seen", 0)
        set(v) = edit { putLong("rhythm_hint_seen", v) }

    var bodyCheckDay: Long
        get() = sp.getLong("body_check_day", 0)
        set(v) = edit { putLong("body_check_day", v) }

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

    /** Buttons for quick entries on the start page (caffeine, nicotine, …), editable by the user. */
    var quickTags: List<String>
        get() = sp.getString("quick_tags", null)?.split('\n')?.filter { it.isNotBlank() } ?: DEFAULT_QUICK_TAGS
        set(v) = edit { putString("quick_tags", v.distinct().joinToString("\n")) }

    /** Personal goals ("Fortschritt"), e.g. sleep ≥ 7 h on 5 days a week. */
    var goals: List<de.waschbeken.heliolink.core.Goal>
        get() = sp.getString("goals", null)?.split('\n')?.mapNotNull { de.waschbeken.heliolink.core.Goal.decode(it) } ?: emptyList()
        set(v) = edit { putString("goals", v.joinToString("\n") { it.encode() }) }

    /** The start page told about the "Fortschritt" page once. */
    var progressHintSeen: Boolean
        get() = sp.getBoolean("progress_hint_seen", false)
        set(v) = edit { putBoolean("progress_hint_seen", v) }

    /** Entries counted as "Tage ohne …" (e.g. Nikotin, Alkohol). */
    var withoutTags: List<String>
        get() = sp.getString("without_tags", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = edit { putString("without_tags", v.distinct().joinToString("\n")) }

    /** The connection is paused so the Zepp app can use the strap (e.g. for a firmware update). */
    var connectionPaused: Boolean
        get() = sp.getBoolean("connection_paused", false)
        set(v) = edit { putBoolean("connection_paused", v) }

    /** Opened or closed lists ("key=1" / "key=0"), so they stay as the user left them. */
    private var sectionStates: Set<String>
        get() = sp.getStringSet("section_states", emptySet()).orEmpty()
        set(v) = edit { putStringSet("section_states", v) }

    fun isExpanded(key: String, default: Boolean): Boolean =
        sectionStates.firstOrNull { it.startsWith("$key=") }?.endsWith("=1") ?: default

    fun setExpanded(key: String, expanded: Boolean) {
        sectionStates = sectionStates.filterNot { it.startsWith("$key=") }.toSet() + "$key=${if (expanded) 1 else 0}"
    }

    /** Order of the dashboard tiles (names), tiles not in the list follow in their default order. */
    var tileOrder: List<String>
        get() = sp.getString("tile_order", "").orEmpty().split(',').filter { it.isNotBlank() }
        set(v) = edit { putString("tile_order", v.joinToString(",")) }

    var use24h: Boolean
        get() = sp.getBoolean("use_24h", true)
        set(v) = edit { putBoolean("use_24h", v) }

    // --- privacy

    /** Password for HelioLink's backups; null = backups are not encrypted. Never part of a backup itself. */
    var backupPassword: String?
        get() = sp.getString(KEY_BACKUP_PASSWORD, null)?.takeIf { it.isNotEmpty() }
        set(v) = edit { putString(KEY_BACKUP_PASSWORD, v) }

    /** Ask for fingerprint, face or the screen lock PIN when the app opens. */
    var appLock: Boolean
        get() = sp.getBoolean("app_lock", false)
        set(v) = edit { putBoolean("app_lock", v) }

    /** Seconds in the background after which the app locks again. */
    var appLockDelay: Int
        get() = sp.getInt("app_lock_delay", 60)
        set(v) = edit { putInt("app_lock_delay", v) }

    /** The last internet connections of the app ("epoch millis|what"), newest first. */
    val networkLog: List<Pair<Long, String>>
        get() = sp.getString("network_log", "").orEmpty().split('\n').mapNotNull { line ->
            val t = line.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
            t to line.substringAfter('|')
        }

    fun logNetwork(what: String) {
        val lines = listOf("${System.currentTimeMillis()}|${what.replace('\n', ' ')}") +
            sp.getString("network_log", "").orEmpty().split('\n').filter { it.isNotBlank() }
        edit { putString("network_log", lines.take(30).joinToString("\n")) }
    }

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

    /** Last focus timer ("focus,break,rounds" in seconds). */
    var focusPlan: String?
        get() = sp.getString("focus_plan", null)
        set(v) = edit { putString("focus_plan", v) }

    /** Breathing exercise: minutes and whether the strap gives the pace. */
    var breatheMinutes: Int
        get() = sp.getInt("breathe_minutes", 5)
        set(v) = edit { putInt("breathe_minutes", v) }

    var breatheBuzz: Boolean
        get() = sp.getBoolean("breathe_buzz", false)
        set(v) = edit { putBoolean("breathe_buzz", v) }

    // --- strap vibrates for notifications

    var armNotify: Boolean
        get() = sp.getBoolean("arm_notify", false)
        set(v) = edit { putBoolean("arm_notify", v) }

    var armCalls: Boolean
        get() = sp.getBoolean("arm_calls", true)
        set(v) = edit { putBoolean("arm_calls", v) }

    /** Apps whose notifications let the strap vibrate. */
    var armApps: Set<String>
        get() = sp.getStringSet("arm_apps", emptySet()).orEmpty()
        set(v) = edit { putStringSet("arm_apps", v) }

    /** Ask "Was war los?" after a longer stretch of high stress. */
    var stressPrompt: Boolean
        get() = sp.getBoolean("stress_prompt", true)
        set(v) = edit { putBoolean("stress_prompt", v) }

    /** End of the last stress stretch the app asked about (epoch seconds) and how often it asked today. */
    var stressPromptLast: Long
        get() = sp.getLong("stress_prompt_last", 0)
        set(v) = edit { putLong("stress_prompt_last", v) }

    var stressPromptToday: String
        get() = sp.getString("stress_prompt_today", "").orEmpty()
        set(v) = edit { putString("stress_prompt_today", v) }

    /** Last interval plan ("work,rest,rounds"). */
    var intervalPlan: String?
        get() = sp.getString("interval_plan", null)
        set(v) = edit { putString("interval_plan", v) }

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
        const val KEY_BACKUP_PASSWORD = "backup_password"
        const val DEFAULT_UPDATE_REPO = "Waschbeken/Waschbeken1"
        val DEFAULT_QUICK_TAGS = listOf("Koffein", "Nikotin", "Alkohol", "Essen", "Medikament", "Sport", "Stress", "Kopfschmerzen", "Müde")

        /** What you are doing during a heart rate recording. */
        val ACTIVITY_TAGS = listOf("Sitzen", "Liegen", "Stehen", "Gehen", "Laufen", "Radfahren", "Sport", "Lernen/Arbeit", "Zocken", "Entspannen", "Aufgeregt")
    }
}
