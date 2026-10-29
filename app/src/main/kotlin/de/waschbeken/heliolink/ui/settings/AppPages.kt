package de.waschbeken.heliolink.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.waschbeken.heliolink.BuildConfig
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.parseHex
import de.waschbeken.heliolink.data.ThemeMode
import de.waschbeken.heliolink.service.StrapUiState
import de.waschbeken.heliolink.ui.AccentPresets
import de.waschbeken.heliolink.ui.home.Tile
import de.waschbeken.heliolink.update.ShizukuInstaller
import de.waschbeken.heliolink.update.UpdateState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// --------------------------------------------------------------------------- Strap: Gerät & Verbindung

@Composable
internal fun DevicePage(state: StrapUiState, onForget: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.app.prefs
    val controller = context.app.repo.controller
    prefsTick()
    var confirmForget by remember { mutableStateOf(false) }

    SettingsHeader("Gerät")
    SettingRow("Name", prefs.deviceName ?: "Amazfit Helio Strap")
    SettingRow("Status", linkText(state) + (state.linkDetail?.let { " · $it" } ?: ""))
    SettingRow("Akku", state.battery?.let { "$it %" + if (state.charging) " · lädt" else "" } ?: "unbekannt")
    SettingRow("MAC-Adresse", prefs.deviceAddress ?: "–")
    state.firmware?.let { SettingRow("Firmware", it) }
    state.serial?.let { SettingRow("Seriennummer", it) }
    if (state.lastSync > 0) SettingRow("Zuletzt synchronisiert", formatDate(state.lastSync))

    SettingsHeader("Aktionen")
    SettingRow("Strap vibrieren lassen", "Zum Wiederfinden – vibriert einige Sekunden", enabled = state.connected) {
        controller?.findStrap()
    }
    SwitchSetting(
        "Live-Puls",
        "Puls im Sekundentakt auf der Startseite anzeigen (kostet Akku)",
        state.liveHeartRate,
        enabled = state.connected,
    ) { controller?.setLiveHeartRate(it) }
    SettingRow("Einstellungen neu laden", "Liest alle Messeinstellungen erneut vom Strap", enabled = state.connected) { controller?.refresh() }
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
    SettingsNote(
        "Der Strap kann nur mit einer App gleichzeitig verbunden sein. Trenne ihn in Zepp oder Gadgetbridge, " +
            "bevor du ihn hier nutzt.",
    )

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

// --------------------------------------------------------------------------- Synchronisierung

@Composable
internal fun SyncPage(state: StrapUiState) {
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
        "Verlauf neu laden",
        "Holt alle Daten erneut vom Strap, z. B. nach einem Fehler",
        enabled = state.connected && !state.syncing,
    ) { confirmReload = true }

    SettingsHeader("Schlafphasen")
    SettingsNote(
        "Der Helio Strap speichert Schlafphasen in einem eigenen Datensatz, den Zepp nicht dokumentiert. HelioLink sucht " +
            "ihn automatisch. Bis er gefunden ist, schätzt die App Tief-, Leicht- und REM-Schlaf aus Puls und Bewegung " +
            "(rund 70 % Übereinstimmung mit dem Strap).",
    )
    SettingRow(
        "Schlafphasen vom Strap",
        when (prefs.sleepCode) {
            -1 -> if (prefs.sleepProbeTried > 0) "Suche läuft – ${prefs.sleepProbeTried} Kandidaten geprüft" else "Noch nicht gesucht – passiert nach dem ersten Abruf"
            0 -> "Nicht gefunden – Schätzung aktiv. Tippen, um erneut zu suchen"
            else -> "Aktiv (Code 0x%02x) – tippen, um neu zu suchen".format(prefs.sleepCode)
        },
        enabled = state.connected && !state.syncing,
    ) { controller?.probeSleepCode() }
    SettingRow("Suche zurücksetzen", "Fängt die Suche von vorn an, z. B. nach einem Firmware-Update") {
        prefs.sleepProbeTried = 0
        prefs.sleepCode = -1
        Toast.makeText(context, "Die Suche startet beim nächsten Abruf", Toast.LENGTH_SHORT).show()
    }
    SettingsNote("Tipp: Mit „Daten & Sicherung › Gadgetbridge importieren“ übernimmst du die echten Schlafphasen aus einem Gadgetbridge-Export.")

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
internal fun NotificationsPage() {
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

    SettingsHeader("Schlaf")
    ChoiceSetting(
        "Schlafenszeit-Erinnerung",
        listOf(0 to "Aus", 15 to "15 Minuten vorher", 30 to "30 Minuten vorher", 45 to "45 Minuten vorher", 60 to "1 Stunde vorher"),
        prefs.bedtimeReminder,
        subtitle = "nächster Wecker minus Schlafziel",
    ) { prefs.bedtimeReminder = it }
    SwitchSetting(
        "Morgenbericht",
        "Nach dem ersten Abruf am Morgen: Schlafdauer, Phasen und Erholung",
        prefs.morningSummary,
    ) { prefs.morningSummary = it }

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppearancePage() {
    val prefs = LocalContext.current.app.prefs
    prefsTick()
    SettingsHeader("Design")
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { mode ->
            FilterChip(selected = prefs.themeMode == mode, onClick = { prefs.themeMode = mode }, label = { Text(mode.label) })
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        SwitchSetting("Material You", "Farben aus deinem Hintergrundbild übernehmen", prefs.dynamicColor) { prefs.dynamicColor = it }
    }

    SettingsHeader("Akzentfarbe")
    FlowRow(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AccentPresets.forEach { (argb, name) ->
            val selected = prefs.accentColor == argb && !prefs.dynamicColor
            Box(
                Modifier
                    .size(44.dp)
                    .background(Color(argb), CircleShape)
                    .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .clickable {
                        prefs.accentColor = argb
                        prefs.dynamicColor = false
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Default.Check, contentDescription = name, tint = Color.White)
            }
        }
    }
    val name = AccentPresets.firstOrNull { it.first == prefs.accentColor }?.second
    if (name != null && !prefs.dynamicColor) SettingsNote("Gewählt: $name")
    if (prefs.dynamicColor) SettingsNote("Material You ist an – die Akzentfarbe gilt, sobald du es ausschaltest.")

    SettingsHeader("Schriftgröße")
    var scale by remember { mutableFloatStateOf(prefs.fontScale) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            "Beispieltext · ${(scale * 100).toInt()} %",
            style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
            value = scale,
            onValueChange = { scale = (it * 20).roundToInt() / 20f },
            onValueChangeFinished = { prefs.fontScale = scale },
            valueRange = 0.85f..1.3f,
            steps = 8,
        )
    }
    SettingsNote("Pulszonen und Schlafphasen behalten ihre festen Farben, damit sie immer gleich lesbar sind.")
}

// --------------------------------------------------------------------------- Startseite

@Composable
internal fun HomeTilesPage() {
    val prefs = LocalContext.current.app.prefs
    prefsTick()
    SettingsHeader("Kacheln auf „Heute“")
    val hidden = prefs.hiddenTiles
    Tile.entries.forEach { tile ->
        SwitchSetting(tile.title, null, tile.name !in hidden) { show ->
            prefs.hiddenTiles = if (show) hidden - tile.name else hidden + tile.name
        }
    }
    SettingRow("Alle anzeigen", "Setzt die Auswahl zurück") { prefs.hiddenTiles = emptySet() }
    SettingsNote("Ausgeblendete Werte werden weiter gespeichert und erscheinen im Export.")
}

// --------------------------------------------------------------------------- Updates

@Composable
internal fun UpdatesPage() {
    val context = LocalContext.current
    val prefs = context.app.prefs
    val updater = context.app.updater
    val update by updater.state.collectAsState()
    prefsTick()

    // Shizuku can be started or stopped any time: re-check while the page is visible.
    var shizuku by remember { mutableStateOf(ShizukuInstaller.status()) }
    var recheck by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(recheck) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                shizuku = ShizukuInstaller.status()
                delay(2_000)
            }
        }
    }

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
            is UpdateState.Downloading -> "Lade herunter … ${(u.progress.coerceAtLeast(0f) * 100).toInt()} %"
            is UpdateState.Installing -> "Wird installiert …"
            is UpdateState.Failed -> u.message
            UpdateState.UpToDate -> "Du hast die neueste Version"
            else -> "Installiert: ${updater.currentVersion}"
        },
    ) {
        if (update is UpdateState.Available || update is UpdateState.Ready || update is UpdateState.KeyChanged) updater.showDialog.value = true else updater.check()
    }
    if (prefs.skippedVersion.isNotBlank()) {
        SettingRow("Übersprungene Version", "${prefs.skippedVersion} – tippen, um sie wieder anzubieten") {
            prefs.skippedVersion = ""
        }
    }

    SettingsHeader("Installieren mit Shizuku")
    SettingsNote(
        "Mit Shizuku installiert HelioLink Updates selbst – ohne den Android-Dialog „Installieren?“. " +
            "Shizuku läuft per Wireless-Debugging oder Root und muss nach jedem Neustart einmal gestartet werden.",
    )
    SettingRow(
        "Shizuku-Status",
        when (shizuku) {
            ShizukuInstaller.Status.READY -> "Bereit – HelioLink darf installieren"
            ShizukuInstaller.Status.NO_PERMISSION -> "Läuft, aber HelioLink ist noch nicht erlaubt – tippen"
            ShizukuInstaller.Status.UNSUPPORTED -> "Shizuku ist zu alt – bitte aktualisieren"
            ShizukuInstaller.Status.NOT_RUNNING -> "Nicht gestartet oder nicht installiert – tippen"
        },
    ) {
        when (shizuku) {
            ShizukuInstaller.Status.NO_PERMISSION -> ShizukuInstaller.requestPermission { granted ->
                recheck++
                if (granted) prefs.shizukuInstall = true
            }
            ShizukuInstaller.Status.READY -> Unit
            else -> {
                val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                try {
                    context.startActivity(
                        (launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, "Shizuku nicht gefunden", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    SwitchSetting(
        "Updates über Shizuku installieren",
        if (shizuku == ShizukuInstaller.Status.READY) "Ohne Bestätigung, die App startet danach neu"
        else "Fällt auf den Android-Installer zurück, solange Shizuku nicht bereit ist",
        prefs.shizukuInstall,
    ) { on ->
        prefs.shizukuInstall = on
        if (on && shizuku == ShizukuInstaller.Status.NO_PERMISSION) ShizukuInstaller.requestPermission { recheck++ }
    }
    SwitchSetting(
        "Im Hintergrund installieren",
        "Neue Versionen automatisch laden und installieren, während die App nicht offen ist",
        prefs.autoInstallUpdates,
        enabled = prefs.shizukuInstall && prefs.autoCheckUpdates,
    ) { prefs.autoInstallUpdates = it }

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

// --------------------------------------------------------------------------- Schlaf

@Composable
internal fun SleepSettingsPage(nav: androidx.navigation.NavHostController) {
    val context = LocalContext.current
    val app = context.app
    val prefs = app.prefs
    val tick = prefsTick()
    val state by app.repo.state.collectAsState()
    val edits by de.waschbeken.heliolink.ui.dbValue(state.dataVersion, tick) { it.sleepEditCount() }
    var confirmClear by remember { mutableStateOf(false) }
    fun changed() = app.repo.dataChanged()

    SettingsHeader("Ziel")
    ChoiceSetting(
        "Schlafziel",
        (300..600 step 30).map { it to "%d:%02d h".format(it / 60, it % 60) },
        prefs.profile.sleepGoalMinutes,
        subtitle = "für Score, Schlafdefizit und Schlafenszeit-Erinnerung",
    ) { prefs.profile = prefs.profile.copy(sleepGoalMinutes = it); changed() }
    SwitchSetting(
        "Morgens nachfragen",
        "Auf der Schlafseite „Stimmt deine Nacht?“ zeigen, damit du schnell korrigieren kannst",
        prefs.sleepAskCheck,
    ) { prefs.sleepAskCheck = it }

    SettingsHeader("Erkennung")
    ChoiceSetting(
        "Kurze Wachphasen",
        listOf(0 to "Alle zeigen", 2 to "Unter 2 min ignorieren", 5 to "Unter 5 min ignorieren", 10 to "Unter 10 min ignorieren"),
        prefs.sleepIgnoreAwake,
        subtitle = "kurzes Aufwachen als Leichtschlaf zählen",
    ) { prefs.sleepIgnoreAwake = it; changed() }
    ChoiceSetting(
        "Wach in der Nacht",
        listOf(30 to "Bis 30 min", 45 to "Bis 45 min", 60 to "Bis 1 h", 90 to "Bis 1,5 h", 120 to "Bis 2 h"),
        prefs.sleepMaxGap,
        subtitle = "längeres Wachsein teilt die Nacht",
    ) { prefs.sleepMaxGap = it; changed() }
    ChoiceSetting(
        "Kürzester Schlaf",
        listOf(20 to "20 min (mit Nickerchen)", 45 to "45 min", 90 to "1,5 h", 180 to "3 h (nur Nachtschlaf)"),
        prefs.sleepMinMinutes,
        subtitle = "kürzerer Schlaf wird nicht als Nacht gezählt",
    ) { prefs.sleepMinMinutes = it; changed() }
    ChoiceSetting(
        "Einschlafen später werten",
        (0..60 step 5).map { it to if (it == 0) "Nein" else "$it min später" },
        prefs.sleepOnsetDelay,
        subtitle = "wenn der Strap stilles Liegen schon als Schlaf zählt",
    ) { prefs.sleepOnsetDelay = it; changed() }
    ChoiceSetting(
        "Aufwachen früher werten",
        (0..60 step 5).map { it to if (it == 0) "Nein" else "$it min früher" },
        prefs.sleepWakeEarlier,
        subtitle = "wenn du nach dem Aufwachen noch liegen bleibst",
    ) { prefs.sleepWakeEarlier = it; changed() }
    SwitchSetting(
        "Phasen immer schätzen",
        "Statt der Phasen vom Strap oder aus Gadgetbridge die Schätzung aus Puls und Bewegung nutzen",
        prefs.sleepAlwaysEstimate,
    ) { prefs.sleepAlwaysEstimate = it; changed() }
    SettingsNote(
        "Die Einstellungen ändern nur die Anzeige – die gespeicherten Daten bleiben unverändert. " +
            "Gadgetbridge und der Strap liegen bei den Phasen nicht immer richtig: Einzelne Nächte korrigierst du auf der Schlafseite mit dem Stift.",
    )

    SettingsHeader("Korrekturen")
    val bias by de.waschbeken.heliolink.ui.dbValue(state.dataVersion, tick) {
        de.waschbeken.heliolink.data.Queries.correctionBias(it, java.time.ZoneId.systemDefault())
    }
    bias?.let { (onset, wake) ->
        if (onset >= 5 || wake >= 5) {
            SettingRow(
                "Aus deinen Korrekturen lernen",
                buildString {
                    append("Laut deinen Korrekturen ")
                    if (onset >= 5) append("schläfst du im Schnitt $onset min später ein")
                    if (onset >= 5 && wake >= 5) append(" und ")
                    if (wake >= 5) append("wachst $wake min früher auf")
                    append(" als erkannt. Tippen, um das für alle Nächte zu übernehmen.")
                },
            ) {
                fun step(v: Int) = ((v + 2) / 5 * 5).coerceIn(0, 60)
                if (onset >= 5) prefs.sleepOnsetDelay = step(prefs.sleepOnsetDelay + onset)
                if (wake >= 5) prefs.sleepWakeEarlier = step(prefs.sleepWakeEarlier + wake)
                changed()
                Toast.makeText(context, "Übernommen", Toast.LENGTH_SHORT).show()
            }
        }
    }
    SettingRow("Letzte Nacht bearbeiten", "Zeiten und Phasen anpassen") {
        nav.navigate(de.waschbeken.heliolink.ui.Routes.sleepEdit(java.time.LocalDate.now().toEpochDay()))
    }
    SettingRow("Korrigierte Nächte", "${edits ?: 0}")
    SettingRow("Alle Korrekturen löschen", "Zeigt wieder die erkannten Nächte", enabled = (edits ?: 0) > 0) { confirmClear = true }
    SettingRow("Schlafphasen vom Strap", "Suche nach dem Schlafdatensatz des Straps") {
        nav.navigate(de.waschbeken.heliolink.ui.Routes.settings("sync"))
    }

    if (confirmClear) {
        ConfirmDialog(
            "Alle Korrekturen löschen?",
            "Alle Nächte, die du bearbeitet hast, zeigen wieder die erkannten Werte.",
            "Löschen",
            onConfirm = {
                app.db.clearSleepEdits()
                changed()
            },
            onDismiss = { confirmClear = false },
        )
    }
}
