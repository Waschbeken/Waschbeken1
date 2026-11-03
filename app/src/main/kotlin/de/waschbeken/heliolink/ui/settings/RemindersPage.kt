package de.waschbeken.heliolink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.Reminder
import de.waschbeken.heliolink.service.ReminderScheduler
import de.waschbeken.heliolink.service.StrapUiState

/** "Erinnerungen": the strap vibrates at set times, e.g. for pills, water or a break. */
@Composable
internal fun RemindersPage(state: StrapUiState) {
    val context = LocalContext.current
    val app = context.app
    val prefs = app.prefs
    prefsTick()
    val list = prefs.reminders
    var editing by remember { mutableStateOf<Reminder?>(null) }

    fun save(newList: List<Reminder>) {
        prefs.reminders = newList.sortedWith(compareBy({ it.hour }, { it.minute }))
        ReminderScheduler.schedule(context)
    }

    // Without notifications the text of a reminder cannot show (the strap still vibrates).
    val notificationsOn = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    if (!notificationsOn) {
        SettingsHeader("Benachrichtigungen sind aus")
        SettingRow("Für HelioLink erlauben", "Sonst vibriert der Strap nur, ohne dass du den Text siehst – tippen") {
            try {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: android.content.ActivityNotFoundException) {
            }
        }
    }
    SettingsHeader("Erinnerungen am Arm")
    SettingsNote(
        "Zur eingestellten Zeit vibriert der Strap und eine Benachrichtigung zeigt den Text – z. B. für Tabletten, " +
            "Trinken oder eine Pause. Vibrieren kann der Strap nur, wenn HelioLink verbunden ist; sonst kommt nur die Benachrichtigung.",
    )
    if (list.isEmpty()) SettingsNote("Noch keine Erinnerung – unten eine anlegen.")
    list.forEach { r ->
        SwitchSetting(r.text.ifBlank { "Erinnerung" }, r.schedule + " · ${r.pulses}× vibrieren", r.enabled) { on ->
            save(list.map { if (it.id == r.id) it.copy(enabled = on) else it })
        }
        SettingRow("Bearbeiten", null) { editing = r }
    }
    SettingRow("Neue Erinnerung", "Uhrzeit, Tage und Text wählen") {
        editing = Reminder(id = (list.maxOfOrNull { it.id } ?: 0) + 1, text = "", hour = 8, minute = 0)
    }
    SettingRow("Testen", "Strap jetzt 3× vibrieren lassen", enabled = state.connected) { app.repo.controller?.buzz(3, 450) }

    editing?.let { r ->
        ReminderDialog(
            r,
            isNew = list.none { it.id == r.id },
            onDismiss = { editing = null },
            onDelete = { save(list.filter { it.id != r.id }) },
        ) { changed -> save(list.filter { it.id != changed.id } + changed) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReminderDialog(r: Reminder, isNew: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (Reminder) -> Unit) {
    var text by remember { mutableStateOf(r.text) }
    var days by remember { mutableIntStateOf(if (r.days == 0) 0x7f else r.days) }
    var pulses by remember { mutableIntStateOf(r.pulses) }
    var repeat by remember { mutableIntStateOf(r.repeatMinutes) }
    val time = rememberTimePickerState(initialHour = r.hour, initialMinute = r.minute, is24Hour = true)
    val until = rememberTimePickerState(
        initialHour = if (r.repeatMinutes > 0) r.endHour else minOf(23, r.hour + 9),
        initialMinute = if (r.repeatMinutes > 0) r.endMinute else r.minute,
        is24Hour = true,
    )
    val names = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Neue Erinnerung" else "Erinnerung bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(text, { text = it.take(60) }, label = { Text("Woran erinnern?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Tabletten", "Trinken", "Pause", "Bewegen", "Augen entspannen").forEach { t ->
                        AssistChip(onClick = {
                            text = t
                            // Water and breaks are usually repeated over the day.
                            if (isNew && repeat == 0 && t in setOf("Trinken", "Bewegen", "Augen entspannen")) repeat = if (t == "Trinken") 120 else 60
                        }, label = { Text(t) })
                    }
                }
                Text(if (repeat > 0) "Ab" else "Uhrzeit", style = MaterialTheme.typography.titleSmall)
                TimeInput(state = time)
                Text("Wiederholen", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Nein", 30 to "alle 30 min", 60 to "jede Stunde", 120 to "alle 2 h", 180 to "alle 3 h").forEach { (m, label) ->
                        FilterChip(selected = repeat == m, onClick = { repeat = m }, label = { Text(label) })
                    }
                }
                if (repeat > 0) {
                    Text("Bis", style = MaterialTheme.typography.titleSmall)
                    TimeInput(state = until)
                }
                Text("Tage", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    names.forEachIndexed { i, name ->
                        val bit = 1 shl i
                        FilterChip(selected = days and bit != 0, onClick = { days = days xor bit }, label = { Text(name) })
                    }
                }
                Row3(days) { days = it }
                Text("Vibration", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { n -> FilterChip(selected = pulses == n, onClick = { pulses = n }, label = { Text("${n}×") }) }
                }
                if (!isNew) TextButton(onClick = { onDelete(); onDismiss() }) { Text("Erinnerung löschen") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = days != 0,
                onClick = {
                    onSave(
                        r.copy(
                            text = text.trim().ifBlank { "Erinnerung" },
                            hour = time.hour,
                            minute = time.minute,
                            days = if (days == 0x7f) 0 else days,
                            pulses = pulses,
                            repeatMinutes = repeat,
                            endHour = if (repeat > 0) until.hour else time.hour,
                            endMinute = if (repeat > 0) until.minute else time.minute,
                        ),
                    )
                    onDismiss()
                },
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Quick choices for the days. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Row3(days: Int, onChange: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip(onClick = { onChange(0x7f) }, label = { Text("Täglich") })
        AssistChip(onClick = { onChange(0x1f) }, label = { Text("Mo–Fr") })
        AssistChip(onClick = { onChange(0x60) }, label = { Text("Wochenende") })
    }
    if (days == 0) Text("Mindestens einen Tag wählen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}
