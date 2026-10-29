package de.waschbeken.heliolink.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.waschbeken.heliolink.BuildConfig
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.data.AutoBackup
import de.waschbeken.heliolink.data.Backup
import de.waschbeken.heliolink.service.StrapUiState
import de.waschbeken.heliolink.ui.exportFile
import de.waschbeken.heliolink.ui.rememberGadgetbridgeImport
import de.waschbeken.heliolink.ui.rememberOpenFile
import de.waschbeken.heliolink.ui.rememberSaveFile
import de.waschbeken.heliolink.ui.shareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun exportInfo(context: Context, state: StrapUiState): String = buildString {
    appendLine("HelioLink ${BuildConfig.VERSION_NAME}")
    appendLine("Exportiert: ${java.time.LocalDateTime.now().withNano(0)}")
    appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
    appendLine("Strap: ${context.app.prefs.deviceName ?: "-"}, Firmware ${state.firmware ?: "?"}, Akku ${state.battery ?: "?"} %")
    appendLine("Schlafphasen-Code: ${context.app.prefs.sleepCode}")
    appendLine("Puls-Aufzeichnung: ${context.app.prefs.continuousHrSeconds} s")
    appendLine("Einstellungen vom Strap: ${state.settings?.values?.entries?.joinToString { "${it.key}=${it.value}" } ?: "-"}")
    appendLine()
    appendLine("Zeitspalte \"zeit\" in Ortszeit, \"unix\" in Sekunden seit 1970 (UTC). Kein Auth-Key enthalten.")
}

@Composable
internal fun DataPage(state: StrapUiState) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var range by remember { mutableStateOf(Backup.Range.ALL) }
    var showLog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    val backup = Backup(context, app.prefs, app.db)
    val saveExport = rememberSaveFile("application/zip", "Export gespeichert") { uri ->
        backup.exportData(uri, range, state.log, exportInfo(context, state))
    }
    val saveBackup = rememberSaveFile("application/zip", "Sicherung gespeichert") { uri -> backup.createBackup(uri) }
    val openBackup = rememberOpenFile(
        arrayOf("application/zip", "application/octet-stream", "*/*"),
        "Sicherung wiederhergestellt",
        onSuccess = { de.waschbeken.heliolink.service.StrapService.start(context) },
    ) { uri ->
        app.repo.controller?.shutdown()
        backup.restore(uri)
        app.repo.dataChanged()
    }
    val importGadgetbridge = rememberGadgetbridgeImport { app.repo.controller?.reconnect() }
    prefsTick()
    val prefs = app.prefs
    var backupRunning by remember { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                prefs.autoBackupTree = uri.toString()
            } catch (e: SecurityException) {
                Toast.makeText(context, "Dieser Ordner lässt sich nicht dauerhaft nutzen", Toast.LENGTH_LONG).show()
            }
        }
    }
    fun backupNow() {
        backupRunning = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AutoBackup.run(context) } }
            backupRunning = false
            Toast.makeText(
                context,
                result.fold({ "Gesichert: $it" }, { "Sicherung fehlgeschlagen: ${it.message}" }),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    SettingsHeader("Export")
    ChoiceSetting("Zeitraum", Backup.Range.entries.map { it to it.label }, range) { range = it }
    SettingRow(
        "Daten exportieren – speichern",
        "Zip mit CSV-Dateien (Puls, Schritte, Schlaf, Stress, SpO₂, Trainings) und Protokoll. Ort frei wählbar, z. B. Downloads.",
    ) { saveExport("heliolink-export-${Backup.fileDate()}.zip") }
    SettingRow("Daten exportieren – teilen", "Dieselbe Zip-Datei per Mail, Messenger oder Drive senden") {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                exportFile(context, "heliolink-export-${Backup.fileDate()}.zip").also { f ->
                    backup.exportData(android.net.Uri.fromFile(f), range, state.log, exportInfo(context, state))
                }
            }
            shareFile(context, file, "application/zip", "Export teilen")
        }
    }
    SettingsNote("Der Export enthält keinen Auth-Key und kann gefahrlos geteilt werden. Trainings gibt es einzeln als TCX im Tab „Training“.")

    SettingsHeader("Automatische Sicherung")
    ChoiceSetting(
        "Automatisch sichern",
        listOf(0 to "Aus", 1 to "Täglich", 3 to "Alle 3 Tage", 7 to "Wöchentlich"),
        prefs.autoBackupDays,
        subtitle = "beim Öffnen der App oder nach dem Abruf",
    ) { prefs.autoBackupDays = it }
    SettingRow(
        "Speicherort",
        AutoBackup.placeName(context) + if (prefs.autoBackupTree == null) " · tippen, um einen Ordner zu wählen (z. B. Google Drive)" else " · tippen zum Ändern",
    ) { pickFolder.launch(null) }
    if (prefs.autoBackupTree != null) {
        SettingRow("Ordner zurücksetzen", "Wieder in Downloads/HelioLink sichern") { prefs.autoBackupTree = null }
    }
    ChoiceSetting(
        "Aufbewahren",
        listOf(3 to "Letzte 3", 7 to "Letzte 7", 14 to "Letzte 14", 30 to "Letzte 30"),
        prefs.autoBackupKeep,
        subtitle = "ältere automatische Sicherungen werden gelöscht",
    ) { prefs.autoBackupKeep = it }
    SwitchSetting(
        "Vor jedem Update sichern",
        "Bevor eine neue Version installiert wird",
        prefs.autoBackupBeforeUpdate,
    ) { prefs.autoBackupBeforeUpdate = it }
    SettingRow(
        "Jetzt automatisch sichern",
        when {
            backupRunning -> "Sichere …"
            prefs.lastAutoBackupFailed -> "Letzter Versuch fehlgeschlagen: ${prefs.lastAutoBackupInfo}"
            prefs.lastAutoBackup > 0 -> "Zuletzt ${formatDate(prefs.lastAutoBackup)} · ${prefs.lastAutoBackupInfo}"
            else -> "Noch keine automatische Sicherung"
        },
        enabled = !backupRunning,
    ) { backupNow() }
    SettingsNote(
        "Automatische Sicherungen enthalten wie die normale Sicherung deinen Auth-Key und bleiben auch erhalten, wenn du " +
            "HelioLink deinstallierst. Wiederherstellen: „Sicherung wiederherstellen“ und die neueste Datei heliolink-auto-… wählen.",
    )

    SettingsHeader("Sicherung")
    SettingRow("Sicherung erstellen", "Alle Daten und Einstellungen inkl. Auth-Key – zum Umzug auf ein neues Handy") {
        saveBackup("heliolink-sicherung-${Backup.fileDate()}.zip")
    }
    SettingRow("Sicherung wiederherstellen", "Ersetzt alle Daten und Einstellungen auf diesem Handy") { confirmRestore = true }
    SettingsNote("Die Sicherung enthält deinen Auth-Key. Gib sie nicht weiter.")

    SettingsHeader("Import")
    SettingRow("Aus Gadgetbridge importieren", "Zip aus „Daten exportieren“: Verlauf, Schlafphasen, SpO₂, Auth-Key") { importGadgetbridge() }

    SettingsHeader("Fehlersuche")
    SettingRow("Protokoll anzeigen", "${state.log.size} Einträge der Verbindung") { showLog = true }
    SettingRow("Protokoll kopieren", "In die Zwischenablage, z. B. um es zu verschicken") {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("HelioLink-Protokoll", exportInfo(context, state) + "\n" + state.log.reversed().joinToString("\n")))
        Toast.makeText(context, "Protokoll kopiert", Toast.LENGTH_SHORT).show()
    }

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
    if (confirmRestore) {
        ConfirmDialog(
            "Sicherung wiederherstellen?",
            "Alle Daten und Einstellungen auf diesem Handy werden durch die Sicherung ersetzt. Danach verbindet sich die App neu.",
            "Datei auswählen",
            onConfirm = { openBackup() },
            onDismiss = { confirmRestore = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Alle Daten löschen?",
            "Alle gespeicherten Gesundheitsdaten und Trainings werden von diesem Handy gelöscht. Das lässt sich nicht rückgängig machen.",
            "Löschen",
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { app.db.clearAll() }
                    app.repo.dataChanged()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
