package de.waschbeken.heliolink.ui.events

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.EntryEffects
import de.waschbeken.heliolink.data.Prefs
import de.waschbeken.heliolink.ui.CollapsibleHeader
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.dbValue
import de.waschbeken.heliolink.ui.rememberSections
import de.waschbeken.heliolink.ui.settings.prefsTick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun clock(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

/** Chips to pick several entries. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPicker(options: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { tag ->
            FilterChip(selected = tag in selected, onClick = { onToggle(tag) }, label = { Text(tag) })
        }
    }
}

/**
 * A [TagPicker] under a header that opens and closes (remembered under [key]). When closed, the header
 * shows what is selected, so the choice stays visible without the chips taking up room.
 */
@Composable
fun CollapsibleTagPicker(
    key: String,
    title: String,
    options: List<String>,
    selected: Set<String>,
    hint: String? = null,
    onToggle: (String) -> Unit,
) {
    val sections = rememberSections()
    val open = sections.isOpen(key)
    val chosen = options.filter { it in selected }
    CollapsibleHeader(
        title, open, count = chosen.size.takeIf { it > 0 },
        summary = chosen.joinToString(", ").ifEmpty { "Nichts ausgewählt – tippen zum Auswählen" },
    ) { sections.toggle(key) }
    if (open) {
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TagPicker(options, selected, onToggle)
    }
}

/**
 * "Schnell eintragen": one tap notes caffeine, nicotine, food … at this moment. The entries show up in
 * the day charts and count for the following night in the sleep evaluation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickEntryCard(dataVersion: Int, helpers: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    prefsTick()
    var reload by remember { mutableIntStateOf(0) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val events by dbValue(dataVersion, reload, today) {
        it.events(today.atStartOfDay(zone).toEpochSecond(), today.plusDays(1).atStartOfDay(zone).toEpochSecond())
    }
    var custom by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val sections = rememberSections()

    fun add(tag: String, note: String = "") {
        scope.launch {
            val now = System.currentTimeMillis() / 1000
            withContext(Dispatchers.IO) { app.db.addEvent(now, tag, note) }
            reload++
            Toast.makeText(context, "$tag um ${clock(now)} eingetragen", Toast.LENGTH_SHORT).show()
        }
    }

    val buttonsOpen = sections.isOpen("home-quick", default = true)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clickable { sections.toggle("home-quick", default = true) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Schnell eintragen", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(
                    if (buttonsOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (buttonsOpen) "Zuklappen" else "Aufklappen",
                )
            }
            if (buttonsOpen) TextButton(onClick = { editing = true }) { Text("Knöpfe") }
        }
        if (buttonsOpen) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            app.prefs.quickTags.forEach { tag -> AssistChip(onClick = { add(tag) }, label = { Text(tag) }) }
            AssistChip(
                onClick = { custom = true },
                label = { Text("Anderes") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        if (buttonsOpen) {
            helpers()
            de.waschbeken.heliolink.ui.home.SubstancesSection(dataVersion + reload)
        }
        val list = events.orEmpty()
        if (buttonsOpen && list.isNotEmpty()) {
            val open = sections.isOpen("home-events")
            CollapsibleHeader(
                "Heute eingetragen", open, count = list.size,
                summary = list.takeLast(4).reversed().joinToString(", ") { "${it.tag} ${clock(it.timestamp)}" },
            ) { sections.toggle("home-events") }
            if (open) list.reversed().forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(clock(e.timestamp), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(52.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.tag, style = MaterialTheme.typography.bodyMedium)
                        if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { app.db.deleteEvent(e.id) }
                            reload++
                        }
                    }) { Icon(Icons.Default.Close, contentDescription = "Löschen") }
                }
            }
        }

        if (buttonsOpen) {
            val effectsOpen = sections.isOpen("home-effects")
            CollapsibleHeader(
                "Wirkung auf den Puls", effectsOpen,
                summary = "Was Koffein, Nikotin & Co. mit deinem Ruhepuls machen",
            ) { sections.toggle("home-effects") }
            if (effectsOpen) EntryEffectsList(dataVersion)
        }
    }

    if (custom) CustomEntryDialog(onDismiss = { custom = false }) { tag, note, keep ->
        if (keep) app.prefs.quickTags = app.prefs.quickTags + tag
        add(tag, note)
    }
    if (editing) EditButtonsDialog(app.prefs, onDismiss = { editing = false })
}

@Composable
private fun CustomEntryDialog(onDismiss: () -> Unit, onAdd: (String, String, Boolean) -> Unit) {
    var tag by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var keep by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Eintragen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(tag, { tag = it.take(30) }, label = { Text("Was? (z. B. Energy-Drink)") }, singleLine = true)
                OutlinedTextField(note, { note = it.take(200) }, label = { Text("Notiz (optional)") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = keep, onCheckedChange = { keep = it })
                    Text("Als Knopf behalten")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(tag.trim(), note.trim(), keep); onDismiss() }, enabled = tag.isNotBlank()) { Text("Eintragen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditButtonsDialog(prefs: Prefs, onDismiss: () -> Unit) {
    var tags by remember { mutableStateOf(prefs.quickTags) }
    var new by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Knöpfe bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tippe auf ein ✕, um einen Knopf zu entfernen.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { t ->
                        InputChip(
                            selected = false,
                            onClick = { tags = tags - t },
                            label = { Text(t) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Entfernen", modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(new, { new = it.take(30) }, label = { Text("Neuer Knopf") }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(onClick = { if (new.isNotBlank()) { tags = tags + new.trim(); new = "" } }) { Icon(Icons.Default.Add, contentDescription = "Hinzufügen") }
                }
                TextButton(onClick = { tags = Prefs.DEFAULT_QUICK_TAGS }) { Text("Standard wiederherstellen") }
            }
        },
        confirmButton = { TextButton(onClick = { prefs.quickTags = tags; onDismiss() }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/**
 * The resting heart rate in the two hours after each kind of quick entry, compared with the same
 * time on days without it (see [EntryEffects]). Loads the last 60 days when shown.
 */
@Composable
fun EntryEffectsList(dataVersion: Int) {
    val effects by dbValue(dataVersion) { db ->
        val now = System.currentTimeMillis() / 1000
        val from = now - 60 * 86_400L
        val events = db.events(from, now).map { it.timestamp to it.tag }
        if (events.isEmpty()) emptyList() else EntryEffects.compute(events, db.activity(from - 14 * 86_400L, now))
    }
    val list = effects
    when {
        list == null -> Text("Rechne …", style = MaterialTheme.typography.bodySmall)
        list.isEmpty() -> Text(
            "Noch zu wenig Einträge. Ab drei Einträgen derselben Sache (z. B. 3× Koffein) mit Pulswerten danach erscheint hier, " +
                "wie sich dein Ruhepuls verändert.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { e ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.tag, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            (if (e.firstHour >= 0) "+" else "−") + "%.0f bpm".format(kotlin.math.abs(e.firstHour)),
                            style = MaterialTheme.typography.titleSmall,
                            color = when {
                                e.firstHour >= 4 -> MaterialTheme.colorScheme.error
                                e.firstHour <= -3 -> Color(0xFF30A46C)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Text(
                        EntryEffects.describe(e) + " · ${e.count}× verglichen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "Verglichen wird mit derselben Uhrzeit an Tagen ohne den Eintrag; nur ruhige Minuten zählen (Strap getragen, wach, kaum Schritte).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What you are doing or took during a recording, plus a note; saved right away. */
@Composable
fun RecordingNotes(workoutId: Long, initialTags: List<String>, initialNote: String) {
    val app = LocalContext.current.app
    var tags by remember(workoutId) { mutableStateOf(initialTags.toSet()) }
    var note by remember(workoutId) { mutableStateOf(initialNote) }
    var dirty by remember { mutableIntStateOf(0) }
    LaunchedEffect(dirty) {
        if (dirty == 0) return@LaunchedEffect
        delay(400)
        withContext(Dispatchers.IO) { app.db.setWorkoutNote(workoutId, tags.toList(), note) }
        app.repo.dataChanged()
    }
    val taken = (app.prefs.quickTags + tags.filter { it !in Prefs.ACTIVITY_TAGS && it !in app.prefs.quickTags }).distinct()
    val toggle: (String) -> Unit = { t -> tags = if (t in tags) tags - t else tags + t; dirty++ }
    Panel {
        CollapsibleTagPicker("rec-activity", "Was machst du gerade?", Prefs.ACTIVITY_TAGS, tags, onToggle = toggle)
        CollapsibleTagPicker("rec-taken", "Hast du etwas genommen oder gegessen?", taken, tags, onToggle = toggle)
        OutlinedTextField(
            note, { note = it.take(300); dirty++ },
            label = { Text("Notiz (z. B. „nach dem Treppensteigen“)") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Short text of a recording's entries for lists. */
fun notesText(tags: List<String>, note: String): String? =
    (tags + listOfNotNull(note.takeIf { it.isNotBlank() })).takeIf { it.isNotEmpty() }?.joinToString(" · ")
