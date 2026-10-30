package de.waschbeken.heliolink.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.BatteryForecast
import de.waschbeken.heliolink.core.BatteryProfiles
import de.waschbeken.heliolink.core.HealthSetting
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.service.StrapUiState
import de.waschbeken.heliolink.ui.dbValue
import kotlinx.coroutines.delay
import java.util.Locale

private fun one(v: Double) = "%.1f".format(Locale.GERMANY, v)

/** "Akku & Leistung": profiles between most data and longest runtime, the measured drain and what costs most. */
@Composable
internal fun BatteryPage(state: StrapUiState) {
    val context = LocalContext.current
    val app = context.app
    val prefs = app.prefs
    val controller = app.repo.controller
    prefsTick()
    val s = rememberStrapSettings(state)
    val config = s.config
    val history by dbValue(state.dataVersion) { db ->
        val now = System.currentTimeMillis() / 1000
        db.values(HealthDb.BATTERY, now - 14 * 86_400L, now + 60)
    }
    var confirm by remember { mutableStateOf<BatteryProfiles.Profile?>(null) }

    SettingsHeader("Strap-Akku")
    val levels = history.orEmpty()
    val nowSec = System.currentTimeMillis() / 1000
    val drain = BatteryProfiles.drainPerDay(levels.filter { it.timestamp >= nowSec - 3 * 86_400 }) ?: BatteryProfiles.drainPerDay(levels)
    val left = BatteryForecast.daysLeft(levels)
    SettingRow(
        "Stand",
        state.battery?.let { "$it %" + if (state.charging) " · lädt" else "" }
            ?: levels.lastOrNull()?.let { "${it.value} % (zuletzt bekannt)" } ?: "Unbekannt",
    )
    SettingRow(
        "Verbrauch",
        when {
            history == null -> "Rechne …"
            drain == null -> "Wird berechnet, sobald etwa ein halber Tag ohne Laden gespeichert ist"
            else -> "ca. ${one(drain)} % pro Tag" + (left?.let { " · reicht noch ca. ${one(it)} Tage" } ?: "")
        },
    )
    val since = prefs.batteryProfileSince
    if (since > 0) {
        BatteryProfiles.drainPerDay(levels.filter { it.timestamp >= since / 1000 })?.let {
            SettingRow("Seit dem letzten Profilwechsel", "ca. ${one(it)} % pro Tag")
        }
    }

    SettingsHeader("Profil")
    if (config == null) {
        SettingsNote(
            if (state.connected) "Lade Einstellungen vom Strap …"
            else "Die Profile stellen die Messungen auf dem Strap um. Verbinde den Strap, um eines zu wählen.",
        )
    } else {
        val current = BatteryProfiles.current(config)
        BatteryProfiles.Profile.entries.forEach { p ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = s.enabled && current != p) { confirm = p }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = current == p, onClick = null, enabled = s.enabled)
                Column(Modifier.padding(start = 8.dp).weight(1f)) {
                    Text(
                        p.label + if (p == BatteryProfiles.Profile.BALANCED) " (empfohlen)" else "",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        p.description + " Abruf alle ${p.syncMinutes} Minuten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (current == null) SettingsNote("Aktuell: eigene Einstellungen (unter „Messungen“). Ein Profil stellt sie neu ein.")
        if (state.pendingSettings.isNotEmpty()) SettingsNote("Wird zum Strap übertragen …")
    }

    SettingsHeader("Sparen ohne Datenverlust")
    SwitchSetting(
        "Live-Puls im Hintergrund pausieren",
        "Verlässt du die App, pausiert der Puls im Sekundentakt und läuft beim Zurückkommen weiter. Trainings und Pulsaufzeichnungen laufen immer weiter.",
        prefs.liveOffInBackground,
    ) { prefs.liveOffInBackground = it }
    if (state.liveHeartRate) {
        SettingRow("Live-Puls läuft gerade", "Kostet spürbar Akku – tippen zum Beenden") { controller?.setLiveHeartRate(false) }
    }
    ChoiceSetting(
        "Daten abrufen",
        listOf(0 to "Nur manuell", 15 to "Alle 15 Minuten", 30 to "Alle 30 Minuten", 60 to "Stündlich", 180 to "Alle 3 Stunden"),
        prefs.autoSyncMinutes,
        subtitle = "seltener = weniger Funkverkehr; die Daten bleiben auf dem Strap",
    ) { prefs.autoSyncMinutes = it }
    if (config?.bool(HealthSetting.HEART_RATE_BROADCAST) == true) {
        SwitchSetting(
            "Puls an andere Geräte senden",
            "Ist an und kostet viel Akku – nur fürs Training mit Laufband, Radcomputer & Co. nötig",
            true,
            s.enabled,
        ) { s.set(HealthSetting.HEART_RATE_BROADCAST, if (it) 1 else 0) }
    }

    SettingsHeader("Handy")
    // Can be changed in the system settings at any time: check again while visible.
    val power = context.getSystemService(PowerManager::class.java)
    var exempt by remember { mutableStateOf(power?.isIgnoringBatteryOptimizations(context.packageName) == true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                exempt = power?.isIgnoringBatteryOptimizations(context.packageName) == true
                delay(2_000)
            }
        }
    }
    SettingRow(
        "Akku-Optimierung von Android",
        if (exempt) "HelioLink ist ausgenommen – der Abruf im Hintergrund läuft zuverlässig"
        else "Aktiv – Android kann Verbindung und Abruf im Hintergrund verzögern. Tippen und HelioLink auf „Nicht optimieren“ stellen",
    ) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    SettingsNote("HelioLink selbst braucht im Hintergrund kaum Akku: Es wartet auf den Strap und ruft nur im eingestellten Abstand Daten ab.")

    SettingsHeader("Was am meisten Akku kostet")
    SettingsNote(
        "1. Live-Puls und Pulsaufzeichnung (Puls im Sekundentakt)\n" +
            "2. Puls an andere Geräte senden\n" +
            "3. SpO₂ ganztägig\n" +
            "4. Puls jede Minute statt „Intelligent“\n" +
            "5. Stress und Atmungsqualität im Schlaf\n" +
            "Der Schlaf mit hoher Genauigkeit kostet wenig und bleibt in jedem Profil an. Einzelne Messungen stellst du unter „Messungen“ ein.",
    )

    confirm?.let { p ->
        ConfirmDialog(
            "${p.label} einstellen?",
            p.description + " Die Daten werden alle ${p.syncMinutes} Minuten abgerufen. Einzelne Messungen kannst du danach unter „Messungen“ wieder ändern.",
            "Einstellen",
            onConfirm = {
                val values = config?.let { BatteryProfiles.settings(p, it) }.orEmpty()
                if (values.isNotEmpty()) controller?.applySettings(values)
                prefs.autoSyncMinutes = p.syncMinutes
                prefs.batteryProfileSince = System.currentTimeMillis()
                if (p == BatteryProfiles.Profile.SAVER && state.liveHeartRate) controller?.setLiveHeartRate(false)
            },
            onDismiss = { confirm = null },
        )
    }
}
