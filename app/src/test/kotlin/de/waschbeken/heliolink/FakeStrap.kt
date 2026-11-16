package de.waschbeken.heliolink

import de.waschbeken.heliolink.core.HealthSetting
import de.waschbeken.heliolink.core.IntervalPlan
import de.waschbeken.heliolink.core.StrapAlarm
import de.waschbeken.heliolink.service.StrapController

/** Stands in for the strap service in UI and receiver tests: remembers what was asked of it. */
class FakeStrap(val app: HelioApp) : StrapController {
    /** Tap test switched on (true) and off (false). */
    val calls = ArrayList<Boolean>()

    /** "Länger wecken": started with these seconds; stops counted. */
    val wakeStarts = ArrayList<Int>()
    var wakeStops = 0

    override fun traceTaps(on: Boolean) {
        calls += on
        // like the service: ready once it listens
        if (on) app.repo.traceReady.value = "bereit"
    }
    override fun startWakeBoost(seconds: Int) { wakeStarts += seconds }
    override fun stopWakeBoost() { wakeStops++ }
    override fun syncNow() = Unit
    override fun probeSleepCode() = Unit
    override fun saveAlarm(alarm: StrapAlarm, label: String) = Unit
    override fun deleteAlarm(position: Int) = Unit
    override fun refreshAlarms() = Unit
    override fun setLiveHeartRate(enabled: Boolean) = Unit
    override fun findStrap() = Unit
    override fun refresh() = Unit
    override fun applySettings(values: Map<HealthSetting, Int>, lists: Map<HealthSetting, List<Int>>) = Unit
    override fun startWorkout(sport: String, gps: Boolean) = Unit
    override fun startIntervals(sport: String, plan: IntervalPlan, gps: Boolean) = Unit
    override fun startRecording(seconds: Int?) = Unit
    override fun setPaused(paused: Boolean) = Unit
    override fun stopWorkout() = Unit
    override fun buzz(count: Int, onMs: Long) = Unit
    override fun startFocus(plan: IntervalPlan) = Unit
    override fun stopFocus() = Unit
    override fun reconnect() = Unit
    override fun shutdown() = Unit
}
