package de.waschbeken.heliolink.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.core.content.FileProvider
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.HeartRateZones
import de.waschbeken.heliolink.core.Tcx
import de.waschbeken.heliolink.data.Workout
import de.waschbeken.heliolink.service.StrapUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val sports = listOf(
    "Laufen" to "Running",
    "Radfahren" to "Biking",
    "Krafttraining" to "Other",
    "HIIT" to "Other",
    "Gehen" to "Other",
    "Sonstiges" to "Other",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkoutScreen(state: StrapUiState) {
    val context = LocalContext.current
    val controller = context.app.repo.controller
    val maxHr = context.app.prefs.profile.maxHeartRate
    var sport by remember { mutableStateOf(sports.first().first) }
    val workouts by dbValue(state.dataVersion) { it.workouts() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val live = state.workout
        if (live != null) {
            var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
            LaunchedEffect(live.id) {
                while (true) {
                    now = System.currentTimeMillis() / 1000
                    delay(1_000)
                }
            }
            val bpm = state.heartRate.takeIf { System.currentTimeMillis() - state.heartRateAt < 15_000 }
            val zone = bpm?.let { HeartRateZones.zoneOf(it, maxHr) } ?: -1
            SectionCard(live.sport) {
                Text(formatDuration(now - live.start), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        bpm?.toString() ?: "–",
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (zone >= 0) Palette.zones[zone] else Palette.Coral,
                    )
                    Column(Modifier.padding(bottom = 12.dp)) {
                        Text("bpm")
                        Text(if (zone >= 0) "Zone ${zone + 1} · ${HeartRateZones.names[zone]}" else "unter Zone 1")
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    Metric("Ø Puls", "${live.avgHr}", Modifier.weight(1f))
                    Metric("Max", "${live.maxHr}", Modifier.weight(1f))
                    Metric("kcal", "${live.calories.toInt()}", Modifier.weight(1f))
                }
                ZoneSummary(live.zoneSeconds)
                if (!state.connected) {
                    Text("Strap nicht verbunden – Aufzeichnung pausiert", color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = { controller?.stopWorkout() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Training beenden") }
            }
        } else {
            SectionCard("Training starten") {
                Text(
                    "Der Strap misst während des Trainings sekündlich deinen Puls. Die App zeigt Zonen, " +
                        "Kalorien und speichert alles – Export als TCX z. B. für Strava.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sports.forEach { (name, _) ->
                        FilterChip(selected = sport == name, onClick = { sport = name }, label = { Text(name) })
                    }
                }
                Button(
                    onClick = { controller?.startWorkout(sport) },
                    enabled = state.connected,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.connected) "Start" else "Strap nicht verbunden") }
                Text(
                    "Zonen bei max. Puls $maxHr: " + (0..4).joinToString(" · ") { "Z${it + 1} ab ${HeartRateZones.lowerBpm(it, maxHr)}" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text("Verlauf", style = MaterialTheme.typography.titleMedium)
        val list = workouts.orEmpty()
        if (list.isEmpty()) {
            Text("Noch keine Trainings aufgezeichnet.", style = MaterialTheme.typography.bodyMedium)
        }
        list.forEach { w -> WorkoutItem(w) }
    }
}

@Composable
private fun ZoneSummary(zoneSeconds: List<Int>) {
    StackedBar(zoneSeconds.mapIndexed { i, s -> s.toFloat() to Palette.zones[i] })
    Row(Modifier.fillMaxWidth()) {
        zoneSeconds.forEachIndexed { i, s ->
            Text(
                "Z${i + 1}\n${s / 60} min",
                color = Palette.zones[i],
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun WorkoutItem(w: Workout) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    val fmt = DateTimeFormatter.ofPattern("EEE d. MMM, HH:mm", Locale.GERMANY)
    val title = Instant.ofEpochSecond(w.start).atZone(ZoneId.systemDefault()).format(fmt)
    SectionCard("${w.sport} · $title") {
        Row(Modifier.fillMaxWidth()) {
            Metric("Dauer", formatDuration(w.end - w.start), Modifier.weight(1f))
            Metric("Ø / Max", "${w.avgHr}/${w.maxHr}", Modifier.weight(1f))
            Metric("kcal", "${w.calories}", Modifier.weight(1f))
        }
        if (w.zoneSeconds.size == 5) ZoneSummary(w.zoneSeconds)
        if (expanded) {
            val points by dbValue(w.id) { it.workoutHr(w.id) }
            TimeLineChart(points.orEmpty().map { it.timestamp to it.bpm }, w.start, w.end, Palette.Coral, gapSeconds = 30)
        }
        Row {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Weniger" else "Pulskurve") }
            TextButton(onClick = { scope.launch { shareTcx(context, w) } }) { Text("TCX teilen") }
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { context.app.db.deleteWorkout(w.id) }
                    context.app.repo.dataChanged()
                }
            }) { Text("Löschen") }
        }
    }
}

private suspend fun shareTcx(context: Context, w: Workout) {
    val sport = sports.firstOrNull { it.first == w.sport }?.second ?: "Other"
    val file = withContext(Dispatchers.IO) {
        val points = context.app.db.workoutHr(w.id).map { Tcx.Point(it.timestamp, it.bpm) }
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        File(dir, "helio-training-${w.start}.tcx").apply { writeText(Tcx.write(sport, w.start, w.end, points, w.calories)) }
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("application/vnd.garmin.tcx+xml")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Training exportieren"))
}
