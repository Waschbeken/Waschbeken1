package de.waschbeken.heliolink.service

import de.waschbeken.heliolink.ble.LinkState
import de.waschbeken.heliolink.core.HealthConfig
import de.waschbeken.heliolink.core.HealthSetting
import de.waschbeken.heliolink.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LiveWorkout(
    val id: Long,
    val sport: String,
    val start: Long, // epoch seconds
    val samples: Int = 0,
    val avgHr: Int = 0,
    val maxHr: Int = 0,
    val calories: Double = 0.0,
    val zoneSeconds: List<Int> = List(5) { 0 },
)

data class StrapUiState(
    val link: LinkState = LinkState.DISCONNECTED,
    val linkDetail: String? = null,
    val battery: Int? = null,
    val charging: Boolean = false,
    val steps: Long? = null,
    val distanceMeters: Long? = null,
    val calories: Long? = null,
    val heartRate: Int? = null,
    val heartRateAt: Long = 0,
    val hrv: Double? = null,
    val liveHeartRate: Boolean = false,
    val asleep: Boolean? = null,
    val settings: HealthConfig? = null,
    /** Strap settings sent but not yet confirmed. */
    val pendingSettings: Set<HealthSetting> = emptySet(),
    /** Strap settings the strap did not take over. */
    val rejectedSettings: Set<HealthSetting> = emptySet(),
    /** Heart rate values the app recorded today (continuous recording). */
    val recordingHr: Boolean = false,
    /** The strap answered with its alarms at least once. */
    val alarmsLoaded: Boolean = false,
    /** A workout went above the maximum heart rate: offer this value. */
    val maxHrSuggestion: Int? = null,
    val syncing: Boolean = false,
    val syncText: String? = null,
    val lastSync: Long = 0,
    val firmware: String? = null,
    val serial: String? = null,
    val workout: LiveWorkout? = null,
    val dataVersion: Int = 0,
    val log: List<String> = emptyList(),
) {
    val connected get() = link == LinkState.READY
}

/** Commands the UI can send to the running [StrapService]. */
interface StrapController {
    fun syncNow()
    fun probeSleepCode()
    fun saveAlarm(alarm: de.waschbeken.heliolink.core.StrapAlarm, label: String)
    fun deleteAlarm(position: Int)
    fun refreshAlarms()
    fun setLiveHeartRate(enabled: Boolean)
    fun findStrap()
    fun refresh()
    fun applySettings(values: Map<HealthSetting, Int>, lists: Map<HealthSetting, List<Int>> = emptyMap())
    fun startWorkout(sport: String)
    fun stopWorkout()
    fun reconnect()
    fun shutdown()
}

class StrapRepository(prefs: Prefs) {
    private val _state = MutableStateFlow(StrapUiState(lastSync = prefs.lastSync))
    val state: StateFlow<StrapUiState> = _state.asStateFlow()

    @Volatile
    var controller: StrapController? = null

    fun update(block: (StrapUiState) -> StrapUiState) = _state.update(block)

    fun log(message: String) = _state.update {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date())
        it.copy(log = (listOf("$time  $message") + it.log).take(200))
    }

    fun dataChanged() = _state.update { it.copy(dataVersion = it.dataVersion + 1) }
}
