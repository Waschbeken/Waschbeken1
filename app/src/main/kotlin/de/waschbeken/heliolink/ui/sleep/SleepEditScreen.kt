package de.waschbeken.heliolink.ui.sleep

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.ActivitySample
import de.waschbeken.heliolink.core.SleepEdit
import de.waschbeken.heliolink.core.SleepEditor
import de.waschbeken.heliolink.core.SleepSession
import de.waschbeken.heliolink.core.SleepStage
import de.waschbeken.heliolink.core.StageRange
import de.waschbeken.heliolink.data.Queries
import de.waschbeken.heliolink.ui.BackTopBar
import de.waschbeken.heliolink.ui.German
import de.waschbeken.heliolink.ui.Hypnogram
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.SectionHeader
import de.waschbeken.heliolink.ui.dbValue
import de.waschbeken.heliolink.ui.formatMinutes
import de.waschbeken.heliolink.ui.settings.ConfirmDialog
import de.waschbeken.heliolink.ui.stageName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private class EditBase(val detected: SleepSession?, val existing: SleepEdit?, val samples: List<ActivitySample>)

private val stagesInOrder = listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)

/** Time of day on the night ending on [date]: from 18:00 on it belongs to the evening before. */
private fun atNight(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): Long =
    (if (hour >= 18) date.minusDays(1) else date).atTime(hour, minute).atZone(zone).toEpochSecond()

private fun clockOf(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepEditScreen(nav: NavHostController, day: Long) {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val date = LocalDate.ofEpochDay(day)
    val base by dbValue(day) { db ->
        val (from, to) = Queries.nightWindow(date, zone)
        EditBase(Queries.night(db, date, zone, applyEdits = false), db.sleepEdits(day, day)[day], db.activity(from, to))
    }
    var start by remember { mutableStateOf(0L) }
    var end by remember { mutableStateOf(0L) }
    val ranges = remember { mutableStateListOf<StageRange>() }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(base) {
        val b = base ?: return@LaunchedEffect
        if (ready) return@LaunchedEffect
        val e = b.existing?.takeIf { !it.deleted }
        start = e?.start ?: b.detected?.start ?: atNight(date, 23, 0, zone)
        end = e?.end ?: b.detected?.end ?: atNight(date, 7, 0, zone)
        ranges.clear()
        e?.ranges?.let { ranges.addAll(it) }
        ready = true
    }
    var editStart by remember { mutableStateOf(false) }
    var editEnd by remember { mutableStateOf(false) }
    var segment by remember { mutableStateOf<StageRange?>(null) }
    var addRange by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val b = base
    val tuning = app.prefs.sleepTuning
    val preview = if (b != null && ready && end > start) {
        SleepEditor.apply(b.detected?.let { SleepEditor.ignoreShortAwake(it, tuning.ignoreAwakeMinutes) }, SleepEdit(day, start, end, ranges.toList()), b.samples)
    } else null

    fun save(edit: SleepEdit?) {
        scope.launch {
            withContext(Dispatchers.IO) {
                if (edit == null) app.db.deleteSleepEdit(day) else app.db.saveSleepEdit(edit)
            }
            app.prefs.sleepCheckedDay = day
            app.repo.dataChanged()
            nav.popBackStack()
        }
    }

    Scaffold(
        topBar = {
            BackTopBar(
                "Schlaf bearbeiten",
                onBack = { nav.popBackStack() },
                subtitle = "Nacht zum " + date.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", German)),
                actions = {
                    if (ranges.isNotEmpty()) IconButton(onClick = { ranges.removeAt(ranges.lastIndex) }) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Letzte Änderung zurücknehmen")
                    }
                },
            )
        },
    ) { padding ->
        if (b == null || !ready) {
            Text("Lade …", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    when {
                        b.existing?.deleted == true -> "Diese Nacht hast du als „kein Schlaf“ markiert. Speichern trägt sie wieder ein."
                        b.existing != null -> "Du hast diese Nacht schon korrigiert. Änderungen ersetzen die alte Korrektur."
                        b.detected == null -> "Für diese Nacht wurde kein Schlaf erkannt. Trag ihn hier selbst ein."
                        b.detected.estimated -> "Die Phasen dieser Nacht sind geschätzt. Korrigiere, was nicht stimmt."
                        else -> "Stimmt etwas nicht? Passe Zeiten und Phasen an – Strap- und Gadgetbridge-Daten bleiben unverändert gespeichert."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Panel {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeBox("Eingeschlafen", clockOf(start), Modifier.weight(1f)) { editStart = true }
                        TimeBox("Aufgewacht", clockOf(end), Modifier.weight(1f)) { editEnd = true }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Nudge(Modifier.weight(1f)) { start += it * 60L }
                        Nudge(Modifier.weight(1f)) { end += it * 60L }
                    }
                    if (end <= start) Text("Aufwachen muss nach dem Einschlafen liegen.", color = MaterialTheme.colorScheme.error)
                    b.detected?.let { d ->
                        if (d.start / 60 != start / 60 || d.end / 60 != end / 60) {
                            Text("Erkannt: ${clockOf(d.start)} – ${clockOf(d.end)}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            if (preview != null) {
                item {
                    Panel {
                        Text("Vorschau", style = MaterialTheme.typography.titleMedium)
                        Hypnogram(preview.stages)
                        Text(
                            "${formatMinutes(preview.asleepMinutes)} geschlafen · Tief ${formatMinutes(preview.minutes(SleepStage.DEEP))} · " +
                                "REM ${formatMinutes(preview.minutes(SleepStage.REM))} · wach ${formatMinutes(preview.minutes(SleepStage.AWAKE))} · Score ${preview.score}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item {
                    SectionHeader("Phasen korrigieren") {
                        TextButton(onClick = { addRange = true }) { Text("Zeitraum festlegen") }
                    }
                }
                item { Text("Tippe auf einen Abschnitt, um seine Phase zu ändern.", style = MaterialTheme.typography.bodySmall) }
                items(SleepEditor.segments(preview), key = { it.from }) { s ->
                    Panel(onClick = { segment = s }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(14.dp).background(Palette.stage(s.stage), CircleShape))
                            Spacer(Modifier.width(12.dp))
                            Text("${clockOf(s.from)} – ${clockOf(s.to)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("${stageName(s.stage)} · ${formatMinutes(((s.to - s.from) / 60).toInt())}", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(
                        onClick = { save(SleepEdit(day, start, end, ranges.toList())) },
                        enabled = end > start,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Speichern") }
                    if (b.existing != null) {
                        OutlinedButton(onClick = { save(null) }, modifier = Modifier.fillMaxWidth()) { Text("Korrektur entfernen (Original zeigen)") }
                    }
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Das war kein Schlaf", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (editStart) TimeDialog("Eingeschlafen", start, onDismiss = { editStart = false }) { h, m -> start = atNight(date, h, m, zone) }
    if (editEnd) TimeDialog("Aufgewacht", end, onDismiss = { editEnd = false }) { h, m -> end = atNight(date, h, m, zone) }
    segment?.let { s ->
        AlertDialog(
            onDismissRequest = { segment = null },
            title = { Text("${clockOf(s.from)} – ${clockOf(s.to)}") },
            text = {
                Column {
                    Text("Was war in dieser Zeit?", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        stagesInOrder.forEach { st ->
                            FilterChip(
                                selected = st == s.stage,
                                onClick = {
                                    if (st != s.stage) ranges.add(s.copy(stage = st))
                                    segment = null
                                },
                                label = { Text(stageName(st)) },
                                leadingIcon = { Box(Modifier.size(10.dp).background(Palette.stage(st), CircleShape)) },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { segment = null }) { Text("Abbrechen") } },
        )
    }
    if (addRange) RangeDialog(start, end, onDismiss = { addRange = false }) { from, to, stage ->
        val f = atNight(date, from.first, from.second, zone)
        val t = atNight(date, to.first, to.second, zone)
        if (t > f) ranges.add(StageRange(f, t, stage))
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Kein Schlaf?",
            "Diese Nacht wird nicht mehr als Schlaf gezählt (z. B. wenn du nur still gelegen hast). Du kannst das später zurücknehmen.",
            "Als kein Schlaf markieren",
            onConfirm = { save(SleepEdit(day, start, end, deleted = true)) },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Small buttons that move a time by a few minutes. */
@Composable
private fun Nudge(modifier: Modifier, onMove: (Int) -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(-15, -5, 5, 15).forEach { m ->
            Text(
                if (m > 0) "+$m" else "−${-m}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onMove(m) }.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun TimeBox(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, epoch: Long, onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    val t = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault())
    val state = rememberTimePickerState(t.hour, t.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state) },
        confirmButton = {
            TextButton(onClick = {
                onPick(state.hour, state.minute)
                onDismiss()
            }) { Text("Übernehmen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RangeDialog(start: Long, end: Long, onDismiss: () -> Unit, onAdd: (Pair<Int, Int>, Pair<Int, Int>, SleepStage) -> Unit) {
    val zone = ZoneId.systemDefault()
    val s = Instant.ofEpochSecond(start).atZone(zone)
    val e = Instant.ofEpochSecond(minOf(end, start + 3600)).atZone(zone)
    val from = rememberTimePickerState(s.hour, s.minute, is24Hour = true)
    val to = rememberTimePickerState(e.hour, e.minute, is24Hour = true)
    var stage by remember { mutableStateOf(SleepStage.AWAKE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zeitraum festlegen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Von", style = MaterialTheme.typography.labelLarge)
                TimeInput(from)
                Text("Bis", style = MaterialTheme.typography.labelLarge)
                TimeInput(to)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    stagesInOrder.forEach { st ->
                        FilterChip(selected = stage == st, onClick = { stage = st }, label = { Text(stageName(st)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onAdd(from.hour to from.minute, to.hour to to.minute, stage)
                onDismiss()
            }) { Text("Übernehmen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
