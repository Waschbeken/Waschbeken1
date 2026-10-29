package de.waschbeken.heliolink.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.waschbeken.heliolink.BuildConfig
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.ble.LinkState
import de.waschbeken.heliolink.service.StrapUiState
import de.waschbeken.heliolink.ui.BackTopBar
import de.waschbeken.heliolink.ui.Routes
import de.waschbeken.heliolink.ui.SectionHeader
import de.waschbeken.heliolink.ui.navigateToTab
import de.waschbeken.heliolink.update.UpdateState
import java.text.DateFormat
import java.util.Date

enum class SettingsSection(val title: String) { STRAP("Strap"), APP("App") }

/** One page of the settings: [key] is used in the route `settings/{key}`. */
enum class SettingsCategory(val key: String, val section: SettingsSection, val title: String, val icon: ImageVector, val summary: String) {
    DEVICE("device", SettingsSection.STRAP, "Gerät & Verbindung", Icons.Default.Bluetooth, "Status, Akku, Firmware, Auth-Key, vibrieren lassen"),
    MEASUREMENTS("measurements", SettingsSection.STRAP, "Messungen", Icons.Default.MonitorHeart, "Pulsintervall, Aktivität, Schlaf, Stress, SpO₂, Pulswarnungen"),
    MOVEMENT("movement", SettingsSection.STRAP, "Bewegung & Ziele", Icons.AutoMirrored.Filled.DirectionsWalk, "Bewegungserinnerung, Nicht stören, Schrittziel, Trainingserkennung"),
    ALARMS("alarms", SettingsSection.STRAP, "Wecker", Icons.Default.Alarm, "Vibrationswecker mit Smart Wake auf dem Strap"),

    APPEARANCE("appearance", SettingsSection.APP, "Darstellung", Icons.Default.Palette, "Hell/dunkel, Akzentfarbe, Material You, Schriftgröße"),
    HOME("home", SettingsSection.APP, "Startseite", Icons.Default.Dashboard, "Welche Kacheln auf „Heute“ erscheinen"),
    SLEEP("sleep", SettingsSection.APP, "Schlaf", Icons.Default.Bedtime, "Schlafziel, Wachphasen, Phasen-Quelle, Korrekturen"),
    NOTIFICATIONS("notifications", SettingsSection.APP, "Benachrichtigungen", Icons.Default.Notifications, "Akku, Schlafenszeit, Morgenbericht, Live-Puls"),
    RECORDING("recording", SettingsSection.APP, "Puls-Aufzeichnung", Icons.Default.Timer, "Öfter als jede Minute: jede Sekunde bis alle 30 Sekunden"),
    PROFILE("profile", SettingsSection.APP, "Profil", Icons.Default.Person, "Alter, Gewicht, Größe, Schlaf- und Schrittziel"),
    TRAINING("training", SettingsSection.APP, "Training & Zonen", Icons.Default.FitnessCenter, "Max. Puls, Ruhepuls, Zonen, Pulsgrenzen mit Vibration"),
    SYNC("sync", SettingsSection.APP, "Synchronisierung", Icons.Default.Sync, "Automatischer Abruf, Zeitraum, Schlafphasen, Verlauf neu laden"),
    UPDATES("updates", SettingsSection.APP, "App-Updates", Icons.Default.SystemUpdate, "Automatisch prüfen, Shizuku, Vorabversionen"),
    DATA("data", SettingsSection.APP, "Daten & Sicherung", Icons.Default.Storage, "Export, Sicherung, Gadgetbridge-Import, Protokoll"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.app
    val state by app.repo.state.collectAsStateWithLifecycle()
    val updater = app.updater
    val update by updater.state.collectAsState()
    val prefs = app.prefs
    prefsTick()

    fun open(c: SettingsCategory) {
        if (c == SettingsCategory.ALARMS) nav.navigateToTab(Routes.ALARMS) else nav.navigate(Routes.settings(c.key))
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Einstellungen") }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { StrapCard(state, prefs.deviceName) { open(SettingsCategory.DEVICE) } }
            SettingsSection.entries.forEach { section ->
                item { SectionHeader(section.title) }
                item {
                    SettingsGroup {
                        val list = SettingsCategory.entries.filter { it.section == section }
                        list.forEachIndexed { i, c ->
                            CategoryRow(c.icon, c.title, c.summary) { open(c) }
                            if (i < list.lastIndex) HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
            item { SectionHeader("Über") }
            item {
                SettingsGroup {
                    // "Nach Updates suchen" sits directly here, like in the Lernkarten app.
                    SettingRow(
                        "Nach Updates suchen",
                        when (val u = update) {
                            UpdateState.Checking -> "Suche …"
                            is UpdateState.Available -> "Version ${u.info.version} verfügbar – tippen zum Installieren"
                            is UpdateState.Downloading -> "Lade herunter … ${(u.progress.coerceAtLeast(0f) * 100).toInt()} %"
                            is UpdateState.Ready -> "Bereit zum Installieren"
                            is UpdateState.Installing -> "Wird installiert …"
                            is UpdateState.KeyChanged -> "Version ${u.info.version}: einmalige Neuinstallation nötig – tippen"
                            is UpdateState.Failed -> u.message
                            UpdateState.UpToDate -> "Du hast die neueste Version (${updater.currentVersion})"
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
                    SettingRow("HelioLink ${BuildConfig.VERSION_NAME}", "Ohne Cloud – deine Daten bleiben auf dem Gerät. Für den Amazfit Helio Strap.")
                }
            }
        }
    }
}

@Composable
private fun StrapCard(state: StrapUiState, name: String?, onClick: () -> Unit) {
    val color = when (state.link) {
        LinkState.READY -> MaterialTheme.colorScheme.primary
        LinkState.AUTH_FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Watch, contentDescription = null, tint = color)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name ?: "Amazfit Helio Strap", style = MaterialTheme.typography.titleMedium)
                Text(
                    linkText(state) + (state.battery?.let { " · Akku $it %" + if (state.charging) " (lädt)" else "" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.lastSync > 0) Text("Zuletzt synchronisiert ${formatDate(state.lastSync)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal fun linkText(state: StrapUiState) = when (state.link) {
    LinkState.READY -> "Verbunden"
    LinkState.CONNECTING -> "Verbinde …"
    LinkState.AUTHENTICATING -> "Anmeldung …"
    LinkState.AUTH_FAILED -> "Auth-Key abgelehnt"
    LinkState.DISCONNECTED -> "Nicht verbunden"
}

/** Rounded group of rows, like the settings cards of the Lernkarten app. */
@Composable
fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
fun SettingsCategoryScreen(nav: NavHostController, key: String, onForget: () -> Unit) {
    val app = LocalContext.current.app
    val state by app.repo.state.collectAsStateWithLifecycle()
    val category = SettingsCategory.entries.firstOrNull { it.key == key } ?: SettingsCategory.DEVICE
    Scaffold(topBar = { BackTopBar(category.title, onBack = { nav.popBackStack() }, subtitle = category.section.title) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            when (category) {
                SettingsCategory.DEVICE -> DevicePage(state, onForget)
                SettingsCategory.MEASUREMENTS -> MeasurementsPage(state)
                SettingsCategory.MOVEMENT -> MovementPage(state)
                SettingsCategory.ALARMS -> Unit
                SettingsCategory.APPEARANCE -> AppearancePage()
                SettingsCategory.HOME -> HomeTilesPage()
                SettingsCategory.SLEEP -> SleepSettingsPage(nav)
                SettingsCategory.NOTIFICATIONS -> NotificationsPage()
                SettingsCategory.RECORDING -> RecordingPage(state)
                SettingsCategory.PROFILE -> ProfilePage(state)
                SettingsCategory.TRAINING -> TrainingPage()
                SettingsCategory.SYNC -> SyncPage(state)
                SettingsCategory.UPDATES -> UpdatesPage()
                SettingsCategory.DATA -> DataPage(state)
            }
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
