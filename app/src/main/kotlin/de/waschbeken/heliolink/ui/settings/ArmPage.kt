package de.waschbeken.heliolink.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import de.waschbeken.heliolink.service.ArmNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** "Am Arm vibrieren": calls and messages of chosen apps let the strap vibrate. */
@Composable
internal fun ArmPage() {
    val context = LocalContext.current
    val app = context.app
    val prefs = app.prefs
    prefsTick()
    // The access can be granted in the system settings at any time: check again while visible.
    var access by remember { mutableStateOf(ArmNotificationListener.hasAccess(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                access = ArmNotificationListener.hasAccess(context)
                delay(1_500)
            }
        }
    }
    var pickApps by remember { mutableStateOf(false) }

    SettingsHeader("Am Arm vibrieren")
    SettingRow(
        "Zugriff auf Benachrichtigungen",
        if (access) "Erteilt" else "Fehlt – tippen und HelioLink erlauben",
    ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    SwitchSetting(
        "Strap vibrieren lassen",
        if (access) "Bei Anrufen und Nachrichten der gewählten Apps" else "Braucht zuerst den Zugriff oben",
        prefs.armNotify && access,
        enabled = access,
    ) { prefs.armNotify = it }
    SwitchSetting("Anrufe", "3× lang", prefs.armCalls, enabled = prefs.armNotify && access) { prefs.armCalls = it }
    SettingRow(
        "Apps für Nachrichten",
        if (prefs.armApps.isEmpty()) "Keine – tippen zum Auswählen" else "${prefs.armApps.size} gewählt · 2× kurz",
        enabled = prefs.armNotify && access,
    ) { pickApps = true }
    SettingsNote(
        "HelioLink sieht nur, welche App eine Benachrichtigung schickt – nicht den Inhalt – und speichert nichts. " +
            "Im Schlaf und bei „Bitte nicht stören“ bleibt der Strap still. Eine Unterhaltung vibriert höchstens einmal pro Minute.",
    )
    SettingRow("Testen", "Strap jetzt 2× vibrieren lassen", enabled = app.repo.controller != null) { app.repo.controller?.buzz(2, 300) }

    if (pickApps) AppPickerDialog(prefs.armApps, onDismiss = { pickApps = false }) { prefs.armApps = it }
}

private class AppEntry(val packageName: String, val label: String)

@Composable
private fun AppPickerDialog(selected: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    val context = LocalContext.current
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName }
                .sortedWith(compareBy({ it.packageName !in selected }, { it.label.lowercase() }))
        }
    }
    var chosen by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apps für Nachrichten") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Suchen") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val list = apps
                if (list == null) {
                    Text("Lade Apps …", modifier = Modifier.padding(top = 12.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                        items(list.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }, key = { it.packageName }) { a ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    chosen = if (a.packageName in chosen) chosen - a.packageName else chosen + a.packageName
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = a.packageName in chosen, onCheckedChange = null)
                                Text(a.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(chosen)
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
