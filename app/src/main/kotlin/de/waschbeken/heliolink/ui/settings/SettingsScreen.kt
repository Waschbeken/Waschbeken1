package de.waschbeken.heliolink.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import de.waschbeken.heliolink.BuildConfig
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.ble.LinkState
import de.waschbeken.heliolink.core.HealthConfig
import de.waschbeken.heliolink.core.HealthSetting
import de.waschbeken.heliolink.core.HeartRateZones
import de.waschbeken.heliolink.core.parseHex
import de.waschbeken.heliolink.data.ThemeMode
import de.waschbeken.heliolink.service.StrapUiState
import de.waschbeken.heliolink.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** One page of the settings. Each knows its title, icon and a one-line summary for the main list. */
enum class SettingsCategory(val title: String, val icon: ImageVector, val summary: String) {
    STRAP("Strap", Icons.Default.Bluetooth, "Verbindung, Akku, Firmware, Auth-Key, vibrieren lassen"),
    MEASUREMENTS("Messungen am Strap", Icons.Default.MonitorHeart, "Pulsintervall, Puls-Broadcast, Schlaf, Stress, SpO₂, Warnungen"),
    RECORDING("Puls-Aufzeichnung", Icons.Default.Timer, "Öfter als jede Minute: jede Sekunde bis alle 30 Sekunden"),
    MOVEMENT("Bewegung & Ziele", Icons.Default.DirectionsWalk, "Bewegungserinnerung mit Zeiten, Schrittziel, Trainingserkennung"),
    PROFILE("Profil", Icons.Default.Person, "Alter, Gewicht, Größe, Schlafziel"),
    TRAINING("Training & Zonen", Icons.Default.FitnessCenter, "Max. Puls, Ruhepuls, Zonen, Pulsgrenzen mit Vibration"),
    SYNC("Synchronisierung", Icons.Default.Sync, "Automatischer Abruf, Zeitraum, Schlafphasen, Verlauf neu laden"),
    NOTIFICATIONS("Benachrichtigungen", Icons.Default.Notifications, "Akku-Warnung, Live-Puls, Sync-Fehler"),
    APPEARANCE("Darstellung", Icons.Default.Palette, "Hell, dunkel, Material You"),
    UPDATES("App-Updates", Icons.Default.SystemUpdate, "Automatisch prüfen, Vorabversionen, Quelle"),
    DATA("Daten & Sicherung", Icons.Default.Storage, "Export, Sicherung, Gadgetbridge-Import, Protokoll"),
}

@Composable
fun SettingsScreen(state: StrapUiState, onForget: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val category = page?.let { p -> SettingsCategory.entries.firstOrNull { it.name == p } }
    BackHandler(enabled = category != null) { page = null }

    if (category == null) {
        SettingsHome(open = { page = it.name })
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { page = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                Text(category.title, style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                when (category) {
                    SettingsCategory.STRAP -> StrapPage(state, onForget)
                    SettingsCategory.MEASUREMENTS -> MeasurementsPage(state)
                    SettingsCategory.RECORDING -> RecordingPage(state)
                    SettingsCategory.MOVEMENT -> MovementPage(state)
                    SettingsCategory.PROFILE -> ProfilePage(state)
                    SettingsCategory.TRAINING -> TrainingPage()
                    SettingsCategory.SYNC -> SyncPage(state)
                    SettingsCategory.NOTIFICATIONS -> NotificationsPage()
                    SettingsCategory.APPEARANCE -> AppearancePage()
                    SettingsCategory.UPDATES -> UpdatesPage()
                    SettingsCategory.DATA -> DataPage(state)
                }
            }
        }
    }
}

@Composable
private fun SettingsHome(open: (SettingsCategory) -> Unit) {
    val context = LocalContext.current
    val updater = context.app.updater
    val update by updater.state.collectAsState()
    val prefs = context.app.prefs
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "Einstellungen",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        SettingsCategory.entries.forEach { c ->
            item(key = c.name) { CategoryRow(c.icon, c.title, c.summary) { open(c) } }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        // "Nach Updates suchen" sits directly here, not inside a category.
        item {
            SettingRow(
                "Nach Updates suchen",
                when (val u = update) {
                    UpdateState.Checking -> "Suche …"
                    is UpdateState.Available -> "Version ${u.info.version} verfügbar – tippen zum Installieren"
                    is UpdateState.Downloading -> "Lade herunter … ${(u.progress.coerceAtLeast(0f) * 100).toInt()} %"
                    is UpdateState.Ready -> "Bereit zum Installieren"
                    is UpdateState.KeyChanged -> "Version ${u.info.version}: einmalige Neuinstallation nötig – tippen"
                    is UpdateState.Failed -> u.message
                    else -> "Installiert: ${updater.currentVersion}" +
                        if (prefs.lastUpdateCheck > 0) " · geprüft ${formatDate(prefs.lastUpdateCheck)}" else ""
                },
                onClick = {
                    if (update is UpdateState.Available || update is UpdateState.Ready || update is UpdateState.KeyChanged) {
                        updater.showDialog.value = true
                    } else {
                        updater.check()
                    }
                },
            )
        }
        item {
            Text(
                "HelioLink ${BuildConfig.VERSION_NAME} · ohne Cloud · Daten bleiben auf deinem Gerät",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

internal fun formatDate(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

/** Re-reads settings whenever any preference changes. */
@Composable
internal fun prefsTick(): Int {
    val tick by LocalContext.current.app.prefs.changes.collectAsState()
    return tick
}

// --------------------------------------------------------------------------- Strap

@Composable
private fun StrapPage(state: StrapUiState, onForget: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.app.prefs
    val controller = context.app.repo.controller
    prefsTick()
    var confirmForget by remember { mutableStateOf(false) }

    SettingsHeader("Gerät")
    SettingRow("Name", prefs.deviceName ?: "Amazfit Helio Strap")
    SettingRow(
        "Status",
        when (state.link) {
            LinkState.READY -> "Verbunden"
            LinkState.CONNECTING -> "Verbinde …"
            LinkState.AUTHENTICATING -> "Anmeldung …"
            LinkState.AUTH_FAILED -> "Auth-Key abgelehnt"
            LinkState.DISCONNECTED -> "Nicht verbunden"
        } + (state.linkDetail?.let { " · $it" } ?: ""),
    )
    SettingRow("Akku", state.battery?.let { "$it %" + if (state.charging) " · lädt" else "" } ?: "unbekannt")
    SettingRow("MAC-Adresse", prefs.deviceAddress ?: "–")
    state.firmware?.let { SettingRow("Firmware", it) }
    state.serial?.let { SettingRow("Seriennummer", it) }

    SettingsHeader("Aktionen")
    SettingRow("Strap vibrieren lassen", "Zum Wiederfinden – vibriert einige Sekunden", enabled = state.connected) {
        controller?.findStrap()
    }
    SettingRow("Neu verbinden", "Trennt die Verbindung und baut sie neu auf") { controller?.reconnect() }

    SettingsHeader("Anmeldung")
    TextSetting(
        "Auth-Key",
        prefs.authKeyHex.orEmpty(),
        subtitle = "32 Hex-Zeichen aus deinem Zepp-Konto (siehe README)",
        placeholder = "0x1a2b3c…",
        secret = true,
        validate = { if (parseHex(it)?.size == 16) null else "Der Key muss genau 32 Hex-Zeichen haben" },
    ) { v ->
        prefs.authKeyHex = v
        controller?.reconnect()
    }
    SettingRow("Strap entfernen", "Verbindung und Auth-Key löschen, Daten bleiben erhalten") { confirmForget = true }

    if (confirmForget) {
        ConfirmDialog(
            "Strap entfernen?",
            "Verbindung und Auth-Key werden gelöscht. Deine gespeicherten Daten bleiben erhalten.",
            "Entfernen",
            onConfirm = onForget,
            onDismiss = { confirmForget = false },
        )
    }
}

// --------------------------------------------------------------------------- Sync

@Composable
private fun SyncPage(state: StrapUiState) {
    val context = LocalContext.current
    val prefs = context.app.prefs
    val controller = context.app.repo.controller
    prefsTick()
    var confirmReload by remember { mutableStateOf(false) }

    SettingsHeader("Automatisch")
    ChoiceSetting(
        "Daten abrufen",
        listOf(0 to "Nur manuell", 15 to "Alle 15 Minuten", 30 to "Alle 30 Minuten", 60 to "Stündlich", 180 to "Alle 3 Stunden"),
        prefs.autoSyncMinutes,
        subtitle = "solange der Strap verbunden ist",
    ) { prefs.autoSyncMinutes = it }
    ChoiceSetting(
        "Beim ersten Abruf",
        listOf(7 to "Letzte 7 Tage", 14 to "Letzte 14 Tage", 30 to "Letzte 30 Tage", 90 to "Alles, was der Strap hat"),
        prefs.historyDays,
    ) { prefs.historyDays = it }

    SettingsHeader("Jetzt")
    SettingRow(
        "Jetzt synchronisieren",
        when {
            state.syncing -> state.syncText ?: "Läuft …"
            state.lastSync > 0 -> "Zuletzt: ${formatDate(state.lastSync)}"
            else -> "Noch nie"
        },
        enabled = state.connected && !state.syncing,
    ) { controller?.syncNow() }
    SettingRow(
        "Schlafphasen-Abruf suchen",
        when (prefs.sleepCode) {
            -1 -> "Noch nicht gesucht – passiert automatisch nach dem ersten Abruf"
            0 -> "Nicht gefunden – tippen, um erneut zu suchen"
            else -> "Aktiv (Code 0x%02x) – tippen, um neu zu suchen".format(prefs.sleepCode)
        },
        enabled = state.connected && !state.syncing,
    ) { controller?.probeSleepCode() }
    SettingRow(
        "Verlauf neu laden",
        "Holt alle Daten erneut vom Strap, z. B. nach einem Fehler",
        enabled = state.connected && !state.syncing,
    ) { confirmReload = true }

    if (confirmReload) {
        ConfirmDialog(
            "Verlauf neu laden?",
            "Die App ruft alle Daten der eingestellten Zeitspanne erneut ab. Vorhandene Werte werden überschrieben, nichts geht verloren.",
            "Neu laden",
            onConfirm = {
                prefs.resetCursors()
                controller?.syncNow()
            },
            onDismiss = { confirmReload = false },
        )
    }
}

// --------------------------------------------------------------------------- Benachrichtigungen

@Composable
private fun NotificationsPage() {
    val context = LocalContext.current
    val prefs = context.app.prefs
    prefsTick()
    SettingsHeader("Strap")
    ChoiceSetting(
        "Akku-Warnung",
        listOf(0 to "Aus", 10 to "Unter 10 %", 15 to "Unter 15 %", 20 to "Unter 20 %", 30 to "Unter 30 %"),
        prefs.lowBatteryAlert,
        subtitle = "damit der Strap nachts nicht leer ist",
    ) { prefs.lowBatteryAlert = it }
    SwitchSetting("Sync-Fehler melden", "Benachrichtigung, wenn der Abruf abbricht", prefs.notifySyncErrors) { prefs.notifySyncErrors = it }
    SwitchSetting(
        "Puls in der Benachrichtigung",
        "Zeigt den aktuellen Puls in der Dauer-Benachrichtigung, solange er gemessen wird",
        prefs.notificationShowsHr,
    ) { prefs.notificationShowsHr = it }

    SettingsHeader("System")
    SettingRow("Android-Benachrichtigungen", "Töne, Wichtigkeit und Kanäle in den Systemeinstellungen") {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
    SettingsNote("Die dauerhafte Benachrichtigung „Helio Strap verbunden“ hält die Verbindung im Hintergrund aktiv.")
}

// --------------------------------------------------------------------------- Darstellung

@Composable
private fun AppearancePage() {
    val prefs = LocalContext.current.app.prefs
    prefsTick()
    SettingsHeader("Design")
    ChoiceSetting("Design", ThemeMode.entries.map { it to it.label }, prefs.themeMode) { prefs.themeMode = it }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        SwitchSetting("Material You", "Farben aus deinem Hintergrundbild übernehmen", prefs.dynamicColor) { prefs.dynamicColor = it }
    }
    SettingsNote("Pulszonen und Schlafphasen behalten ihre festen Farben, damit sie immer gleich lesbar sind.")
}

// --------------------------------------------------------------------------- Updates

@Composable
private fun UpdatesPage() {
    val context = LocalContext.current
    val prefs = context.app.prefs
    val updater = context.app.updater
    val update by updater.state.collectAsState()
    prefsTick()

    SettingsHeader("Updates")
    SwitchSetting(
        "Automatisch prüfen",
        "Im Hintergrund alle paar Stunden – eine neue Version meldet sich per Benachrichtigung",
        prefs.autoCheckUpdates,
    ) { prefs.autoCheckUpdates = it }
    SwitchSetting("Vorabversionen", "Auch Test-Versionen anbieten", prefs.includePrereleases) { prefs.includePrereleases = it }
    SettingRow(
        "Jetzt prüfen",
        when (val u = update) {
            UpdateState.Checking -> "Suche …"
            is UpdateState.Available -> "Version ${u.info.version} verfügbar"
            is UpdateState.Failed -> u.message
            UpdateState.UpToDate -> "Du hast die neueste Version"
            else -> "Installiert: ${updater.currentVersion}"
        },
    ) {
        if (update is UpdateState.Available || update is UpdateState.Ready) updater.showDialog.value = true else updater.check()
    }
    if (prefs.skippedVersion.isNotBlank()) {
        SettingRow("Übersprungene Version", "${prefs.skippedVersion} – tippen, um sie wieder anzubieten") {
            prefs.skippedVersion = ""
        }
    }

    SettingsHeader("Quelle")
    TextSetting(
        "Update-Quelle",
        prefs.updateRepo,
        subtitle = "GitHub-Repository mit den Releases (besitzer/name)",
        validate = { if (it.count { c -> c == '/' } != 1) "Format: besitzer/name" else null },
    ) { prefs.updateRepo = it }
    if (BuildConfig.DEBUG) {
        SettingsNote("Dies ist eine Test-Version (debug). Updates installieren die normale Version zusätzlich.")
    }
}

