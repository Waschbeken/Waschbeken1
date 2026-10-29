package de.waschbeken.heliolink.ui.alarms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.waschbeken.heliolink.HelioApp
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.StrapAlarm
import de.waschbeken.heliolink.core.nextAlarm
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.ui.BackTopBar
import de.waschbeken.heliolink.ui.EmptyState
import de.waschbeken.heliolink.ui.German
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.Routes
import de.waschbeken.heliolink.ui.SectionHeader
import de.waschbeken.heliolink.ui.dbValue
import de.waschbeken.heliolink.ui.formatMinutes
import de.waschbeken.heliolink.ui.settings.ConfirmDialog
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlinx.coroutines.delay

private val days = DayOfWeek.entries

/** "Mo–Fr", "Täglich", "Sa, So", "Einmalig". */
fun repetitionText(repetition: Int): String = when (repetition) {
    0 -> "Einmalig"
    StrapAlarm.EVERY_DAY -> "Täglich"
    StrapAlarm.WEEKDAYS -> "Werktags"
    0x60 -> "Wochenende"
    else -> days.filter { repetition and (1 shl (it.value - 1)) != 0 }
        .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, German).removeSuffix(".") }
}

fun untilText(next: ZonedDateTime, now: ZonedDateTime = ZonedDateTime.now()): String {
    val minutes = Duration.between(now, next).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> "in weniger als einer Minute"
        minutes < 24 * 60 -> "in ${formatMinutes(minutes.toInt())}"
        else -> "in ${minutes / (24 * 60)} T ${(minutes % (24 * 60)) / 60} h"
    }
}

/** Saves through the running service, or locally so it is sent at the next connection. */
private fun save(app: HelioApp, alarm: StrapAlarm, label: String) {
    val c = app.repo.controller
    if (c != null) c.saveAlarm(alarm, label) else {
        app.db.saveAlarm(alarm, label, pending = true)
        app.repo.dataChanged()
    }
}

private fun delete(app: HelioApp, row: HealthDb.AlarmRow) {
    val c = app.repo.controller
    if (c != null) c.deleteAlarm(row.alarm.position) else {
        app.db.saveAlarm(row.alarm, row.label, pending = true, deleted = true)
        app.repo.dataChanged()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlarmsScreen(nav: NavHostController) {
    val app = LocalContext.current.app
    val state by app.repo.state.collectAsStateWithLifecycle()
    val tick by app.prefs.changes.collectAsStateWithLifecycle()
    val rows by dbValue(state.dataVersion) { db -> db.alarms().filter { !it.deleted } }
    val list = rows.orEmpty()
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = ZonedDateTime.now()
        }
    }
    LaunchedEffect(state.connected) { if (state.connected) app.repo.controller?.refreshAlarms() }
    val used = list.map { it.alarm.position }.toSet()
    val free = (0 until StrapAlarm.SLOTS).firstOrNull { it !in used }
    val sleepGoal = app.prefs.profile.also { tick }.sleepGoalMinutes

    Scaffold(
        topBar = {
            BackTopBar("Wecker", onBack = { nav.popBackStack() }) {
                IconButton(onClick = { app.repo.controller?.refreshAlarms() }, enabled = state.connected) {
                    Icon(Icons.Default.Refresh, contentDescription = "Vom Strap laden")
                }
            }
        },
        floatingActionButton = {
            if (free != null) {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate(Routes.alarm(-1)) },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Wecker") },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val next = nextAlarm(list.map { it.alarm }, now)
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (next != null) {
                            Text("Nächster Wecker", style = MaterialTheme.typography.labelLarge)
                            Text(next.format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 48.sp, fontWeight = FontWeight.Bold)
                            Text(
                                next.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", German)) + " · " + untilText(next, now),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val bed = next.minusMinutes(sleepGoal.toLong() + 15)
                            if (Duration.between(now, bed).toMinutes() in 0..(16 * 60)) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                    Icon(Icons.Default.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "Für ${formatMinutes(sleepGoal)} Schlaf ab ${bed.format(DateTimeFormatter.ofPattern("HH:mm"))} ins Bett",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        } else {
                            Text("Kein Wecker aktiv", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Der Strap weckt dich mit Vibration – leise, ohne Handy. Mit „Smart“ weckt er bis zu 30 Minuten früher in einer Leichtschlafphase.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            if (!state.connected) {
                item {
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Strap nicht verbunden. Änderungen werden gespeichert und beim nächsten Verbinden übertragen.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            } else if (!state.alarmsLoaded) {
                item { Text("Wecker werden vom Strap geladen …", style = MaterialTheme.typography.bodySmall) }
            }
            if (list.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Default.Alarm, "Noch keine Wecker",
                        "Lege bis zu ${StrapAlarm.SLOTS} Wecker an. Sie liegen auf dem Strap und klingeln auch ohne Handy.",
                        Modifier.padding(top = 24.dp),
                    )
                }
            } else {
                item { SectionHeader("${list.size} von ${StrapAlarm.SLOTS} Plätzen belegt") }
                items(list, key = { it.alarm.position }) { row -> AlarmRowItem(row, now, onClick = { nav.navigate(Routes.alarm(row.alarm.position)) }) { enabled ->
                    save(app, row.alarm.copy(enabled = enabled), row.label)
                } }
            }
            if (free != null) {
                item { SectionHeader("Schnell anlegen") }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Triple(6, 30, StrapAlarm.WEEKDAYS), Triple(7, 0, StrapAlarm.WEEKDAYS), Triple(9, 0, 0x60)).forEach { (h, m, rep) ->
                            OutlinedButton(onClick = { save(app, StrapAlarm(free, true, true, h, m, rep), "") }) {
                                Text("%02d:%02d %s".format(h, m, repetitionText(rep)))
                            }
                        }
                        OutlinedButton(onClick = {
                            val t = LocalTime.now().plusMinutes(sleepGoal.toLong()).plusMinutes(15)
                            save(app, StrapAlarm(free, true, true, t.hour, t.minute, 0), "Nach ${formatMinutes(sleepGoal)} Schlaf")
                        }) { Text("Jetzt + ${formatMinutes(sleepGoal)}") }
                        OutlinedButton(onClick = {
                            val t = LocalTime.now().plusMinutes(20)
                            save(app, StrapAlarm(free, true, false, t.hour, t.minute, 0), "Powernap")
                        }) { Text("Powernap 20 min") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlarmRowItem(row: HealthDb.AlarmRow, now: ZonedDateTime, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val a = row.alarm
    val dim = if (a.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("%02d:%02d".format(a.hour, a.minute), fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = dim)
                Text(
                    listOfNotNull(row.label.ifBlank { null }, repetitionText(a.repetition)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (a.smart) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Smart", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                    }
                    if (row.pending) Text("wird übertragen", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    else if (a.enabled) nextAlarm(listOf(a), now)?.let {
                        Text(untilText(it, now), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Switch(checked = a.enabled, onCheckedChange = onToggle)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlarmEditScreen(nav: NavHostController, position: Int) {
    val app = LocalContext.current.app
    val existing = remember(position) { if (position >= 0) app.db.alarms().firstOrNull { it.alarm.position == position } else null }
    val slot = remember(position) {
        if (position >= 0) position else {
            val used = app.db.alarms().map { it.alarm.position }.toSet()
            (0 until StrapAlarm.SLOTS).firstOrNull { it !in used }
        }
    }
    val time = rememberTimePickerState(existing?.alarm?.hour ?: 7, existing?.alarm?.minute ?: 0, is24Hour = true)
    var repetition by remember { mutableIntStateOf(existing?.alarm?.repetition ?: StrapAlarm.WEEKDAYS) }
    var smart by remember { mutableStateOf(existing?.alarm?.smart ?: true) }
    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    var keyboard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(topBar = { BackTopBar(if (existing == null) "Neuer Wecker" else "Wecker bearbeiten", onBack = { nav.popBackStack() }) }) { padding ->
        if (slot == null) {
            EmptyState(Icons.Default.Alarm, "Alle Plätze belegt", "Der Strap hat ${StrapAlarm.SLOTS} Weckerplätze. Lösche zuerst einen Wecker.", Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                if (keyboard) TimeInput(time) else TimePicker(time)
                TextButton(onClick = { keyboard = !keyboard }) { Text(if (keyboard) "Uhr anzeigen" else "Zeit eintippen") }
            }
            item {
                Panel {
                    Text("Wiederholen", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        days.forEach { d ->
                            val bit = 1 shl (d.value - 1)
                            FilterChip(
                                selected = repetition and bit != 0,
                                onClick = { repetition = repetition xor bit },
                                label = { Text(d.getDisplayName(TextStyle.SHORT, German).removeSuffix(".").take(2)) },
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Einmalig" to 0, "Werktags" to StrapAlarm.WEEKDAYS, "Wochenende" to 0x60, "Täglich" to StrapAlarm.EVERY_DAY).forEach { (name, value) ->
                            FilterChip(selected = repetition == value, onClick = { repetition = value }, label = { Text(name) })
                        }
                    }
                }
            }
            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Smart Wake", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Weckt bis zu 30 Minuten früher, sobald du leicht schläfst – du wachst erholter auf.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = smart, onCheckedChange = { smart = it })
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = label, onValueChange = { label = it.take(30) },
                    label = { Text("Name (nur in der App)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                val preview = nextAlarm(listOf(StrapAlarm(slot, true, smart, time.hour, time.minute, repetition)))
                if (preview != null) Text("Klingelt ${untilText(preview)}", style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Button(onClick = {
                    save(app, StrapAlarm(slot, true, smart, time.hour, time.minute, repetition), label.trim())
                    nav.popBackStack()
                }, modifier = Modifier.fillMaxWidth()) { Text("Speichern") }
                TextButton(
                    onClick = { app.repo.controller?.findStrap() },
                    enabled = app.repo.state.value.connected,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Vibration testen") }
                if (existing != null) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Wecker löschen", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (confirmDelete && existing != null) {
        ConfirmDialog(
            "Wecker löschen?", "Der Wecker wird auch vom Strap entfernt.", "Löschen",
            onConfirm = {
                delete(app, existing)
                nav.popBackStack()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
