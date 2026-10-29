package de.waschbeken.heliolink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.data.Backup
import androidx.compose.ui.unit.dp
import de.waschbeken.heliolink.update.AppUpdater
import de.waschbeken.heliolink.update.UpdateState

@Composable
fun UpdateDialog(updater: AppUpdater) {
    val show by updater.showDialog.collectAsState()
    val state by updater.state.collectAsState()
    if (!show) return
    val context = LocalContext.current
    val app = context.app
    val info = when (val s = state) {
        is UpdateState.Available -> s.info
        is UpdateState.Downloading -> s.info
        is UpdateState.Ready -> s.info
        is UpdateState.KeyChanged -> s.info
        else -> null
    }
    val saveBackup = rememberSaveFile("application/zip", "Sicherung gespeichert") { uri ->
        app.repo.controller?.shutdown()
        Backup(context, app.prefs, app.db).createBackup(uri)
    }
    val saveApk = rememberSaveFile("application/vnd.android.package-archive", "Neue Version gespeichert") { uri ->
        (app.updater.state.value as? UpdateState.KeyChanged)?.let { app.updater.manager.saveApk(it.apk, uri) }
    }
    AlertDialog(
        onDismissRequest = { updater.dismiss(skipVersion = false) },
        title = { Text(if (info != null) "HelioLink ${info.version} ist da" else "Update") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val s = state) {
                    is UpdateState.Downloading -> {
                        Text("Lade herunter …")
                        if (s.progress >= 0f) {
                            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    is UpdateState.Failed -> Text(s.message)
                    is UpdateState.KeyChanged -> {
                        Text(
                            "Diese Version ist mit dem neuen, passwortgeschützten Schlüssel signiert. Android lässt sie " +
                                "deshalb einmalig nicht über die installierte App installieren. So geht's ohne Datenverlust:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "1. Sicherung speichern (Daten, Einstellungen, Auth-Key)\n" +
                                "2. Neue Version speichern, z. B. in Downloads\n" +
                                "3. HelioLink deinstallieren\n" +
                                "4. Die gespeicherte APK im Dateimanager öffnen und installieren\n" +
                                "5. In der neuen App: Einstellungen → Daten → Sicherung wiederherstellen",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Column {
                            TextButton(onClick = { saveBackup("heliolink-sicherung-${Backup.fileDate()}.zip") }) { Text("1. Sicherung speichern") }
                            TextButton(onClick = { saveApk("heliolink-${s.info.version}.apk") }) { Text("2. Neue Version speichern") }
                            TextButton(onClick = { app.updater.manager.uninstall() }) { Text("3. HelioLink deinstallieren") }
                        }
                    }
                    else -> {
                        Text(
                            "Installiert ist ${updater.currentVersion}. Deine Daten und die Verbindung zum Strap bleiben erhalten.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val notes = info?.notes.orEmpty()
                        if (notes.isNotBlank()) {
                            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                                Text(notes, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                is UpdateState.Available -> TextButton(onClick = { updater.download(s.info) }) { Text("Installieren") }
                is UpdateState.Ready -> TextButton(onClick = { updater.install(s.apk) }) { Text("Installieren") }
                is UpdateState.Downloading -> Unit
                is UpdateState.KeyChanged -> TextButton(onClick = { updater.dismiss(skipVersion = false) }) { Text("Später") }
                else -> TextButton(onClick = { updater.dismiss(skipVersion = false) }) { Text("OK") }
            }
        },
        dismissButton = {
            if (state is UpdateState.Available) {
                Column {
                    TextButton(onClick = { updater.dismiss(skipVersion = false) }) { Text("Später") }
                    TextButton(onClick = { updater.dismiss(skipVersion = true) }) { Text("Diese Version überspringen") }
                }
            }
        },
    )
}
