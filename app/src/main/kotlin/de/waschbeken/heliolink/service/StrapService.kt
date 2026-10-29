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
import de.waschbeken.heliolink.core.HeartRateZones
import de.waschbeken.heliolink.core.StrapEvent
import de.waschbeken.heliolink.core.rmssd
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
        private const val CHANNEL_ALERTS = "alerts"
        private const val CHANNEL_UPDATES = "updates"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_BATTERY = 2
        private const val NOTIFICATION_SYNC = 3
        private const val NOTIFICATION_UPDATE = 4
        private const val ACTION_STOP = "de.waschbeken.heliolink.STOP"

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
    private var hrStreaming = false
    private val rrBuffer = ArrayList<Int>()

    private var lastWorkoutSecond = 0L
    private var workoutHrSum = 0L

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
            handler.postDelayed(this, 5 * 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("strap").also { it.start() }
        handler = Handler(thread.looper)
        createChannel()
        repo.controller = this
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
            connection?.startSync { startSync(prefs, defaultHistory = Duration.ofDays(days)) }
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

    override fun applySettings(changes: Map<HealthSetting, Int>) {
        handler.post {
            val p = connection?.protocol ?: return@post
            p.changeHealthSettings(changes)
            handler.postDelayed({ connection?.protocol?.requestHealthSettings() }, 1_500)
        }
    }

    override fun startWorkout(sport: String) {
        handler.post {
            if (repo.state.value.workout != null) return@post
            val start = System.currentTimeMillis() / 1000
            val id = db.startWorkout(sport, start)
            lastWorkoutSecond = 0
            workoutHrSum = 0
            repo.update { it.copy(workout = LiveWorkout(id, sport, start)) }
            updateHeartRateStreaming()
            updateNotification()
        }
    }

    override fun stopWorkout() {
        handler.post {
            val w = repo.state.value.workout ?: return@post
            val end = System.currentTimeMillis() / 1000
            db.finishWorkout(
                Workout(w.id, w.sport, w.start, end, w.avgHr, w.maxHr, w.calories.roundToInt(), w.zoneSeconds)
            )
            repo.update { it.copy(workout = null) }
            repo.dataChanged()
            updateHeartRateStreaming()
            updateNotification()
        }
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
        val wanted = (liveRequested || repo.state.value.workout != null) && c.state == LinkState.READY
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
        repo.update { it.copy(link = state, linkDetail = detail, syncing = if (state == LinkState.READY) it.syncing else false) }
        if (detail != null) repo.log(detail)
        if (state == LinkState.READY) {
            updateHeartRateStreaming()
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
            }
            is StrapEvent.Steps -> repo.update {
                it.copy(steps = event.steps, distanceMeters = event.distanceMeters, calories = event.calories)
            }
            is StrapEvent.HeartRate -> onHeartRate(event)
            is StrapEvent.SleepState -> repo.update { it.copy(asleep = event.asleep) }
            is StrapEvent.HealthSettings -> repo.update { it.copy(settings = event.settings) }
            is StrapEvent.ConfigAck -> repo.log("Einstellung übernommen (Status ${event.status})")
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
            is StrapEvent.SyncFinished -> {
                val now = System.currentTimeMillis()
                if (event.success) prefs.lastSync = now
                repo.update {
                    it.copy(syncing = false, syncText = event.message, lastSync = if (event.success) now else it.lastSync)
                }
                repo.log(if (event.success) "Synchronisierung abgeschlossen" else "Synchronisierung abgebrochen: ${event.message}")
                repo.dataChanged()
                if (!event.success && prefs.notifySyncErrors) {
                    alert(NOTIFICATION_SYNC, CHANNEL_ALERTS, "Abruf vom Strap abgebrochen", event.message ?: "Unbekannter Fehler")
                }
            }
            is StrapEvent.Log -> repo.log(event.message)
        }
    }

    private fun onHeartRate(event: StrapEvent.HeartRate) {
        if (event.bpm <= 0) return
        val now = System.currentTimeMillis()
        rrBuffer.addAll(event.rrIntervalsMs)
        while (rrBuffer.size > 60) rrBuffer.removeAt(0)
        val hrv = rmssd(rrBuffer)
        repo.update { it.copy(heartRate = event.bpm, heartRateAt = now, hrv = hrv ?: it.hrv) }

        val w = repo.state.value.workout ?: return
        val second = now / 1000
        if (second == lastWorkoutSecond) return
        val elapsed = if (lastWorkoutSecond == 0L) 1 else (second - lastWorkoutSecond).coerceIn(1, 10).toInt()
        lastWorkoutSecond = second
        db.addWorkoutHr(w.id, second, event.bpm)

        val profile = prefs.profile
        val zone = HeartRateZones.zoneOf(event.bpm, profile.maxHeartRate)
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

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Strap-Verbindung", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Warnungen", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_UPDATES, "App-Updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
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
