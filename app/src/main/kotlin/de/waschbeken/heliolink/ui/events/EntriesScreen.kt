package de.waschbeken.heliolink.ui.events

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.ui.BackTopBar
import de.waschbeken.heliolink.ui.German
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.dbValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Every quick entry and symptom of the last weeks, by day; time can be changed, entries deleted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntriesScreen(nav: NavHostController) {
    val app = LocalContext.current.app
    val state by app.repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    var days by rememberSaveable { mutableStateOf(14) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var retime by remember { mutableStateOf<HealthDb.Event?>(null) }
    val today = LocalDate.now(zone)
    val events by dbValue(state.dataVersion, reload, days) { db ->
        db.events(today.minusDays(days - 1L).atStartOfDay(zone).toEpochSecond(), today.plusDays(1).atStartOfDay(zone).toEpochSecond())
    }
    fun day(e: HealthDb.Event) = Instant.ofEpochSecond(e.timestamp).atZone(zone).toLocalDate()
    val clock = DateTimeFormatter.ofPattern("HH:mm")
    fun dayTitle(d: LocalDate) = when (d) {
        today -> "Heute"
        today.minusDays(1) -> "Gestern"
        else -> d.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", German))
    }

    Scaffold(topBar = { BackTopBar("Alle Einträge", onBack = { nav.popBackStack() }, subtitle = "Letzte $days Tage") }) { padding ->
        val list = events
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (list == null) {
                item { Text("Lade …") }
                return@LazyColumn
            }
            val counts = list.groupingBy { it.tag }.eachCount().entries.sortedByDescending { it.value }
            if (counts.isNotEmpty()) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Alle (${list.size})") })
                    counts.forEach { (tag, n) ->
                        FilterChip(selected = filter == tag, onClick = { filter = if (filter == tag) null else tag }, label = { Text("$tag ($n)") })
                    }
                }
            }
            val shown = list.filter { filter == null || it.tag == filter }.sortedByDescending { it.timestamp }
            if (shown.isEmpty()) item {
                Text(
                    "Keine Einträge in diesem Zeitraum. Einträge machst du auf „Heute“ unter „Schnell eintragen“ oder im Tab „Körper“.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            shown.groupBy { day(it) }.forEach { (d, dayEvents) ->
                item(key = d.toEpochDay()) {
                    Panel {
                        Text(dayTitle(d), style = MaterialTheme.typography.titleSmall)
                        dayEvents.forEach { e ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    Instant.ofEpochSecond(e.timestamp).atZone(zone).format(clock),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(52.dp).clickable { retime = e }.padding(vertical = 8.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(e.tag, style = MaterialTheme.typography.bodyMedium)
                                    if (e.note.isNotBlank()) {
                                        Text(e.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { app.db.deleteEvent(e.id) }
                                        reload++
                                        app.repo.dataChanged()
                                    }
                                }) { Icon(Icons.Default.Close, contentDescription = "Löschen") }
                            }
                        }
                    }
                }
            }
            item {
                Column {
                    Text(
                        "Tippe auf eine Uhrzeit, um sie zu ändern.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (days < 365) TextButton(onClick = { days = minOf(365, days + 30) }) { Text("Ältere Einträge laden") }
                }
            }
        }
    }

    retime?.let { e ->
        EntryTimeDialog("${e.tag}: Uhrzeit ändern", e.timestamp, onDismiss = { retime = null }, day = day(e)) { picked ->
            val ts = picked ?: System.currentTimeMillis() / 1000
            scope.launch {
                withContext(Dispatchers.IO) { app.db.setEventTime(e.id, ts) }
                reload++
                app.repo.dataChanged()
            }
        }
    }
}
