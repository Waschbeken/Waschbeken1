package de.waschbeken.heliolink.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.HealthConfig
import de.waschbeken.heliolink.core.HealthSetting
import de.waschbeken.heliolink.core.HeartRateZones
import de.waschbeken.heliolink.core.parseHex
import de.waschbeken.heliolink.data.UserProfile
import de.waschbeken.heliolink.service.StrapController
import de.waschbeken.heliolink.service.StrapUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class SwitchInfo(val setting: HealthSetting, val title: String, val description: String)

private val switches = listOf(
    SwitchInfo(
        HealthSetting.HEART_RATE_BROADCAST, "Puls an andere Geräte senden",
        "Der Strap verhält sich wie ein normaler Brustgurt: Laufband, Radcomputer, Zwift, Strava & Co. " +
            "können den Puls direkt per Bluetooth empfangen. Braucht etwas mehr Akku.",
    ),
    SwitchInfo(
        HealthSetting.HEART_RATE_ACTIVITY_MONITORING, "Puls bei Aktivität häufiger messen",
        "Erkennt Bewegung und misst dann öfter.",
    ),
    SwitchInfo(
        HealthSetting.SLEEP_HIGH_ACCURACY, "Schlaf mit hoher Genauigkeit",
        "Nutzt den Pulssensor für die Schlafphasen (REM/Tief). Empfohlen.",
    ),
    SwitchInfo(HealthSetting.SLEEP_BREATHING_QUALITY, "Atmungsqualität im Schlaf", "Zusätzliche Messung in der Nacht."),
    SwitchInfo(HealthSetting.STRESS_MONITORING, "Stress automatisch messen", "Ermittelt den Stresswert aus der Herzfrequenzvariabilität."),
    SwitchInfo(HealthSetting.STRESS_RELAXATION_REMINDER, "Entspannungs-Erinnerung", "Vibriert bei hohem Stress."),
    SwitchInfo(HealthSetting.SPO2_ALL_DAY, "SpO₂ ganztägig", "Blutsauerstoff regelmäßig messen."),
    SwitchInfo(HealthSetting.INACTIVITY_WARNINGS, "Bewegungserinnerung", "Vibriert nach längerem Sitzen."),
)

@Composable
fun SettingsScreen(state: StrapUiState, onForget: () -> Unit) {
    val context = LocalContext.current
    val controller = context.app.repo.controller

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StrapCard(state, controller)
        HealthSettingsCard(state.settings, state.connected, controller)
        ProfileCard()
        AppCard(state, controller, onForget)
        LogCard(state.log)
    }
}

@Composable
private fun StrapCard(state: StrapUiState, controller: StrapController?) {
    val prefs = LocalContext.current.app.prefs
    SectionCard(prefs.deviceName ?: "Helio Strap") {
        Text("MAC: ${prefs.deviceAddress}", style = MaterialTheme.typography.bodySmall)
        state.firmware?.let { Text("Firmware: $it", style = MaterialTheme.typography.bodySmall) }
        state.serial?.let { Text("Seriennummer: $it", style = MaterialTheme.typography.bodySmall) }
        state.battery?.let {
            Text("Akku: $it %" + if (state.charging) " (lädt)" else "", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { controller?.findStrap() }, enabled = state.connected) { Text("Strap vibrieren") }
            OutlinedButton(onClick = { controller?.reconnect() }) { Text("Neu verbinden") }
        }
    }
}

private fun intervalLabel(v: Int) = when {
    v == 0 -> "Aus"
    v < 0 -> "Intelligent"
    v == 1 -> "jede Minute"
    else -> "alle $v min"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HealthSettingsCard(settings: HealthConfig?, connected: Boolean, controller: StrapController?) {
    SectionCard("Messungen am Strap") {
        if (settings == null) {
            Text(
                if (connected) "Lade Einstellungen vom Strap…" else "Verbinde den Strap, um seine Einstellungen zu ändern.",
                style = MaterialTheme.typography.bodySmall,
            )
            return@SectionCard
        }
        settings.values[HealthSetting.HEART_RATE_INTERVAL]?.let { current ->
            Text("Puls rund um die Uhr", style = MaterialTheme.typography.titleSmall)
            val options = settings.options[HealthSetting.HEART_RATE_INTERVAL]?.takeIf { it.isNotEmpty() } ?: listOf(0, 1, 5, 10, 30)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { v ->
                    FilterChip(
                        selected = current == v,
                        onClick = { controller?.applySettings(mapOf(HealthSetting.HEART_RATE_INTERVAL to v)) },
                        label = { Text(intervalLabel(v)) },
                        enabled = connected,
                    )
                }
            }
            HorizontalDivider()
        }
        for (info in switches) {
            val value = settings.bool(info.setting) ?: continue
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(info.title)
                    Text(info.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = value,
                    enabled = connected,
                    onCheckedChange = { controller?.applySettings(mapOf(info.setting to if (it) 1 else 0)) },
                )
            }
        }
        AlertOptions("Warnung bei hohem Puls", HealthSetting.HEART_RATE_HIGH_ALERT, settings, connected, controller, "bpm")
        AlertOptions("Warnung bei niedrigem Puls", HealthSetting.HEART_RATE_LOW_ALERT, settings, connected, controller, "bpm")
        AlertOptions("Warnung bei niedrigem SpO₂", HealthSetting.SPO2_LOW_ALERT, settings, connected, controller, "%")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertOptions(
    title: String,
    setting: HealthSetting,
    settings: HealthConfig,
    connected: Boolean,
    controller: StrapController?,
    unit: String,
) {
    val current = settings.values[setting] ?: return
    val options = settings.options[setting]?.map { it and 0xff }?.takeIf { it.isNotEmpty() } ?: return
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { v ->
            FilterChip(
                selected = (current and 0xff) == v,
                onClick = { controller?.applySettings(mapOf(setting to v)) },
                label = { Text(if (v == 0) "Aus" else "$v $unit") },
                enabled = connected,
            )
        }
    }
}

@Composable
private fun ProfileCard() {
    val prefs = LocalContext.current.app.prefs
    val initial = remember { prefs.profile }
    var age by remember { mutableStateOf(initial.age.toString()) }
    var weight by remember { mutableStateOf(initial.weightKg.toString()) }
    var height by remember { mutableStateOf(initial.heightCm.toString()) }
    var male by remember { mutableStateOf(initial.male) }
    var maxHr by remember { mutableStateOf(if (initial.maxHrOverride > 0) initial.maxHrOverride.toString() else "") }
    var goal by remember { mutableStateOf(initial.stepGoal.toString()) }
    var saved by remember { mutableIntStateOf(0) }

    SectionCard("Profil") {
        Text(
            "Wird für Pulszonen und Kalorien verwendet und bleibt auf dem Handy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Alter", age, Modifier.weight(1f)) { age = it }
            NumberField("Gewicht kg", weight, Modifier.weight(1f)) { weight = it }
            NumberField("Größe cm", height, Modifier.weight(1f)) { height = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = male, onClick = { male = true }, label = { Text("männlich") })
            FilterChip(selected = !male, onClick = { male = false }, label = { Text("weiblich") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Max. Puls (leer = auto)", maxHr, Modifier.weight(1f)) { maxHr = it }
            NumberField("Schrittziel", goal, Modifier.weight(1f)) { goal = it }
        }
        val a = age.toIntOrNull() ?: initial.age
        Text(
            "Max. Puls: ${maxHr.toIntOrNull() ?: HeartRateZones.maxHeartRate(a)} bpm",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = {
            prefs.profile = UserProfile(
                age = a.coerceIn(5, 110),
                weightKg = (weight.toIntOrNull() ?: initial.weightKg).coerceIn(20, 300),
                heightCm = (height.toIntOrNull() ?: initial.heightCm).coerceIn(80, 250),
                male = male,
                maxHrOverride = maxHr.toIntOrNull()?.coerceIn(100, 230) ?: 0,
                stepGoal = (goal.toIntOrNull() ?: initial.stepGoal).coerceIn(500, 100_000),
            )
            saved++
        }) { Text(if (saved > 0) "Gespeichert ✓" else "Speichern") }
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(6)) },
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppCard(state: StrapUiState, controller: StrapController?, onForget: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.app.prefs
    val scope = rememberCoroutineScope()
    var autoSync by remember { mutableIntStateOf(prefs.autoSyncMinutes) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    SectionCard("App") {
        Text("Automatisch synchronisieren", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "Aus", 15 to "15 min", 30 to "30 min", 60 to "1 h", 180 to "3 h").forEach { (v, label) ->
                FilterChip(selected = autoSync == v, onClick = { autoSync = v; prefs.autoSyncMinutes = v }, label = { Text(label) })
            }
        }
        HorizontalDivider()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { shareCsv(context) } }) { Text("Daten als CSV") }
            OutlinedButton(onClick = {
                prefs.resetCursors()
                controller?.syncNow()
            }, enabled = state.connected && !state.syncing) { Text("Verlauf neu laden") }
            OutlinedButton(onClick = { showKeyDialog = true }) { Text("Auth-Key ändern") }
            TextButton(onClick = { confirmForget = true }) { Text("Strap entfernen", color = MaterialTheme.colorScheme.error) }
        }
    }

    if (showKeyDialog) {
        var key by remember { mutableStateOf(prefs.authKeyHex.orEmpty()) }
        val valid = parseHex(key)?.size == 16
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("Auth-Key") },
            text = {
                OutlinedTextField(value = key, onValueChange = { key = it.trim() }, isError = !valid, singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    prefs.authKeyHex = key
                    showKeyDialog = false
                    controller?.reconnect()
                }) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { showKeyDialog = false }) { Text("Abbrechen") } },
        )
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Strap entfernen?") },
            text = { Text("Verbindung und Auth-Key werden gelöscht. Deine gespeicherten Daten bleiben erhalten.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; onForget() }) { Text("Entfernen") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun LogCard(log: List<String>) {
    var open by remember { mutableStateOf(false) }
    SectionCard("Protokoll", trailing = {
        TextButton(onClick = { open = !open }) { Text(if (open) "ausblenden" else "anzeigen") }
    }) {
        if (open) {
            if (log.isEmpty()) Text("Noch keine Einträge", style = MaterialTheme.typography.bodySmall)
            log.take(80).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
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
