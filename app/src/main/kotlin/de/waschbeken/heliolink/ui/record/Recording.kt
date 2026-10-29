package de.waschbeken.heliolink.ui.record

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.HeartRateZones
import de.waschbeken.heliolink.service.LiveWorkout
import de.waschbeken.heliolink.service.RECORDING_SPORT
import de.waschbeken.heliolink.ui.BackTopBar
import de.waschbeken.heliolink.ui.LineChart
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.ProgressRing
import de.waschbeken.heliolink.ui.Routes
import de.waschbeken.heliolink.ui.Series
import de.waschbeken.heliolink.ui.StackedBar
import de.waschbeken.heliolink.ui.StatTile
import de.waschbeken.heliolink.ui.dbValue
import de.waschbeken.heliolink.ui.formatDuration
import kotlinx.coroutines.delay

/** Durations offered for a heart rate recording (seconds, null = until stopped). */
private val durations = listOf(60 to "1 min", 300 to "5 min", 600 to "10 min", 1800 to "30 min", 3600 to "1 h", 7200 to "2 h", null to "Bis ich stoppe")

/** Asks how long to record; [onStart] gets the seconds or null for "until stopped". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordDialog(connected: Boolean, onDismiss: () -> Unit, onStart: (Int?) -> Unit) {
    var choice by rememberSaveable { mutableStateOf<Int?>(300) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.FiberManualRecord, contentDescription = null, tint = Palette.Heart) },
        title = { Text("Puls aufzeichnen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Der Strap misst jede Sekunde. Die Aufzeichnung wird gespeichert und erscheint unter Training und im Tab „Daten“.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    durations.forEach { (seconds, label) ->
                        FilterChip(selected = choice == seconds, onClick = { choice = seconds }, label = { Text(label) })
                    }
                }
                if (!connected) Text("Der Strap ist gerade nicht verbunden.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = { onStart(choice); onDismiss() }, enabled = connected) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Opens the dialog and, after starting, the live screen. Returns the function that shows the dialog. */
@Composable
fun rememberRecordStarter(nav: NavHostController): () -> Unit {
    val app = LocalContext.current.app
    val state by app.repo.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    if (open) {
        RecordDialog(state.connected, onDismiss = { open = false }) { seconds ->
            app.repo.controller?.startRecording(seconds)
            nav.navigate(Routes.RECORDING)
        }
    }
    return {
        if (state.workout != null) nav.navigate(Routes.RECORDING) else open = true
    }
}

/** Small card while a recording or workout runs; tap opens the live screen. */
@Composable
fun RecordingBanner(nav: NavHostController) {
    val app = LocalContext.current.app
    val state by app.repo.state.collectAsStateWithLifecycle()
    val w = state.workout ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(w.id) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1_000)
        }
    }
    Card(
        onClick = { nav.navigate(if (w.sport == RECORDING_SPORT) Routes.RECORDING else Routes.TRAINING) },
        colors = CardDefaults.cardColors(containerColor = Palette.Heart.copy(alpha = 0.16f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.FiberManualRecord, contentDescription = null, tint = Palette.Heart, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "${w.sport} läuft · ${formatDuration(now - w.start)}" + (w.plannedEnd?.let { " von ${formatDuration(it - w.start)}" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            )
            Text(state.heartRate?.let { "$it bpm" } ?: "", style = MaterialTheme.typography.titleSmall, color = Palette.Heart)
        }
    }
}

/** Live view of a running heart rate recording. */
@Composable
fun RecordingScreen(nav: NavHostController) {
    val app = LocalContext.current.app
    val state by app.repo.state.collectAsStateWithLifecycle()
    val profile = app.prefs.profile
    val live = state.workout
    // Remember which recording this screen showed, to open it when it is finished.
    var shownId by rememberSaveable { mutableLongStateOf(-1L) }
    LaunchedEffect(live?.id) { if (live != null) shownId = live.id }
    LaunchedEffect(live == null, shownId) {
        if (live == null && shownId >= 0) {
            delay(400) // let the service write the end
            nav.popBackStack()
            nav.navigate(Routes.workout(shownId))
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1_000)
        }
    }
    val points by dbValue(live?.id, now / 2) { db -> live?.let { w -> db.workoutHr(w.id).map { it.timestamp to it.bpm.toFloat() } }.orEmpty() }

    Scaffold(topBar = { BackTopBar(live?.sport ?: "Aufzeichnung", onBack = { nav.popBackStack() }) }) { padding ->
        if (live == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(if (shownId >= 0) "Wird gespeichert …" else "Starte …")
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { LiveCard(live, state.heartRate, now, profile.maxHeartRate, profile.zoneRest(null), state.connected) }
            item {
                Panel {
                    Text("Letzte 5 Minuten (jede Sekunde)", style = MaterialTheme.typography.titleMedium)
                    val list = points.orEmpty()
                    if (list.size < 2) {
                        Text("Warte auf Messwerte …", style = MaterialTheme.typography.bodySmall)
                    } else {
                        // the last five minutes roll by; the whole recording can be zoomed after saving
                        LineChart(
                            listOf(Series(list, Palette.Heart, fill = true, width = 2f)), maxOf(live.start, now - 300), maxOf(now, live.start + 60),
                            modifier = Modifier.fillMaxWidth().height(200.dp), gapSeconds = 10,
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Ø Puls", "${live.avgHr}", Modifier.weight(1f))
                    StatTile("Max", "${live.maxHr}", Modifier.weight(1f))
                    StatTile("Werte", "${points?.size ?: 0}", Modifier.weight(1f))
                }
            }
            item {
                Button(
                    onClick = { app.repo.controller?.stopWorkout() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Beenden und speichern")
                }
            }
        }
    }
}

@Composable
private fun LiveCard(w: LiveWorkout, bpm: Int?, now: Long, maxHr: Int, rest: Int?, connected: Boolean) {
    val zone = bpm?.let { HeartRateZones.zoneOf(it, maxHr, rest) } ?: -1
    val color = if (zone >= 0) Palette.zones[zone] else Palette.Heart
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.16f))) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
                val progress = w.plannedEnd?.let { ((now - w.start).toFloat() / (it - w.start)).coerceIn(0f, 1f) } ?: 1f
                ProgressRing(progress, color, Modifier.size(110.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(bpm?.toString() ?: "–", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = color)
                    Text("bpm", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(formatDuration(now - w.start), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    w.plannedEnd?.let { "noch ${formatDuration((it - now).coerceAtLeast(0))}" } ?: "bis du stoppst",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(if (zone >= 0) "Zone ${zone + 1} · ${HeartRateZones.names[zone]}" else "unter Zone 1", style = MaterialTheme.typography.bodySmall)
                if (!connected) Text("Strap nicht verbunden – keine Werte", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        StackedBar(w.zoneSeconds.mapIndexed { i, s -> s.toFloat() to Palette.zones[i] }, Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).height(8.dp))
    }
}
