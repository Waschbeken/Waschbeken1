package de.waschbeken.heliolink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.waschbeken.heliolink.R
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.ble.LinkState
import de.waschbeken.heliolink.ble.StrapConnection
import de.waschbeken.heliolink.core.Calories
import de.waschbeken.heliolink.core.DeviceInfo
import de.waschbeken.heliolink.core.HealthSetting
import de.waschbeken.heliolink.core.HeartRateMode
import de.waschbeken.heliolink.core.IntervalPlan
import de.waschbeken.heliolink.core.HeartRateZones
import de.waschbeken.heliolink.core.SleepAnalyzer
import de.waschbeken.heliolink.core.SleepStage
import de.waschbeken.heliolink.core.StrapAlarm
import de.waschbeken.heliolink.core.StrapEvent
import de.waschbeken.heliolink.core.StrapProtocol
import de.waschbeken.heliolink.core.ValueSample
import de.waschbeken.heliolink.core.nextAlarm
import de.waschbeken.heliolink.AppVisibility
import de.waschbeken.heliolink.widget.HelioWidget
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import de.waschbeken.heliolink.core.minuteRmssd
import de.waschbeken.heliolink.core.rmssd
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.data.Workout
import de.waschbeken.heliolink.ui.MainActivity
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foreground service that keeps the connection to the strap alive, downloads the history in the
 * background, keeps realtime heart rate running and records workouts.
 */
class StrapService : Service(), StrapController, StrapConnection.Listener {

    companion object {
        private const val CHANNEL = "strap"
        private const val CHANNEL_ALERTS = Notifications.CHANNEL_ALERTS
        private const val CHANNEL_UPDATES = Notifications.CHANNEL_UPDATES
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_BATTERY = Notifications.ID_BATTERY
        private const val NOTIFICATION_SYNC = Notifications.ID_SYNC
        private const val NOTIFICATION_UPDATE = Notifications.ID_UPDATE
        private const val ACTION_STOP = "de.waschbeken.heliolink.STOP"

        const val PAUSED_TEXT = "Pausiert – der Strap ist frei für die Zepp-App"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, StrapService::class.java))
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var connection: StrapConnection? = null

    private val repo get() = app.repo
    private val prefs get() = app.prefs
    private val db get() = app.db

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var liveRequested = false
    private var probeOffset = 0
    private var lastBatteryStored = 0L
    private var lastWidgetUpdate = 0L
    private var hrStreaming = false
    private val rrBuffer = ArrayList<Int>()

    private var lastWorkoutSecond = 0L
    private var workoutHrSum = 0L
    private var lastRecordedSecond = 0L
    private var lastVibration = 0L
    private var lastHrNotification = 0L

    /** Strap settings sent and the values we expect back. */
    private val pendingValues = HashMap<HealthSetting, Int>()
    private val pendingLists = HashMap<HealthSetting, List<Int>>()

    private val hrKeepAlive = object : Runnable {
        override fun run() {
            if (!hrStreaming) return
            connection?.protocol?.setRealtimeHeartRate(HeartRateMode.CONTINUE)
            handler.postDelayed(this, 10_000)
        }
    }

    private val periodic = object : Runnable {
        override fun run() {
            val c = connection
            if (c != null && c.state == LinkState.READY) {
                c.protocol.requestBattery()
                c.protocol.requestSteps()
                val minutes = prefs.autoSyncMinutes
                if (minutes > 0 && System.currentTimeMillis() - prefs.lastSync > minutes * 60_000L) syncNow()
            }
            checkForUpdateInBackground()
            checkBedtime()
            handler.postDelayed(this, 5 * 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("strap").also { it.start() }
        handler = object : Handler(thread.looper) {
            // A single unexpected packet from the strap must not close the app: log it and go on.
            override fun dispatchMessage(msg: android.os.Message) {
                try {
                    super.dispatchMessage(msg)
                } catch (e: Exception) {
                    repo.log("Fehler abgefangen: ${e.javaClass.simpleName}: ${e.message}")
                    de.waschbeken.heliolink.data.CrashReporter.nonFatal(this@StrapService, e)
                }
            }
        }
        createChannel()
        repo.controller = this
        // React to settings that change the heart rate streaming (continuous recording).
        scope.launch {
            var last = prefs.continuousHrSeconds
            prefs.changes.collect {
                val now = prefs.continuousHrSeconds
                if (now != last) {
                    last = now
                    handler.post { updateHeartRateStreaming() }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            // Missing Bluetooth permission or background start restriction.
            repo.log("Dienst konnte nicht starten: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        handler.post { ensureConnection() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        repo.controller = null
        handler.post {
            connection?.close()
            connection = null
        }
        thread.quitSafely()
        super.onDestroy()
    }

    private fun ensureConnection() {
        val address = prefs.deviceAddress
        val key = prefs.authKey
        if (address == null || key == null) {
            stopSelf()
            return
        }
        if (prefs.connectionPaused) {
            repo.update { it.copy(link = LinkState.DISCONNECTED, linkDetail = PAUSED_TEXT) }
            updateNotification()
            return
        }
        if (connection == null) {
            connection = StrapConnection(applicationContext, address, key, handler, this)
            handler.removeCallbacks(periodic)
            handler.postDelayed(periodic, 5 * 60_000L)
        }
        connection?.connect()
    }

    // ------------------------------------------------------------------ StrapController (any thread)

    override fun syncNow() {
        handler.post {
            val days = prefs.historyDays.toLong()
            val sleepCode = prefs.sleepCode.takeIf { it > 0 }
            val offset = prefs.sleepOffset
            connection?.startSync {
                sleepRecordOffset = offset
                startSync(prefs, defaultHistory = Duration.ofDays(days), sleepCode = sleepCode)
            }
        }
    }

    override fun probeSleepCode() {
        handler.post {
            repo.update { it.copy(syncing = true, syncText = "Suche Schlafphasen-Daten auf dem Strap …") }
            repo.log("Suche, unter welchem Code der Strap Schlafphasen liefert …")
            val tried = prefs.sleepProbeTried.coerceIn(0, StrapProtocol.SLEEP_CODE_CANDIDATES.size)
            probeOffset = tried
            connection?.startSync { probeSleepCode(prefs, StrapProtocol.SLEEP_CODE_CANDIDATES.drop(tried)) }
        }
    }

    override fun saveAlarm(alarm: StrapAlarm, label: String) {
        handler.post {
            val ready = connection?.state == LinkState.READY
            db.saveAlarm(alarm, label, pending = !ready)
            if (ready) connection?.protocol?.setAlarm(alarm)
            repo.dataChanged()
        }
    }

    override fun deleteAlarm(position: Int) {
        handler.post {
            if (connection?.state == LinkState.READY) {
                connection?.protocol?.deleteAlarm(position)
                db.removeAlarm(position)
            } else {
                db.alarms().firstOrNull { it.alarm.position == position }?.let {
                    db.saveAlarm(it.alarm, it.label, pending = true, deleted = true)
                }
            }
            repo.dataChanged()
        }
    }

    override fun refreshAlarms() {
        handler.post { connection?.protocol?.requestAlarms() }
    }

    /** Sends alarm changes made while the strap was not connected. */
    private fun pushPendingAlarms() {
        val p = connection?.protocol ?: return
        for (row in db.alarms().filter { it.pending }) {
            if (row.deleted) {
                p.deleteAlarm(row.alarm.position)
                db.removeAlarm(row.alarm.position)
            } else {
                p.setAlarm(row.alarm)
                db.saveAlarm(row.alarm, row.label, pending = false)
            }
        }
    }

    override fun setLiveHeartRate(enabled: Boolean) {
        handler.post {
            liveRequested = enabled
            repo.update { it.copy(liveHeartRate = enabled) }
            updateHeartRateStreaming()
        }
    }

    override fun findStrap() {
        handler.post {
            val p = connection?.protocol ?: return@post
            p.findStrap(true)
            handler.postDelayed({ connection?.protocol?.findStrap(false) }, 6_000)
        }
    }

    override fun setPaused(paused: Boolean) {
        handler.post {
            prefs.connectionPaused = paused
            if (paused) {
                connection?.close()
                connection = null
                hrStreaming = false
                repo.update { it.copy(link = LinkState.DISCONNECTED, linkDetail = PAUSED_TEXT, syncing = false, liveHeartRate = false) }
                repo.log("Verbindung pausiert – der Strap ist frei für die Zepp-App")
                updateNotification()
            } else {
                repo.log("Verbindung fortgesetzt")
                ensureConnection()
            }
        }
    }

    override fun refresh() {
        handler.post {
            val c = connection ?: return@post
            if (c.state == LinkState.READY) {
                c.protocol.requestBattery()
                c.protocol.requestSteps()
            } else if (c.state == LinkState.DISCONNECTED) {
                c.connect()
            }
        }
    }

    override fun applySettings(values: Map<HealthSetting, Int>, lists: Map<HealthSetting, List<Int>>) {
        handler.post {
            val p = connection?.protocol ?: return@post
            pendingValues.putAll(values)
            pendingLists.putAll(lists)
            val keys = values.keys + lists.keys
            repo.update { s ->
                s.copy(
                    pendingSettings = s.pendingSettings + keys,
                    rejectedSettings = s.rejectedSettings - keys,
                    // Show the new value right away, the strap's answer corrects it if needed.
                    settings = s.settings?.let { c -> c.copy(values = c.values + values, lists = c.lists + lists) },
                )
            }
            p.changeHealthSettings(values, lists)
            handler.removeCallbacks(verifySettings)
            handler.postDelayed(verifySettings, 1_500)
        }
    }

    private val verifySettings = Runnable { connection?.protocol?.requestHealthSettings() }

    /** Compares what the strap reports with the changes we sent. */
    private fun checkPendingSettings(update: de.waschbeken.heliolink.core.HealthConfig) {
        if (pendingValues.isEmpty() && pendingLists.isEmpty()) return
        val accepted = HashSet<HealthSetting>()
        val rejected = HashSet<HealthSetting>()
        for ((setting, wanted) in pendingValues.toMap()) {
            val actual = update.values[setting] ?: continue
            pendingValues.remove(setting)
            if (actual == wanted || (actual and 0xff) == (wanted and 0xff)) accepted += setting else rejected += setting
        }
        for ((setting, wanted) in pendingLists.toMap()) {
            val actual = update.lists[setting] ?: continue
            pendingLists.remove(setting)
            if (actual.sorted() == wanted.sorted()) accepted += setting else rejected += setting
        }
        if (rejected.isNotEmpty()) repo.log("Vom Strap nicht übernommen: ${rejected.joinToString()}")
        repo.update {
            it.copy(
                pendingSettings = it.pendingSettings - accepted - rejected,
                rejectedSettings = it.rejectedSettings - accepted + rejected,
            )
        }
    }

    override fun startWorkout(sport: String) = begin(sport, null)

    override fun startIntervals(sport: String, plan: IntervalPlan) = begin(sport, null, plan)

    override fun startRecording(seconds: Int?) = begin(RECORDING_SPORT, seconds)

    private val plannedStop = Runnable {
        val w = repo.state.value.workout
        if (w?.plannedEnd != null && System.currentTimeMillis() / 1000 >= w.plannedEnd - 1) stopWorkout()
    }

    private fun begin(sport: String, seconds: Int?, intervals: IntervalPlan? = null) {
        handler.post {
            if (repo.state.value.workout != null) return@post
            endCooldown()
            val start = System.currentTimeMillis() / 1000
            val id = db.startWorkout(sport, start)
            lastWorkoutSecond = 0
            workoutHrSum = 0
            lastVibration = 0
            if (sport != RECORDING_SPORT) prefs.defaultSport = sport
            val end = seconds?.let { start + it }
            repo.update { it.copy(workout = LiveWorkout(id, sport, start, plannedEnd = end, intervals = intervals)) }
            handler.removeCallbacks(plannedStop)
            if (seconds != null) handler.postDelayed(plannedStop, seconds * 1000L)
            lastPhase = null
            handler.removeCallbacks(intervalTick)
            if (intervals != null) {
                prefs.intervalPlan = intervals.encode()
                handler.post(intervalTick)
            }
            updateHeartRateStreaming()
            updateNotification()
        }
    }

    override fun stopWorkout() {
        handler.post {
            handler.removeCallbacks(plannedStop)
            handler.removeCallbacks(intervalTick)
            val w = repo.state.value.workout ?: return@post
            val end = System.currentTimeMillis() / 1000
            db.finishWorkout(
                Workout(w.id, w.sport, w.start, end, w.avgHr, w.maxHr, w.calories.roundToInt(), w.zoneSeconds)
            )
            // Heart rate recovery: keep measuring for a minute after a workout (not after a plain recording).
            val state = repo.state.value
            val hrEnd = state.heartRate?.takeIf { System.currentTimeMillis() - state.heartRateAt < 10_000 }
            if (w.sport != RECORDING_SPORT && hrEnd != null && end - w.start >= 5 * 60) {
                cooldown = Cooldown(w.id, end, hrEnd)
                handler.postDelayed(cooldownTimeout, 100_000)
            }
            repo.update { it.copy(workout = null, cooldownUntil = cooldown?.let { c -> c.end + 60 }) }
            repo.dataChanged()
            suggestMaxHeartRate(w.maxHr)
            updateHeartRateStreaming()
            updateNotification()
        }
    }

    // ------------------------------------------------------------------ intervals and heart rate recovery

    private var lastPhase: IntervalPlan.Phase? = null

    /** Once a second during interval training: vibrates at every change and ends the workout after the last round. */
    private val intervalTick = object : Runnable {
        override fun run() {
            val w = repo.state.value.workout ?: return
            val plan = w.intervals ?: return
            val phase = plan.phaseAt(System.currentTimeMillis() / 1000 - w.start)
            val before = lastPhase
            if (phase == null) {
                buzz(3, 400)
                repo.log("Intervalle fertig")
                stopWorkout()
                return
            }
            if (before == null || before.work != phase.work || before.round != phase.round) {
                if (phase.work) buzz(2, 450) else buzz(1, 1_300)
            }
            lastPhase = phase
            handler.postDelayed(this, 1_000)
        }
    }

    override fun buzz(count: Int, onMs: Long) {
        for (i in 0 until count) {
            val at = i * (onMs + 450)
            handler.postDelayed({ connection?.protocol?.findStrap(true) }, at)
            handler.postDelayed({ connection?.protocol?.findStrap(false) }, at + onMs)
        }
    }

    private var lastFocusPhase: IntervalPlan.Phase? = null

    override fun startFocus(plan: IntervalPlan) {
        handler.post {
            repo.update { it.copy(focus = System.currentTimeMillis() / 1000 to plan) }
            prefs.focusPlan = plan.encode()
            lastFocusPhase = null
            handler.removeCallbacks(focusTick)
            handler.post(focusTick)
            updateNotification()
        }
    }

    override fun stopFocus() {
        handler.post {
            handler.removeCallbacks(focusTick)
            repo.update { it.copy(focus = null) }
            updateNotification()
        }
    }

    /** Focus timer: 2× short at each focus time, 1× long at each break, 3× when done. */
    private val focusTick = object : Runnable {
        override fun run() {
            val (start, plan) = repo.state.value.focus ?: return
            val phase = plan.phaseAt(System.currentTimeMillis() / 1000 - start)
            val before = lastFocusPhase
            if (phase == null) {
                buzz(3, 400)
                repo.update { it.copy(focus = null) }
                Notifications.show(
                    this@StrapService, Notifications.ID_FOCUS, Notifications.CHANNEL_HEALTH, "Fokus-Timer fertig",
                    "${plan.rounds}× ${plan.workSeconds / 60} min geschafft.",
                )
                updateNotification()
                return
            }
            if (before == null || before.work != phase.work || before.round != phase.round) {
                if (phase.work) buzz(2, 450) else buzz(1, 1_300)
            }
            lastFocusPhase = phase
            handler.postDelayed(this, 1_000)
        }
    }

    private class Cooldown(val workout: Long, val end: Long, val hrEnd: Int)

    private var cooldown: Cooldown? = null

    private val cooldownTimeout = Runnable { endCooldown() }

    private fun endCooldown() {
        handler.removeCallbacks(cooldownTimeout)
        if (cooldown == null && repo.state.value.cooldownUntil == null) return
        cooldown = null
        repo.update { it.copy(cooldownUntil = null) }
        updateHeartRateStreaming()
    }

    /** A minute after the end of a workout: saves how far the pulse dropped. */
    private fun checkCooldown(second: Long, bpm: Int) {
        val c = cooldown ?: return
        if (second < c.end + 60) return
        db.setWorkoutHrr(c.workout, c.hrEnd - bpm)
        repo.log("Puls-Erholung: ${c.hrEnd} → $bpm in 1 min")
        endCooldown()
        repo.dataChanged()
    }

    override fun reconnect() {
        handler.post {
            connection?.close()
            connection = null
            repo.update { it.copy(settings = null) }
            ensureConnection()
        }
    }

    override fun shutdown() {
        handler.post {
            connection?.close()
            connection = null
            hrStreaming = false
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateHeartRateStreaming() {
        val c = connection ?: return
        val wanted = (liveRequested || repo.state.value.workout != null || cooldown != null || prefs.continuousHrSeconds > 0) &&
            c.state == LinkState.READY
        repo.update { it.copy(recordingHr = prefs.continuousHrSeconds > 0 && c.state == LinkState.READY) }
        if (wanted && !hrStreaming) {
            hrStreaming = true
            c.protocol.setRealtimeHeartRate(HeartRateMode.START)
            handler.removeCallbacks(hrKeepAlive)
            handler.postDelayed(hrKeepAlive, 10_000)
        } else if (!wanted && hrStreaming) {
            hrStreaming = false
            handler.removeCallbacks(hrKeepAlive)
            if (c.state == LinkState.READY) c.protocol.setRealtimeHeartRate(HeartRateMode.STOP)
        }
    }

    // ------------------------------------------------------------------ StrapConnection.Listener (strap thread)

    override fun onLinkState(state: LinkState, detail: String?) {
        if (state != LinkState.READY) hrStreaming = false
        val shown = if (prefs.connectionPaused && state == LinkState.DISCONNECTED) PAUSED_TEXT else detail
        repo.update { it.copy(link = state, linkDetail = shown, syncing = if (state == LinkState.READY) it.syncing else false) }
        if (detail != null) repo.log(detail)
        if (state == LinkState.READY) {
            updateHeartRateStreaming()
            pushPendingAlarms()
            handler.postDelayed({ syncNow() }, 3_000)
        }
        updateNotification()
    }

    override fun onDeviceInfo(info: DeviceInfo) {
        repo.update { it.copy(firmware = info.firmware ?: it.firmware, serial = info.serial ?: it.serial) }
    }

    override fun onEvent(event: StrapEvent) {
        when (event) {
            is StrapEvent.Authenticated -> repo.log("Angemeldet")
            is StrapEvent.AuthFailed -> repo.log("Anmeldung fehlgeschlagen: ${event.reason}")
            is StrapEvent.Ready -> repo.log("Bereit (${event.encryptedEndpoints.size} verschlüsselte Dienste)")
            is StrapEvent.Battery -> {
                repo.update { it.copy(battery = event.level, charging = event.charging) }
                updateNotification()
                checkBattery(event.level, event.charging)
                val now = System.currentTimeMillis() / 1000
                if (now - lastBatteryStored > 1800 || db.latestValue(HealthDb.BATTERY)?.value != event.level) {
                    lastBatteryStored = now
                    db.insertValues(HealthDb.BATTERY, listOf(ValueSample(now, event.level)))
                }
                updateWidget(force = true)
            }
            is StrapEvent.Steps -> repo.update {
                it.copy(steps = event.steps, distanceMeters = event.distanceMeters, calories = event.calories)
            }
            is StrapEvent.HeartRate -> onHeartRate(event)
            is StrapEvent.SleepState -> repo.update { it.copy(asleep = event.asleep) }
            is StrapEvent.HealthSettings -> {
                repo.update { it.copy(settings = it.settings?.merge(event.settings) ?: event.settings) }
                checkPendingSettings(event.settings)
            }
            is StrapEvent.ConfigAck -> repo.log(
                if (event.status == 1) "Strap hat die Einstellung bestätigt" else "Strap antwortet auf Einstellung mit Status ${event.status}"
            )
            is StrapEvent.FindAcknowledged -> repo.log("Strap vibriert")
            is StrapEvent.FindStoppedOnStrap -> repo.log("Suche am Strap beendet")
            is StrapEvent.SyncProgress -> repo.update {
                val pct = if (event.expectedBytes > 0) (event.receivedBytes * 100 / event.expectedBytes).coerceAtMost(100) else 0
                it.copy(syncing = true, syncText = "${event.type.label}: $pct %")
            }
            is StrapEvent.ActivityData -> {
                db.insertActivity(event.samples)
                repo.log("${event.samples.size} Minuten Aktivität gespeichert")
            }
            is StrapEvent.StressData -> {
                db.insertStress(event.samples)
                repo.log("${event.samples.size} Stresswerte gespeichert")
            }
            is StrapEvent.Spo2Data -> {
                db.insertValues(HealthDb.SPO2, event.samples)
                repo.log("${event.samples.size} SpO₂-Werte gespeichert")
            }
            is StrapEvent.RestingHeartRateData -> {
                db.insertValues(HealthDb.RESTING_HR, event.samples)
                repo.log("${event.samples.size} Ruhepuls-Werte gespeichert")
            }
            is StrapEvent.SleepData -> {
                db.insertSleepRecords(event.records)
                repo.log("${event.records.size} Nächte mit Schlafphasen gespeichert")
                repo.dataChanged()
            }
            is StrapEvent.PaiData -> {
                db.insertPai(event.samples)
                repo.log("${event.samples.size} PAI-Werte gespeichert")
            }
            is StrapEvent.TemperatureData -> {
                db.insertValues(HealthDb.TEMPERATURE, event.samples)
                repo.log("${event.samples.size} Temperaturwerte gespeichert")
            }
            is StrapEvent.RespirationData -> {
                db.insertValues(HealthDb.RESPIRATION, event.samples)
                repo.log("${event.samples.size} Atemfrequenz-Werte gespeichert")
            }
            is StrapEvent.HrvData -> {
                db.insertValues(HealthDb.HRV, event.samples)
                repo.log("${event.samples.size} HRV-Werte gespeichert")
            }
            is StrapEvent.Alarms -> {
                db.mergeStrapAlarms(event.alarms)
                repo.update { it.copy(alarmsLoaded = true) }
                repo.dataChanged()
            }
            is StrapEvent.SleepProbeProgress -> {
                val tried = probeOffset + event.tried
                prefs.sleepProbeTried = tried
                val total = StrapProtocol.SLEEP_CODE_CANDIDATES.size
                repo.update { it.copy(syncing = true, syncText = "Suche Schlafphasen: $tried von $total Codes") }
                repo.log("Schlafphasen-Suche 0x%02x: %s".format(event.code, event.detail))
            }
            is StrapEvent.SleepCodeProbed -> {
                val code = event.code
                if (code != null) {
                    prefs.sleepCode = code
                    prefs.sleepOffset = event.offset
                    repo.log("Schlafphasen gefunden (Code 0x%02x) – werden ab jetzt mit abgerufen".format(code))
                } else {
                    prefs.sleepCode = 0
                    repo.log("Der Strap liefert keine Schlafphasen-Datensätze, Schlaf wird aus den Minutenwerten berechnet")
                }
                repo.update { it.copy(syncing = false, syncText = null) }
                repo.dataChanged()
            }
            is StrapEvent.SyncFinished -> {
                val now = System.currentTimeMillis()
                if (event.success) prefs.lastSync = now
                repo.update {
                    it.copy(syncing = false, syncText = event.message, lastSync = if (event.success) now else it.lastSync)
                }
                repo.log(if (event.success) "Synchronisierung abgeschlossen" else "Synchronisierung abgebrochen: ${event.message}")
                repo.dataChanged()
                if (event.success && prefs.sleepCode == -1) probeSleepCode()
                if (event.success) {
                    afterSync()
                    updateWidget(force = true)
                }
                if (!event.success && prefs.notifySyncErrors) {
                    alert(NOTIFICATION_SYNC, CHANNEL_ALERTS, "Abruf vom Strap abgebrochen", event.message ?: "Unbekannter Fehler")
                }
            }
            is StrapEvent.Log -> repo.log(event.message)
        }
    }

    private var hrvMinute = 0L
    private val hrvRr = ArrayList<Int>()

    /**
     * HRV during the day: whenever the strap streams the heart rate with beat intervals (live view,
     * recording, workout), the RMSSD of every full minute is saved – the strap itself only measures at night.
     */
    private fun collectMinuteHrv(second: Long, rr: List<Int>) {
        val minute = second / 60
        if (minute != hrvMinute) {
            if (hrvMinute > 0) minuteRmssd(hrvRr)?.let { db.insertValues(HealthDb.HRV, listOf(ValueSample(hrvMinute * 60, it))) }
            hrvMinute = minute
            hrvRr.clear()
        }
        hrvRr.addAll(rr)
    }

    private val rhythmRr = ArrayList<Int>()
    private var rhythmLast = 0L

    /**
     * Heart rhythm check: every 64 beats at rest (no workout, pulse 40–110) are checked for an
     * irregular rhythm and saved. Gaps in the stream start a new stretch.
     */
    private fun collectRhythm(now: Long, bpm: Int, rr: List<Int>) {
        val w = repo.state.value.workout
        val resting = bpm in 40..110 && (w == null || w.sport == RECORDING_SPORT)
        if (!resting || now - rhythmLast > 5_000) rhythmRr.clear()
        if (!resting || rr.isEmpty()) return
        rhythmLast = now
        rhythmRr.addAll(rr)
        if (rhythmRr.size < de.waschbeken.heliolink.core.RhythmCheck.BEATS + 8) return
        de.waschbeken.heliolink.core.RhythmCheck.analyze(rhythmRr)?.let { db.insertRhythm(now / 1000, it) }
        rhythmRr.clear()
    }

    private fun onHeartRate(event: StrapEvent.HeartRate) {
        if (event.bpm <= 0) return
        val now = System.currentTimeMillis()
        rrBuffer.addAll(event.rrIntervalsMs)
        while (rrBuffer.size > 60) rrBuffer.removeAt(0)
        collectMinuteHrv(now / 1000, event.rrIntervalsMs)
        collectRhythm(now, event.bpm, event.rrIntervalsMs)
        checkCooldown(now / 1000, event.bpm)
        if (event.rrIntervalsMs.isNotEmpty()) repo.beats.tryEmit(event.rrIntervalsMs)
        val hrv = rmssd(rrBuffer)
        repo.update { it.copy(heartRate = event.bpm, heartRateAt = now, hrv = hrv ?: it.hrv) }

        val interval = prefs.continuousHrSeconds
        if (interval > 0 && now / 1000 - lastRecordedSecond >= interval) {
            lastRecordedSecond = now / 1000
            db.insertHr(lastRecordedSecond, event.bpm)
        }
        updateWidget(force = false)
        if (prefs.notificationShowsHr && now - lastHrNotification > 10_000) {
            lastHrNotification = now
            updateNotification()
        }

        val w = repo.state.value.workout ?: return
        checkWorkoutLimits(event.bpm, now / 1000 - w.start)
        val second = now / 1000
        if (second == lastWorkoutSecond) return
        if (w.plannedEnd != null && second >= w.plannedEnd) {
            stopWorkout()
            return
        }
        val elapsed = if (lastWorkoutSecond == 0L) 1 else (second - lastWorkoutSecond).coerceIn(1, 10).toInt()
        lastWorkoutSecond = second
        db.addWorkoutHr(w.id, second, event.bpm)

        val profile = prefs.profile
        val zone = HeartRateZones.zoneOf(event.bpm, profile.maxHeartRate, profile.zoneRest(strapRestingHr()))
        val zones = w.zoneSeconds.toMutableList()
        if (zone >= 0) zones[zone] += elapsed
        workoutHrSum += event.bpm
        val samples = w.samples + 1
        val kcal = w.calories + Calories.perMinute(event.bpm, profile.weightKg.toDouble(), profile.age, profile.male) * elapsed / 60.0
        repo.update {
            it.copy(
                workout = w.copy(
                    samples = samples,
                    avgHr = (workoutHrSum / samples).toInt(),
                    maxHr = maxOf(w.maxHr, event.bpm),
                    calories = kcal,
                    zoneSeconds = zones,
                )
            )
        }
        if (samples % 15 == 0) updateNotification()
    }

    private fun updateWidget(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastWidgetUpdate < 60_000) return
        lastWidgetUpdate = now
        val s = repo.state.value
        HelioWidget.update(applicationContext, s.heartRate, s.steps, lastNightText(), s.battery)
    }

    private fun lastNightText(): String? {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val night = de.waschbeken.heliolink.data.Queries.night(db, today, zone) ?: return null
        return "${night.asleepMinutes / 60}:%02d h".format(night.asleepMinutes % 60)
    }

    /** Reminds to go to bed so the sleep goal fits before the next alarm. */
    private fun checkBedtime() {
        val before = prefs.bedtimeReminder
        if (before <= 0) return
        val next = nextAlarm(db.alarms().filter { !it.deleted }.map { it.alarm }) ?: return
        val bedtime = next.minusMinutes(prefs.profile.sleepGoalMinutes.toLong())
        val now = ZonedDateTime.now()
        val remind = bedtime.minusMinutes(before.toLong())
        val day = bedtime.toLocalDate().toEpochDay()
        if (now.isAfter(remind) && now.isBefore(bedtime) && prefs.bedtimeNotifiedDay != day) {
            prefs.bedtimeNotifiedDay = day
            Notifications.show(
                this, Notifications.ID_BEDTIME, Notifications.CHANNEL_HEALTH,
                "Bald Schlafenszeit",
                "Wecker um %02d:%02d – für %s Schlaf ab %02d:%02d ins Bett.".format(
                    next.hour, next.minute, de.waschbeken.heliolink.ui.formatMinutes(prefs.profile.sleepGoalMinutes), bedtime.hour, bedtime.minute,
                ),
                de.waschbeken.heliolink.ui.MainActivity.openIntent(this, de.waschbeken.heliolink.ui.Routes.ALARMS),
            )
        }
    }

    /** Monday morning: last week compared with the week before. */
    private fun weeklySummary() {
        val today = LocalDate.now()
        if (!prefs.weeklySummary || today.dayOfWeek != java.time.DayOfWeek.MONDAY || prefs.weeklySummaryDay == today.toEpochDay()) return
        if (LocalTime.now().hour < 6) return
        prefs.weeklySummaryDay = today.toEpochDay()
        val zone = ZoneId.systemDefault()
        val nights = de.waschbeken.heliolink.data.Queries.nights(db, today.minusDays(14), today.minusDays(1), zone)
        val last = nights.filterKeys { it >= today.minusDays(7) }.values
        val before = nights.filterKeys { it < today.minusDays(7) }.values
        val days = de.waschbeken.heliolink.core.Aggregates.days(
            db.activity(today.minusDays(14).atStartOfDay(zone).toEpochSecond(), today.atStartOfDay(zone).toEpochSecond()),
            today.minusDays(14), today.minusDays(1), zone,
        )
        val stepsLast = days.filter { it.date >= today.minusDays(7) }.map { it.steps }.average().takeIf { !it.isNaN() }?.toInt()
        val stepsBefore = days.filter { it.date < today.minusDays(7) }.map { it.steps }.average().takeIf { !it.isNaN() }?.toInt()
        if (last.isEmpty() && (stepsLast ?: 0) == 0) return
        fun trend(now: Int, was: Int?, unit: String) = if (was == null || was == 0) "" else {
            val d = now - was
            if (d >= 0) " (+$d$unit)" else " ($d$unit)"
        }
        val text = buildString {
            if (last.isNotEmpty()) {
                val sleep = last.map { it.asleepMinutes }.average().toInt()
                val score = last.map { it.score }.average().toInt()
                append("Ø ${de.waschbeken.heliolink.ui.formatMinutes(sleep)} Schlaf")
                append(trend(sleep, before.map { it.asleepMinutes }.average().takeIf { !it.isNaN() }?.toInt(), " min"))
                append(" · Score $score")
                append(trend(score, before.map { it.score }.average().takeIf { !it.isNaN() }?.toInt(), ""))
            }
            if (stepsLast != null && stepsLast > 0) {
                if (isNotEmpty()) append(" · ")
                append("Ø ${"%,d".format(java.util.Locale.GERMANY, stepsLast)} Schritte")
                append(trend(stepsLast, stepsBefore, ""))
            }
        }
        Notifications.show(
            this, Notifications.ID_WEEKLY, Notifications.CHANNEL_HEALTH, "Deine Woche", text,
            de.waschbeken.heliolink.ui.MainActivity.openIntent(this, de.waschbeken.heliolink.ui.Routes.SLEEP),
        )
    }

    /** Once a day: a clear warning when last night's values together point to an illness coming up. */
    private fun bodyCheckWarning() {
        val today = LocalDate.now()
        if (!prefs.bodyCheckNotify || prefs.bodyCheckDay == today.toEpochDay() || LocalTime.now().hour < 5) return
        val zone = ZoneId.systemDefault()
        val night = de.waschbeken.heliolink.data.Queries.night(db, today, zone) ?: return
        if (night.end > System.currentTimeMillis() / 1000 - 15 * 60) return // probably still in bed
        val result = de.waschbeken.heliolink.core.BodyCheck.check(de.waschbeken.heliolink.data.Queries.nightVitals(db, today.minusDays(36), today, zone))
        prefs.bodyCheckDay = today.toEpochDay()
        if (result == null || result.date != today || result.level != de.waschbeken.heliolink.core.BodyCheck.Level.ALERT) return
        Notifications.show(
            this, Notifications.ID_BODY_CHECK, Notifications.CHANNEL_HEALTH,
            "Dein Körper arbeitet mehr als sonst",
            result.signals.joinToString(" · ") { "${it.vital.label} ${it.text}" } +
                ". Das sieht oft so aus, bevor man krank wird – gönn dir heute Ruhe. Keine Diagnose.",
            de.waschbeken.heliolink.ui.MainActivity.openIntent(this, de.waschbeken.heliolink.ui.Routes.HOME),
        )
    }

    /** In the morning (until 11:00), when there is no HRV value of today yet: reminds of the 1-minute measurement. */
    private fun hrvReminder() {
        val today = LocalDate.now()
        val hour = LocalTime.now().hour
        if (!prefs.hrvReminder || prefs.hrvReminderDay == today.toEpochDay() || hour < 5 || hour >= 11) return
        val zone = ZoneId.systemDefault()
        val night = de.waschbeken.heliolink.data.Queries.night(db, today, zone) ?: return
        if (night.end > System.currentTimeMillis() / 1000 - 10 * 60) return // probably still in bed
        if (db.values(HealthDb.HRV, today.atTime(4, 0).atZone(zone).toEpochSecond(), System.currentTimeMillis() / 1000 + 60).isNotEmpty()) return
        prefs.hrvReminderDay = today.toEpochDay()
        Notifications.show(
            this, Notifications.ID_HRV_REMINDER, Notifications.CHANNEL_HEALTH,
            "Zeit für deine HRV-Messung", "1 Minute ruhig liegen oder sitzen – tippen zum Starten.",
            de.waschbeken.heliolink.ui.MainActivity.openIntent(this, de.waschbeken.heliolink.ui.Routes.TOOLS_HRV),
        )
    }

    /** Morning summary after the first successful sync of the day. */
    private fun afterSync() {
        de.waschbeken.heliolink.data.AutoBackup.runIfDueInBackground(this)
        weeklySummary()
        try {
            bodyCheckWarning()
            StressPrompt.check(this, db, prefs, repo.state.value.workout != null)
            hrvReminder()
        } catch (e: Exception) {
            de.waschbeken.heliolink.data.CrashReporter.nonFatal(this, e)
        }
        val today = LocalDate.now()
        if (!prefs.morningSummary || prefs.morningSummaryDay == today.toEpochDay() || LocalTime.now().hour < 5) return
        val zone = ZoneId.systemDefault()
        val night = de.waschbeken.heliolink.data.Queries.night(db, today, zone) ?: return
        if (night.end > System.currentTimeMillis() / 1000 - 15 * 60) return // probably still in bed
        prefs.morningSummaryDay = today.toEpochDay()
        val text = buildString {
            append("${de.waschbeken.heliolink.ui.formatMinutes(night.asleepMinutes)} geschlafen, Score ${night.score}")
            if (!night.estimated) {
                append(" · Tief ${de.waschbeken.heliolink.ui.formatMinutes(night.minutes(SleepStage.DEEP))}")
                append(" · REM ${de.waschbeken.heliolink.ui.formatMinutes(night.minutes(SleepStage.REM))}")
            }
            night.minHeartRate?.let { append(" · Puls min. $it") }
        }
        Notifications.show(
            this, Notifications.ID_MORNING, Notifications.CHANNEL_HEALTH, "Guten Morgen! Deine Nacht", "$text · Tippen zum Ansehen oder Korrigieren",
            de.waschbeken.heliolink.ui.MainActivity.openIntent(this, de.waschbeken.heliolink.ui.Routes.SLEEP),
        )
    }

    /** Offers a higher maximum heart rate when a workout went above it. */
    private fun suggestMaxHeartRate(measured: Int) {
        val profile = prefs.profile
        if (measured <= profile.maxHeartRate || measured <= prefs.suggestedMaxHr || measured > 230) return
        prefs.suggestedMaxHr = measured
        repo.update { it.copy(maxHrSuggestion = measured) }
        Notifications.show(
            this, Notifications.ID_MAX_HR, Notifications.CHANNEL_HEALTH,
            "Neuer Höchstpuls: $measured bpm",
            "Dein Training lag über dem eingestellten Maximalpuls (${profile.maxHeartRate}). In der App kannst du ihn übernehmen, damit die Pulszonen stimmen.",
        )
    }

    private var cachedResting: Pair<Long, Int?>? = null

    /** The strap's latest resting heart rate, looked up at most every 10 minutes. */
    private fun strapRestingHr(): Int? {
        val now = System.currentTimeMillis()
        cachedResting?.let { (at, value) -> if (now - at < 600_000) return value }
        val value = db.values(HealthDb.RESTING_HR, now / 1000 - 14 * 86400, now / 1000 + 60).lastOrNull()?.value
        cachedResting = now to value
        return value
    }

    /** Lets the strap vibrate when the heart rate leaves the limits set for workouts. */
    private fun checkWorkoutLimits(bpm: Int, elapsedSeconds: Long) {
        val upper = prefs.workoutHrUpper
        val lower = prefs.workoutHrLower
        val outside = (upper > 0 && bpm > upper) || (lower > 0 && elapsedSeconds > 180 && bpm < lower)
        val now = System.currentTimeMillis()
        if (!outside || now - lastVibration < 45_000) return
        lastVibration = now
        val p = connection?.protocol ?: return
        repo.log(if (upper > 0 && bpm > upper) "Puls $bpm über $upper – Strap vibriert" else "Puls $bpm unter $lower – Strap vibriert")
        p.findStrap(true)
        handler.postDelayed({ connection?.protocol?.findStrap(false) }, 1_500)
    }

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Strap-Verbindung", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Warnungen", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_UPDATES, "App-Updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        Notifications.createChannels(this)
    }

    private fun checkBattery(level: Int, charging: Boolean) {
        val threshold = prefs.lowBatteryAlert
        if (threshold <= 0 || charging || level > threshold + 5) {
            if (prefs.lowBatteryNotified) prefs.lowBatteryNotified = false
            return
        }
        if (level <= threshold && !prefs.lowBatteryNotified) {
            prefs.lowBatteryNotified = true
            alert(NOTIFICATION_BATTERY, CHANNEL_ALERTS, "Helio Strap: Akku bei $level %", "Lade den Strap bald auf, damit Schlaf und Puls weiter aufgezeichnet werden.")
        }
    }

    private fun checkForUpdateInBackground() {
        scope.launch {
            val info = app.updater.manager.checkInBackground() ?: return@launch
            if (prefs.shizukuInstall && prefs.autoInstallUpdates && !AppVisibility.inForeground) {
                repo.log("Installiere Update ${info.version} im Hintergrund (Shizuku)")
                if (app.updater.installSilently(info)) return@launch
            }
            app.updater.announce(info)
            val intent = Intent(this@StrapService, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SHOW_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            alert(NOTIFICATION_UPDATE, CHANNEL_UPDATES, "HelioLink ${info.version} ist da", "Tippen, um das Update zu installieren", intent)
        }
    }

    private fun alert(id: Int, channel: String, title: String, text: String, intent: Intent = Intent(this, MainActivity::class.java)) {
        val pending = PendingIntent.getActivity(this, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(id, notification)
        } catch (_: SecurityException) {
            // Notifications not permitted.
        }
    }

    private fun buildNotification(): Notification {
        val s = repo.state.value
        val title = when (s.link) {
            LinkState.READY -> "Helio Strap verbunden"
            LinkState.CONNECTING, LinkState.AUTHENTICATING -> "Verbinde mit Helio Strap…"
            LinkState.AUTH_FAILED -> "Auth-Key abgelehnt"
            LinkState.DISCONNECTED -> "Helio Strap nicht verbunden"
        }
        val parts = ArrayList<String>()
        s.battery?.let { parts += "Akku $it %" + if (s.charging) " ⚡" else "" }
        s.workout?.let { parts += "Training läuft · Ø ${it.avgHr} bpm" }
        if (prefs.notificationShowsHr && s.heartRate != null && System.currentTimeMillis() - s.heartRateAt < 30_000) {
            parts += "♥ ${s.heartRate} bpm"
        }
        if (s.recordingHr) parts += "Puls-Aufzeichnung an"
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StrapService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(parts.joinToString(" · ").ifEmpty { null })
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Beenden", stop)
            .build()
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
            // Notifications not permitted, the service keeps running anyway.
        }
    }
}
