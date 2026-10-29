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
    STRAP("Strap", Icons.Default.Bluetooth, "Verbindung, Akku, Auth-Key, Strap vibrieren"),
    MEASUREMENTS("Messungen", Icons.Default.MonitorHeart, "Pulsintervall, Puls-Broadcast, Schlaf, Stress, Warnungen"),
    PROFILE("Profil & Zonen", Icons.Default.Person, "Alter, Gewicht, max. Puls, Schrittziel"),
    SYNC("Synchronisierung", Icons.Default.Sync, "Automatischer Abruf, Verlauf neu laden"),
    NOTIFICATIONS("Benachrichtigungen", Icons.Default.Notifications, "Akku-Warnung, Sync-Fehler"),
    APPEARANCE("Darstellung", Icons.Default.Palette, "Hell, dunkel, Material You"),
    UPDATES("App-Updates", Icons.Default.SystemUpdate, "Automatisch prüfen, Vorabversionen, Quelle"),
    DATA("Daten", Icons.Default.Storage, "Export, Protokoll, Daten löschen"),
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
                    SettingsCategory.PROFILE -> ProfilePage()
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
                    is UpdateState.Failed -> u.message
                    else -> "Installiert: ${updater.currentVersion}" +
                        if (prefs.lastUpdateCheck > 0) " · geprüft ${formatDate(prefs.lastUpdateCheck)}" else ""
                },
                onClick = {
                    if (update is UpdateState.Available || update is UpdateState.Ready) updater.showDialog.value = true
                    else updater.check()
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

private fun formatDate(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

/** Re-reads settings whenever any preference changes. */
@Composable
private fun prefsTick(): Int {
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

// --------------------------------------------------------------------------- Messungen

private data class SwitchInfo(val setting: HealthSetting, val title: String, val description: String)

private val heartSwitches = listOf(
    SwitchInfo(
        HealthSetting.HEART_RATE_BROADCAST, "Puls an andere Geräte senden",
        "Der Strap wird zum Brustgurt: Laufband, Radcomputer, Zwift, Strava & Co. empfangen den Puls direkt. Braucht mehr Akku.",
    ),
    SwitchInfo(HealthSetting.HEART_RATE_ACTIVITY_MONITORING, "Bei Aktivität häufiger messen", "Erkennt Bewegung und misst dann öfter"),
)

private val sleepSwitches = listOf(
    SwitchInfo(HealthSetting.SLEEP_HIGH_ACCURACY, "Schlaf mit hoher Genauigkeit", "Nutzt den Pulssensor für REM- und Tiefschlaf. Empfohlen."),
    SwitchInfo(HealthSetting.SLEEP_BREATHING_QUALITY, "Atmungsqualität im Schlaf", "Zusätzliche Messung in der Nacht"),
)

private val otherSwitches = listOf(
    SwitchInfo(HealthSetting.STRESS_MONITORING, "Stress automatisch messen", "Aus der Herzfrequenzvariabilität"),
    SwitchInfo(HealthSetting.STRESS_RELAXATION_REMINDER, "Entspannungs-Erinnerung", "Vibriert bei hohem Stress"),
    SwitchInfo(HealthSetting.SPO2_ALL_DAY, "SpO₂ ganztägig", "Blutsauerstoff regelmäßig messen"),
    SwitchInfo(HealthSetting.INACTIVITY_WARNINGS, "Bewegungserinnerung", "Vibriert nach längerem Sitzen"),
)

private fun intervalLabel(v: Int) = when {
    v == 0 -> "Aus"
    v < 0 -> "Intelligent"
    v == 1 -> "Jede Minute"
    else -> "Alle $v Minuten"
}

@Composable
private fun MeasurementsPage(state: StrapUiState) {
    val controller = LocalContext.current.app.repo.controller
    val settings = state.settings
    if (settings == null) {
        SettingsNote(
            if (state.connected) "Lade Einstellungen vom Strap …"
            else "Die Einstellungen werden direkt auf dem Strap gespeichert. Verbinde den Strap, um sie zu ändern.",
        )
        return
    }
    val enabled = state.connected
    fun apply(s: HealthSetting, v: Int) {
        controller?.applySettings(mapOf(s to v))
    }

    SettingsHeader("Herzfrequenz")
    settings.values[HealthSetting.HEART_RATE_INTERVAL]?.let { current ->
        val options = settings.options[HealthSetting.HEART_RATE_INTERVAL]?.takeIf { it.isNotEmpty() } ?: listOf(0, 1, 5, 10, 30)
        ChoiceSetting(
            "Puls rund um die Uhr",
            options.map { it to intervalLabel(it) },
            current,
            subtitle = "Kürzer = genauer, länger = mehr Akku",
            enabled = enabled,
        ) { apply(HealthSetting.HEART_RATE_INTERVAL, it) }
    }
    Switches(heartSwitches, settings, enabled, ::apply)
    AlertChoice("Warnung bei hohem Puls", HealthSetting.HEART_RATE_HIGH_ALERT, settings, enabled, "bpm", ::apply)
    AlertChoice("Warnung bei niedrigem Puls", HealthSetting.HEART_RATE_LOW_ALERT, settings, enabled, "bpm", ::apply)

    SettingsHeader("Schlaf")
    Switches(sleepSwitches, settings, enabled, ::apply)

    SettingsHeader("Stress & mehr")
    Switches(otherSwitches, settings, enabled, ::apply)
    AlertChoice("Warnung bei niedrigem SpO₂", HealthSetting.SPO2_LOW_ALERT, settings, enabled, "%", ::apply)
    SettingsNote("Nur Einstellungen, die dein Strap meldet, werden angezeigt. Änderungen übernimmt der Strap sofort.")
}

@Composable
private fun Switches(list: List<SwitchInfo>, settings: HealthConfig, enabled: Boolean, apply: (HealthSetting, Int) -> Unit) {
    for (info in list) {
        val value = settings.bool(info.setting) ?: continue
        SwitchSetting(info.title, info.description, value, enabled) { apply(info.setting, if (it) 1 else 0) }
    }
}

@Composable
private fun AlertChoice(
    title: String,
    setting: HealthSetting,
    settings: HealthConfig,
    enabled: Boolean,
    unit: String,
    apply: (HealthSetting, Int) -> Unit,
) {
    val current = settings.values[setting]?.let { it and 0xff } ?: return
    val options = settings.options[setting]?.map { it and 0xff }?.takeIf { it.isNotEmpty() } ?: return
    ChoiceSetting(title, options.map { it to if (it == 0) "Aus" else "$it $unit" }, current, enabled = enabled) { apply(setting, it) }
}

// --------------------------------------------------------------------------- Profil

@Composable
private fun ProfilePage() {
    val prefs = LocalContext.current.app.prefs
    prefsTick()
    val p = prefs.profile

    SettingsHeader("Körper")
    IntSetting("Alter", p.age, 5..110, "Jahre") { prefs.profile = prefs.profile.copy(age = it) }
    ChoiceSetting("Geschlecht", listOf(true to "männlich", false to "weiblich"), p.male, subtitle = "für die Kalorienformel") {
        prefs.profile = prefs.profile.copy(male = it)
    }
    IntSetting("Gewicht", p.weightKg, 20..300, "kg") { prefs.profile = prefs.profile.copy(weightKg = it) }
    IntSetting("Größe", p.heightCm, 80..250, "cm") { prefs.profile = prefs.profile.copy(heightCm = it) }

    SettingsHeader("Puls & Ziele")
    IntSetting(
        "Maximaler Puls",
        p.maxHrOverride,
        0..230,
        display = if (p.maxHrOverride > 0) "${p.maxHrOverride} bpm" else "Automatisch (${HeartRateZones.maxHeartRate(p.age)} bpm)",
        subtitle = "0 = aus dem Alter berechnen",
    ) { prefs.profile = prefs.profile.copy(maxHrOverride = if (it in 1..99) 0 else it) }
    IntSetting("Schrittziel", p.stepGoal, 500..100_000, "Schritte") { prefs.profile = prefs.profile.copy(stepGoal = it) }

    SettingsHeader("Deine Pulszonen")
    (0..4).forEach { z ->
        val from = HeartRateZones.lowerBpm(z, p.maxHeartRate)
        val to = if (z < 4) HeartRateZones.lowerBpm(z + 1, p.maxHeartRate) - 1 else p.maxHeartRate
        SettingRow("Zone ${z + 1} · ${HeartRateZones.names[z]}", "$from – $to bpm")
    }
    SettingsNote("Die Werte bleiben auf dem Handy und werden für Zonen und Kalorien verwendet.")
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

// --------------------------------------------------------------------------- Daten

@Composable
private fun DataPage(state: StrapUiState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val importGadgetbridge = de.waschbeken.heliolink.ui.rememberGadgetbridgeImport {
        context.app.repo.controller?.reconnect()
    }
    SettingsHeader("Import")
    SettingRow("Aus Gadgetbridge importieren", "Zip aus „Daten exportieren“: Verlauf, Schlafphasen, SpO₂, Auth-Key") {
        importGadgetbridge()
    }

    SettingsHeader("Export")
    SettingRow("Aktivitätsdaten als CSV", "Minutenwerte: Schritte, Puls, Schlaf – z. B. für Excel") {
        scope.launch { shareCsv(context) }
    }
    SettingsNote("Trainings exportierst du einzeln im Tab „Training“ als TCX.")

    SettingsHeader("Fehlersuche")
    SettingRow("Protokoll anzeigen", "${state.log.size} Einträge der Verbindung") { showLog = true }

    SettingsHeader("Zurücksetzen")
    SettingRow("Alle Gesundheitsdaten löschen", "Puls, Schlaf, Stress und Trainings auf diesem Handy") { confirmDelete = true }

    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("Protokoll") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (state.log.isEmpty()) Text("Noch keine Einträge")
                    state.log.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text("Schließen") } },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Alle Daten löschen?",
            "Alle gespeicherten Gesundheitsdaten und Trainings werden von diesem Handy gelöscht. Das lässt sich nicht rückgängig machen. Die Daten auf dem Strap werden beim nächsten Abruf nicht erneut geladen.",
            "Löschen",
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { context.app.db.clearAll() }
                    context.app.repo.dataChanged()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

private suspend fun shareCsv(context: Context) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        File(dir, "helio-aktivitaet.csv").apply { writeText(context.app.db.exportActivityCsv()) }
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/csv")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Daten exportieren"))
}
