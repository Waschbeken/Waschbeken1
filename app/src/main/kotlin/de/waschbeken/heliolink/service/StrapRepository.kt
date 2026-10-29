package de.waschbeken.heliolink.service

import de.waschbeken.heliolink.ble.LinkState
import de.waschbeken.heliolink.core.HealthConfig
import de.waschbeken.heliolink.core.HealthSetting
import de.waschbeken.heliolink.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Sport name of a heart rate recording started by tapping the current heart rate. */
const val RECORDING_SPORT = "Pulsaufzeichnung"

data class LiveWorkout(
    val id: Long,
    val sport: String,
    val start: Long, // epoch seconds
    /** A recording stops by itself at this time (epoch seconds), null = until stopped. */
    val plannedEnd: Long? = null,
    val samples: Int = 0,
    val avgHr: Int = 0,
    val maxHr: Int = 0,
    val calories: Double = 0.0,
    val zoneSeconds: List<Int> = List(5) { 0 },
    /** Interval training: the strap vibrates at every change between effort and rest. */
    val intervals: de.waschbeken.heliolink.core.IntervalPlan? = null,
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
    /** Focus timer (focus = effort, break = rest of the plan) and when it started, epoch seconds. */
    val focus: Pair<Long, de.waschbeken.heliolink.core.IntervalPlan>? = null,
    /** After a workout the pulse keeps being measured until then (epoch seconds) for the heart rate recovery. */
    val cooldownUntil: Long? = null,
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
    /** A workout with intervals; the strap vibrates at each change and the workout ends after the last round. */
    fun startIntervals(sport: String, plan: de.waschbeken.heliolink.core.IntervalPlan)
    /** Records the heart rate every second for [seconds] (null = until stopped) and saves it like a workout. */
    fun startRecording(seconds: Int?)
    /** Pauses the connection so another app (Zepp, e.g. for a firmware update) can use the strap. */
    fun setPaused(paused: Boolean)
    fun stopWorkout()
    /** Lets the strap vibrate [count] times for [onMs] milliseconds each. */
    fun buzz(count: Int, onMs: Long)
    /** Focus timer: the strap vibrates at the start of each focus time and each break. */
    fun startFocus(plan: de.waschbeken.heliolink.core.IntervalPlan)
    fun stopFocus()
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

    /** Beat intervals (ms) as the strap sends them while the pulse is streamed, for measurements on screen. */
    val beats = kotlinx.coroutines.flow.MutableSharedFlow<List<Int>>(extraBufferCapacity = 256)
}
