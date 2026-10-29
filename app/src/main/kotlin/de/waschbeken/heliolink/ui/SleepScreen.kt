package de.waschbeken.heliolink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.waschbeken.heliolink.core.SleepAnalyzer
import de.waschbeken.heliolink.core.SleepStage
import de.waschbeken.heliolink.service.StrapUiState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SleepScreen(state: StrapUiState) {
    val zone = ZoneId.systemDefault()
    var date by remember { mutableStateOf(LocalDate.now()) }
    val weekStart = date.minusDays(7).atTime(18, 0).atZone(zone).toEpochSecond()
    val end = date.atTime(18, 0).atZone(zone).toEpochSecond()
    val samples by dbValue(date, state.dataVersion) { it.activity(weekStart, end) }
    val sessions = remember(samples) { SleepAnalyzer.sessions(samples.orEmpty()) }
    val night = SleepAnalyzer.nightOf(date, sessions, zone)
    val time = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DateSwitcher(date) { date = it }

        if (night == null) {
            SectionCard {
                Text("Für diese Nacht liegen keine Schlafdaten vor.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Trage den Strap nachts und synchronisiere morgens. Tipp: In den Strap-Einstellungen " +
                        "„Schlaf mit hoher Genauigkeit“ aktivieren, dann erkennt der Strap auch REM-Phasen besser.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val from = Instant.ofEpochSecond(night.start).atZone(zone).format(time)
            val to = Instant.ofEpochSecond(night.end).atZone(zone).format(time)
            SectionCard("Nacht $from – $to") {
                Row(Modifier.fillMaxWidth()) {
                    Metric("Schlafdauer", formatMinutes(night.asleepMinutes), Modifier.weight(1f))
                    Metric("Schlaf-Score", "${night.score}", Modifier.weight(1f), color = scoreColor(night.score))
                }
                Hypnogram(night.stages)
                StackedBar(SleepStage.entries.map { night.minutes(it).toFloat() to Palette.stage(it) })
                SleepStage.entries.forEach { s ->
                    val minutes = night.minutes(s)
                    val pct = if (night.durationMinutes > 0) minutes * 100 / night.durationMinutes else 0
                    Row(Modifier.fillMaxWidth()) {
                        Text("● ${stageName(s)}", color = Palette.stage(s), modifier = Modifier.weight(1f))
                        Text("${formatMinutes(minutes)}  ($pct %)")
                    }
                }
            }
            SectionCard("Puls im Schlaf") {
                Row(Modifier.fillMaxWidth()) {
                    Metric("Durchschnitt", "${night.avgHeartRate ?: "–"} bpm", Modifier.weight(1f))
                    Metric("Minimum", "${night.minHeartRate ?: "–"} bpm", Modifier.weight(1f))
                }
                val nightSamples = samples.orEmpty().filter { it.timestamp in night.start until night.end && it.heartRate > 0 }
                TimeLineChart(nightSamples.map { it.timestamp to it.heartRate }, night.start, night.end, Palette.Deep)
            }
        }

        SectionCard("Letzte 7 Nächte") {
            val days = (6 downTo 0).map { date.minusDays(it.toLong()) }
            val nights = days.map { SleepAnalyzer.nightOf(it, sessions, zone) }
            BarChart(
                nights.map { (it?.asleepMinutes ?: 0) / 60f },
                Palette.Light,
                labels = days.map { it.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.GERMANY) },
            )
            val valid = nights.filterNotNull()
            if (valid.isNotEmpty()) {
                Text(
                    "Ø ${formatMinutes(valid.sumOf { it.asleepMinutes } / valid.size)} pro Nacht · Ø Score ${valid.sumOf { it.score } / valid.size}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

fun scoreColor(score: Int) = when {
    score >= 80 -> androidx.compose.ui.graphics.Color(0xFF66BB6A)
    score >= 60 -> androidx.compose.ui.graphics.Color(0xFFFFB74D)
    else -> androidx.compose.ui.graphics.Color(0xFFE57373)
}
