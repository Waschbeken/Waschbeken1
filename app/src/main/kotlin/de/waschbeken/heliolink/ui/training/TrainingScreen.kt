package de.waschbeken.heliolink.ui.training

import android.content.Context
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import de.waschbeken.heliolink.core.ActivityDetector
import de.waschbeken.heliolink.core.DetectedActivity
import de.waschbeken.heliolink.core.HeartRateZones
import de.waschbeken.heliolink.core.Tcx
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.data.Workout
import de.waschbeken.heliolink.service.LiveWorkout
import de.waschbeken.heliolink.ui.BackTopBar
import de.waschbeken.heliolink.ui.BarChart
import de.waschbeken.heliolink.ui.German
import de.waschbeken.heliolink.ui.LineChart
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.Routes
import de.waschbeken.heliolink.ui.SectionHeader
import de.waschbeken.heliolink.ui.Series
import de.waschbeken.heliolink.ui.StackedBar
import de.waschbeken.heliolink.ui.StatTile
import de.waschbeken.heliolink.ui.dbValue
import de.waschbeken.heliolink.ui.exportFile
import de.waschbeken.heliolink.ui.fmt
import de.waschbeken.heliolink.ui.formatDuration
import de.waschbeken.heliolink.ui.formatMinutes
import de.waschbeken.heliolink.ui.rememberSaveFile
import de.waschbeken.heliolink.ui.shareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

val sports = listOf(
    "Laufen" to "Running",
    "Radfahren" to "Biking",
    "Krafttraining" to "Other",
    "HIIT" to "Other",
    "Gehen" to "Other",
    "Fußball" to "Other",
    "Schwimmen" to "Other",
    "Sonstiges" to "Other",
)

private class TrainingData(
    val workouts: List<Workout>,
    val detected: List<DetectedActivity>,
    val weekMinutes: List<Float>,
    val weekLabels: List<String>,
    val restingHr: Int?,
)

private fun load(db: HealthDb): TrainingData {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val from = today.minusDays(6)
    val start = from.atStartOfDay(zone).toEpochSecond()
    val end = today.plusDays(1).atStartOfDay(zone).toEpochSecond()
    val workouts = db.workouts()
    val resting = db.values(HealthDb.RESTING_HR, start - 14 * 86400, end).lastOrNull()?.value
    val detected = ActivityDetector.detect(db.activity(start, end), resting)
        .filter { a -> workouts.none { w -> a.start < w.end && w.start < a.end } } // already recorded as workout
        .sortedByDescending { it.start }
    val days = (0..6).map { from.plusDays(it.toLong()) }
    val minutes = days.map { d ->
        val ds = d.atStartOfDay(zone).toEpochSecond()
        val de = ds + 86400
        (workouts.filter { it.start in ds until de }.sumOf { (it.end - it.start) / 60 } +
            detected.filter { it.start in ds until de }.sumOf { it.minutes.toLong() }).toFloat()
    }
    return TrainingData(workouts, detected, minutes, days.map { it.dayOfWeek.getDisplayName(TextStyle.SHORT, German).take(2) }, resting)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrainingScreen(nav: NavHostController) {
    val app = LocalContext.current.app
    val state by app.repo.state.collectAsStateWithLifecycle()
    val tick by app.prefs.changes.collectAsStateWithLifecycle()
    val profile = app.prefs.profile.also { tick }
    val data by dbValue(state.dataVersion) { load(it) }
    val controller = app.repo.controller
    var sport by remember { mutableStateOf(app.prefs.defaultSport) }
    val rest = profile.zoneRest(data?.restingHr)

    Scaffold(topBar = { TopAppBar(title = { Text("Training") }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val live = state.workout
            if (live != null) {
                item { LiveWorkoutCard(live, state.heartRate.takeIf { System.currentTimeMillis() - state.heartRateAt < 15_000 }, profile.maxHeartRate, rest, state.connected) { controller?.stopWorkout() } }
            } else {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Training starten", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Der Strap misst sekündlich deinen Puls. Du siehst Zone, Kalorien und Verlauf – und kannst es als TCX exportieren.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                sports.forEach { (name, _) -> FilterChip(selected = sport == name, onClick = { sport = name }, label = { Text(name) }) }
                            }
                            Button(onClick = { controller?.startWorkout(sport) }, enabled = state.connected, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (state.connected) "Start" else "Strap nicht verbunden")
                            }
                        }
                    }
                }
                item {
                    Panel {
                        Text("Deine Zonen (max. ${profile.maxHeartRate} bpm)", style = MaterialTheme.typography.titleSmall)
                        StackedBar((0..4).map { 1f to Palette.zones[it] })
                        Text(
                            (0..4).joinToString("  ") { "Z${it + 1} ${HeartRateZones.lowerBpm(it, profile.maxHeartRate, rest)}" },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            data?.let { d ->
                item { SectionHeader("Aktive Minuten – letzte 7 Tage") }
                item { Panel { BarChart(d.weekMinutes, Palette.Steps, labels = d.weekLabels, goal = 30f) } }
                if (d.detected.isNotEmpty()) {
                    item { SectionHeader("Automatisch erkannt") }
                    d.detected.take(10).forEach { a ->
                        item {
                            Panel(onClick = { nav.navigate(Routes.metric("heart")) }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (a.kind == DetectedActivity.Kind.RUN) Icons.AutoMirrored.Filled.DirectionsRun else Icons.AutoMirrored.Filled.DirectionsWalk,
                                        contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("${a.kind.label} · ${dateTime(a.start)}", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "${formatMinutes(a.minutes)} · ${a.steps.fmt()} Schritte · ${a.cadence}/min" + (a.avgHeartRate?.let { " · Ø $it bpm" } ?: ""),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item { SectionHeader("Verlauf") }
                if (d.workouts.isEmpty()) {
                    item { Text("Noch keine Trainings aufgezeichnet.", style = MaterialTheme.typography.bodyMedium) }
                }
                d.workouts.forEach { w ->
                    item {
                        Panel(onClick = { nav.navigate(Routes.workout(w.id)) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Favorite, contentDescription = null, tint = Palette.Heart)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${w.sport} · ${dateTime(w.start)}", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${formatDuration(w.end - w.start)} · Ø ${w.avgHr} · max. ${w.maxHr} bpm · ${w.calories} kcal",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (w.zoneSeconds.size == 5) {
                                        Spacer(Modifier.width(4.dp))
                                        StackedBar(w.zoneSeconds.mapIndexed { i, s -> s.toFloat() to Palette.zones[i] }, Modifier.fillMaxWidth().padding(top = 6.dp).then(Modifier))
                                    }
                                }
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun dateTime(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("EEE d. MMM, HH:mm", German))

@Composable
private fun LiveWorkoutCard(live: LiveWorkout, bpm: Int?, maxHr: Int, rest: Int?, connected: Boolean, onStop: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(live.id) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1_000)
        }
    }
    val zone = bpm?.let { HeartRateZones.zoneOf(it, maxHr, rest) } ?: -1
    Card(colors = CardDefaults.cardColors(containerColor = if (zone >= 0) Palette.zones[zone].copy(alpha = 0.22f) else MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(live.sport, style = MaterialTheme.typography.titleMedium)
            Text(formatDuration(now - live.start), fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(bpm?.toString() ?: "–", fontSize = 64.sp, fontWeight = FontWeight.Bold, color = if (zone >= 0) Palette.zones[zone] else Palette.Heart)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.padding(bottom = 14.dp)) {
                    Text("bpm")
                    Text(if (zone >= 0) "Zone ${zone + 1} · ${HeartRateZones.names[zone]}" else "unter Zone 1")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Ø Puls", "${live.avgHr}", Modifier.weight(1f))
                StatTile("Max", "${live.maxHr}", Modifier.weight(1f))
                StatTile("kcal", "${live.calories.toInt()}", Modifier.weight(1f))
            }
            StackedBar(live.zoneSeconds.mapIndexed { i, s -> s.toFloat() to Palette.zones[i] })
            Text(
                live.zoneSeconds.mapIndexed { i, s -> "Z${i + 1} ${s / 60}′" }.joinToString("  "),
                style = MaterialTheme.typography.labelSmall,
            )
            if (!connected) Text("Strap nicht verbunden – Aufzeichnung pausiert", color = MaterialTheme.colorScheme.error)
            Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Training beenden")
            }
        }
    }
}

/** TCX with every recorded heart rate value; Strava, Garmin Connect & Co. import it. */
private fun tcxOf(context: Context, w: Workout): String {
    val sport = sports.firstOrNull { it.first == w.sport }?.second ?: "Other"
    val points = context.app.db.workoutHr(w.id).map { Tcx.Point(it.timestamp, it.bpm) }
    return Tcx.write(sport, w.start, w.end, points, w.calories)
}

@Composable
fun WorkoutDetailScreen(nav: NavHostController, id: Long) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val workout by dbValue(id) { db -> db.workouts().firstOrNull { it.id == id } }
    val points by dbValue(id) { it.workoutHr(id) }
    val profile = app.prefs.profile
    var confirmDelete by remember { mutableStateOf(false) }
    val w = workout
    val fileName = "helio-training-${w?.let { Instant.ofEpochSecond(it.start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")) } ?: id}.tcx"
    val saveTcx = rememberSaveFile("application/octet-stream", "Training gespeichert") { uri ->
        val ww = w ?: return@rememberSaveFile
        context.contentResolver.openOutputStream(uri)?.use { it.write(tcxOf(context, ww).toByteArray()) }
    }

    Scaffold(topBar = { BackTopBar(w?.sport ?: "Training", onBack = { nav.popBackStack() }, subtitle = w?.let { dateTime(it.start) }) }) { padding ->
        if (w == null) return@Scaffold
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Dauer", formatDuration(w.end - w.start), Modifier.weight(1f))
                    StatTile("Kalorien", "${w.calories} kcal", Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Ø Puls", "${w.avgHr} bpm", Modifier.weight(1f), tint = Palette.Heart)
                    StatTile("Max. Puls", "${w.maxHr} bpm", Modifier.weight(1f), tint = Palette.Heart)
                }
            }
            item {
                Panel {
                    Text("Pulsverlauf", style = MaterialTheme.typography.titleMedium)
                    val p = points.orEmpty()
                    if (p.isEmpty()) Text("Keine Pulswerte gespeichert.") else LineChart(
                        listOf(Series(p.map { it.timestamp to it.bpm.toFloat() }, Palette.Heart, fill = true)), w.start, w.end, gapSeconds = 30,
                        bands = (1..4).map { HeartRateZones.lowerBpm(it, profile.maxHeartRate, null).toFloat() to Palette.zones[it] },
                    )
                }
            }
            if (w.zoneSeconds.size == 5) {
                item {
                    Panel {
                        Text("Zeit in Zonen", style = MaterialTheme.typography.titleMedium)
                        StackedBar(w.zoneSeconds.mapIndexed { i, s -> s.toFloat() to Palette.zones[i] })
                        val total = w.zoneSeconds.sum().coerceAtLeast(1)
                        w.zoneSeconds.forEachIndexed { i, s ->
                            Text(
                                "Zone ${i + 1} · ${HeartRateZones.names[i]}: ${formatDuration(s.toLong())} (${s * 100 / total} %)",
                                style = MaterialTheme.typography.bodyMedium, color = Palette.zones[i],
                            )
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { saveTcx(fileName) }) { Text("TCX speichern") }
                    TextButton(onClick = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { exportFile(context, fileName).apply { writeText(tcxOf(context, w)) } }
                            shareFile(context, file, "application/octet-stream", "Training teilen")
                        }
                    }) { Text("Teilen") }
                    TextButton(onClick = { confirmDelete = true }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (confirmDelete && w != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Training löschen?") },
            text = { Text("Das Training und seine Pulswerte werden gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { app.db.deleteWorkout(w.id) }
                        app.repo.dataChanged()
                        nav.popBackStack()
                    }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}
