package de.waschbeken.heliolink.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.data.AutoBackup
import de.waschbeken.heliolink.ui.AppLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Datenschutz": what leaves the phone, app lock and encrypted backups. */
@Composable
internal fun PrivacyPage() {
    val context = LocalContext.current
    val prefs = context.app.prefs
    prefsTick()

    SettingsHeader("Was verlässt dein Handy?")
    SettingsNote(
        "Gesundheitsdaten: nie. Kein Konto, keine Cloud, keine Werbung, keine Analyse – Puls, Schlaf und alles " +
            "andere liegen nur in HelioLink auf diesem Handy.",
    )
    SettingsNote("Android-Cloud-Sicherung (Google, Xiaomi …): für HelioLink ausgeschaltet. Nur eine direkte Übertragung von Handy zu Handy nimmt die Daten mit.")
    SettingsNote(
        "Einzige Verbindung ins Internet: die Suche nach Updates bei GitHub. Dabei wird nur die Liste der Versionen " +
            "abgerufen und beim Update die neue App geladen – GitHub sieht deine IP-Adresse und die Uhrzeit, sonst nichts.",
    )
    SwitchSetting(
        "Automatisch nach Updates suchen",
        if (prefs.autoCheckUpdates) "An – alle paar Stunden" else "Aus – HelioLink geht nur online, wenn du „Nach Updates suchen“ tippst",
        prefs.autoCheckUpdates,
    ) { prefs.autoCheckUpdates = it }
    val log = prefs.networkLog
    SettingRow(
        "Letzte Verbindungen",
        if (log.isEmpty()) "Noch keine" else log.take(5).joinToString("\n") { (t, what) -> formatDate(t) + " · " + what },
    )

    SettingsHeader("App-Sperre")
    val available = AppLock.available(context)
    SwitchSetting(
        "Mit Fingerabdruck oder PIN sperren",
        if (available) "Beim Öffnen fragt Android nach Fingerabdruck, Gesicht oder der PIN deines Handys"
        else "Richte zuerst eine Displaysperre (PIN, Muster oder Fingerabdruck) im Handy ein",
        prefs.appLock && available,
        enabled = available,
    ) { prefs.appLock = it }
    ChoiceSetting(
        "Wieder sperren",
        listOf(0 to "Sofort", 60 to "Nach 1 Minute", 300 to "Nach 5 Minuten", 1800 to "Nach 30 Minuten"),
        prefs.appLockDelay,
        subtitle = "wenn die App so lange im Hintergrund war",
        enabled = prefs.appLock,
    ) { prefs.appLockDelay = it }
    SettingsNote(
        "Mit Sperre bleibt die Vorschau in der Liste der offenen Apps leer. Widget und Benachrichtigung zeigen weiter " +
            "Puls und Schritte: den Puls in der Benachrichtigung schaltest du unter Benachrichtigungen aus, das Widget " +
            "entfernst du auf dem Startbildschirm.",
    )

    SettingsHeader("Sicherungen")
    BackupEncryptionSetting()
}

/** Turns password encryption of all backups on, changes the password or turns it off. */
@Composable
internal fun BackupEncryptionSetting() {
    val context = LocalContext.current
    val app = context.app
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    var setPassword by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    val on = prefs.backupPassword != null
    SettingRow(
        "Sicherungen verschlüsseln",
        if (on) "An – nur mit deinem Passwort lesbar · tippen zum Ändern"
        else "Aus – wer die Datei hat, kann deine Daten und den Auth-Key lesen · tippen zum Einschalten",
    ) { if (on) options = true else setPassword = true }

    if (setPassword) {
        NewPasswordDialog(
            changing = on,
            offerCleanup = prefs.autoBackupDays > 0 || prefs.lastAutoBackup > 0,
            onDismiss = { setPassword = false },
        ) { password, cleanup ->
            setPassword = false
            prefs.backupPassword = password
            if (prefs.autoBackupDays > 0 || prefs.lastAutoBackup > 0) {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            AutoBackup.run(context)
                            if (cleanup) AutoBackup.prune(context, 1)
                        }
                    }
                    Toast.makeText(
                        context,
                        result.fold(
                            { "Verschlüsselt gesichert" + if (cleanup) ", alte Sicherungen gelöscht" else "" },
                            { "Passwort gespeichert, Sicherung fehlgeschlagen: ${it.message}" },
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } else {
                Toast.makeText(context, "Sicherungen werden ab jetzt verschlüsselt", Toast.LENGTH_SHORT).show()
            }
        }
    }
    if (options) {
        AlertDialog(
            onDismissRequest = { options = false },
            title = { Text("Verschlüsselte Sicherungen") },
            text = { Text("Neue Sicherungen werden mit deinem Passwort verschlüsselt. Ältere Sicherungen brauchen das Passwort, mit dem sie erstellt wurden.") },
            confirmButton = {
                TextButton(onClick = {
                    options = false
                    setPassword = true
                }) { Text("Passwort ändern") }
            },
            dismissButton = {
                TextButton(onClick = {
                    options = false
                    confirmOff = true
                }) { Text("Ausschalten") }
            },
        )
    }
    if (confirmOff) {
        ConfirmDialog(
            "Verschlüsselung ausschalten?",
            "Neue Sicherungen kann dann jeder lesen, der die Datei hat – auch andere Apps mit Dateizugriff. Sie enthalten deinen Auth-Key.",
            "Ausschalten",
            onConfirm = { prefs.backupPassword = null },
            onDismiss = { confirmOff = false },
        )
    }
}

@Composable
private fun NewPasswordDialog(changing: Boolean, offerCleanup: Boolean, onDismiss: () -> Unit, onConfirm: (String, Boolean) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var cleanup by remember { mutableStateOf(true) }
    val error = when {
        first.isNotEmpty() && first.length < 6 -> "Mindestens 6 Zeichen"
        second.isNotEmpty() && second != first -> "Die Passwörter sind verschieden"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (changing) "Neues Passwort" else "Sicherungen verschlüsseln") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Alle Sicherungen, auch die automatischen, werden mit diesem Passwort verschlüsselt. Ohne das Passwort " +
                        "lassen sie sich nicht wiederherstellen – von niemandem. Schreib es dir auf.",
                    style = MaterialTheme.typography.bodySmall,
                )
                PasswordField(first, { first = it }, "Passwort")
                PasswordField(second, { second = it }, "Passwort wiederholen", error)
                if (offerCleanup) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = cleanup, onCheckedChange = { cleanup = it })
                        Text("Alte automatische Sicherungen löschen", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(first, cleanup && offerCleanup) }, enabled = error == null && first.length >= 6 && first == second) {
                Text("Speichern")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Asks for the password of an encrypted backup before restoring it. */
@Composable
internal fun BackupPasswordDialog(error: String?, busy: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Sicherung ist verschlüsselt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Gib das Passwort ein, mit dem die Sicherung erstellt wurde.", style = MaterialTheme.typography.bodySmall)
                PasswordField(password, { password = it }, "Passwort", error)
                if (busy) Text("Prüfe und stelle wieder her …", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty() && !busy) { Text("Wiederherstellen") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Abbrechen") } },
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, error: String? = null) {
    var reveal by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = if (error != null) { { Text(error) } } else null,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { reveal = !reveal }) {
                Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (reveal) "Verbergen" else "Anzeigen")
            }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    )
}
