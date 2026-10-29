package de.waschbeken.heliolink.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.ble.LinkState
import de.waschbeken.heliolink.core.DayAnalyzer
import de.waschbeken.heliolink.core.HeartRateZones
import de.waschbeken.heliolink.core.SleepAnalyzer
import de.waschbeken.heliolink.core.SleepStage
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.service.StrapUiState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

@Composable
fun TodayScreen(state: StrapUiState, openSleep: () -> Unit) {
    val context = LocalContext.current
    val controller = context.app.repo.controller
    val profile = context.app.prefs.profile
    val zone = ZoneId.systemDefault()
    var date by remember { mutableStateOf(LocalDate.now()) }

    val dayStart = date.atStartOfDay(zone).toEpochSecond()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
    val nightStart = date.minusDays(1).atTime(18, 0).atZone(zone).toEpochSecond()
    val samples by dbValue(date, state.dataVersion) { it.activity(nightStart, dayEnd) }
    val stress by dbValue(date, state.dataVersion) { it.stress(dayStart, dayEnd) }
    val records by dbValue(date, state.dataVersion) { it.sleepRecords(nightStart, dayEnd) }
    val spo2 by dbValue(date, state.dataVersion) { it.values(HealthDb.SPO2, nightStart, dayEnd) }
    val resting by dbValue(date, state.dataVersion) { it.values(HealthDb.RESTING_HR, dayStart - 86400, dayEnd) }
    val hrv by dbValue(date, state.dataVersion) { it.values(HealthDb.HRV, nightStart, dayEnd) }
    val appHr by dbValue(date, state.dataVersion, state.heartRateAt / 60_000) { it.hr(dayStart, dayEnd) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(state)

        if (date == LocalDate.now()) LiveHeartCard(state, profile.maxHeartRate) { controller?.setLiveHeartRate(it) }

        DateSwitcher(date) { date = it }

        val all = samples.orEmpty()
        val day = all.filter { it.timestamp in dayStart until dayEnd }
        val summary = DayAnalyzer.summarize(date, day, zone)

        // Steps: the strap's own counter is the most current value for today.
        val steps = if (date == LocalDate.now() && state.steps != null) state.steps.toInt() else summary.steps
        SectionCard("Schritte") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                    ProgressRing(steps / profile.stepGoal.toFloat(), Palette.Steps, Modifier.size(84.dp))
                    Text("${(steps * 100 / profile.stepGoal.coerceAtLeast(1))} %", fontWeight = FontWeight.SemiBold)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Metric("von ${profile.stepGoal}", "$steps")
                    if (date == LocalDate.now() && state.distanceMeters != null) {
                        Text(
                            "%.2f km · %d kcal".format(state.distanceMeters / 1000.0, state.calories ?: 0),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            val hourly = FloatArray(24)
            day.forEach { s ->
                val h = java.time.Instant.ofEpochSecond(s.timestamp).atZone(zone).hour
                hourly[h] += s.steps.toFloat()
            }
            if (hourly.any { it > 0 }) {
                BarChart(hourly.toList(), Palette.Steps, labels = List(24) { if (it % 6 == 0) "$it" else "" })
            }
        }

        val sessions = SleepAnalyzer.combine(records.orEmpty().map { it.toSession(all) }, SleepAnalyzer.sessions(all))
        val night = SleepAnalyzer.nightOf(date, sessions, zone)
        SectionCard("Schlaf", modifier = Modifier.clickable(onClick = openSleep)) {
            if (night == null) {
                Text("Keine Schlafdaten für diese Nacht", style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(Modifier.fillMaxWidth()) {
                    Metric("geschlafen", formatMinutes(night.asleepMinutes), Modifier.weight(1f))
                    Metric("Score", "${night.score}", Modifier.weight(1f))
                    Metric("Tiefschlaf", formatMinutes(night.minutes(SleepStage.DEEP)), Modifier.weight(1f))
                }
                StackedBar(SleepStage.entries.map { night.minutes(it).toFloat() to Palette.stage(it) })
            }
        }

        val spo2Values = spo2.orEmpty().map { it.value }
        val strapResting = resting.orEmpty().lastOrNull { it.timestamp < dayEnd }?.value
        val nightHrv = night?.let { n -> hrv.orEmpty().filter { it.timestamp in n.start until n.end }.map { it.value } }.orEmpty()
        if (spo2Values.isNotEmpty() || strapResting != null || nightHrv.isNotEmpty()) {
            SectionCard("Erholung") {
                Row(Modifier.fillMaxWidth()) {
                    Metric("Ruhepuls", strapResting?.let { "$it bpm" } ?: "–", Modifier.weight(1f))
                    Metric("HRV nachts", if (nightHrv.isEmpty()) "–" else "${nightHrv.average().toInt()} ms", Modifier.weight(1f))
                    Metric(
                        "SpO₂",
                        if (spo2Values.isEmpty()) "–" else "${spo2Values.average().toInt()} %",
                        Modifier.weight(1f),
                        color = if (spo2Values.isNotEmpty() && spo2Values.min() < 90) Color(0xFFFFB74D) else Color.Unspecified,
                    )
                }
                if (spo2Values.isNotEmpty()) {
                    Text("SpO₂ min. ${spo2Values.min()} % aus ${spo2Values.size} Messungen", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SectionCard("Herzfrequenz") {
            if (summary.avgHeartRate == null && appHr.isNullOrEmpty()) {
                Text("Noch keine Pulswerte für diesen Tag", style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(Modifier.fillMaxWidth()) {
                    Metric("Ruhepuls", "${summary.restingHeartRate ?: "–"}", Modifier.weight(1f))
                    Metric("Durchschnitt", "${summary.avgHeartRate}", Modifier.weight(1f))
                    Metric("Min / Max", "${summary.minHeartRate}/${summary.maxHeartRate}", Modifier.weight(1f))
                }
                val recorded = appHr.orEmpty()
                // Where the app recorded more often than once a minute, those values replace the minute values.
                val recordedMinutes = recorded.map { it.timestamp / 60 }.toHashSet()
                val points = (day.filter { it.heartRate > 0 && it.timestamp / 60 !in recordedMinutes }.map { it.timestamp to it.heartRate } +
                    recorded.map { it.timestamp to it.bpm }).sortedBy { it.first }
                TimeLineChart(points, dayStart, dayEnd, Palette.Coral)
                if (recorded.isNotEmpty()) {
                    Text(
                        "${recorded.size} zusätzliche Werte aus der Puls-Aufzeichnung der App",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val stressValues = stress.orEmpty()
        if (stressValues.isNotEmpty()) {
            SectionCard("Stress") {
                val avg = stressValues.map { it.stress }.average().toInt()
                Row(Modifier.fillMaxWidth()) {
                    Metric("Durchschnitt", "$avg", Modifier.weight(1f), color = stressColor(avg))
                    Metric("Maximum", "${stressValues.maxOf { it.stress }}", Modifier.weight(1f))
                    Metric("entspannt", formatMinutes(stressValues.count { it.stress < 40 }), Modifier.weight(1f))
                }
                val buckets = List(48) { i ->
                    val from = dayStart + i * 1800L
                    stressValues.filter { it.timestamp in from until from + 1800 }.map { it.stress }.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f
                }
                BarChart(buckets, Palette.Stress, colors = buckets.map { stressColor(it.toInt()) })
                Legend(listOf("entspannt" to stressColor(20), "normal" to stressColor(50), "mittel" to stressColor(70), "hoch" to stressColor(90)))
            }
        }
    }
}

fun stressColor(v: Int): Color = when {
    v < 40 -> Color(0xFF4FC3F7)
    v < 60 -> Color(0xFF81C784)
    v < 80 -> Color(0xFFFFB74D)
    else -> Color(0xFFE57373)
}

@Composable
private fun StatusCard(state: StrapUiState) {
    val controller = LocalContext.current.app.repo.controller
    val (text, color) = when (state.link) {
        LinkState.READY -> "Verbunden" to Color(0xFF66BB6A)
        LinkState.CONNECTING -> "Verbinde…" to Color(0xFFFFB74D)
        LinkState.AUTHENTICATING -> "Anmeldung…" to Color(0xFFFFB74D)
        LinkState.AUTH_FAILED -> "Auth-Key abgelehnt" to Color(0xFFE57373)
        LinkState.DISCONNECTED -> "Nicht verbunden" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("● $text", color = color, fontWeight = FontWeight.SemiBold)
                state.linkDetail?.takeIf { state.link != LinkState.READY }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val last = if (state.lastSync > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.lastSync)) else "nie"
                Text("Letzter Sync: $last", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.battery?.let {
                AssistChip(onClick = {}, label = { Text("🔋 $it %" + if (state.charging) " ⚡" else "") })
            }
        }
        if (state.syncing) {
            Text(state.syncText ?: "Synchronisiere…", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { controller?.syncNow() }, enabled = state.connected) { Text("Jetzt synchronisieren") }
                if (state.link == LinkState.DISCONNECTED || state.link == LinkState.AUTH_FAILED) {
                    FilledTonalButton(onClick = { controller?.reconnect() }) { Text("Neu verbinden") }
                }
            }
        }
    }
}

@Composable
private fun LiveHeartCard(state: StrapUiState, maxHr: Int, onToggle: (Boolean) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            now = System.currentTimeMillis()
        }
    }
    val fresh = state.heartRate != null && now - state.heartRateAt < 30_000
    val bpm = state.heartRate.takeIf { fresh }
    val zoneIdx = bpm?.let { HeartRateZones.zoneOf(it, maxHr) } ?: -1
    SectionCard("Puls live", trailing = {
        Switch(checked = state.liveHeartRate, onCheckedChange = onToggle, enabled = state.connected)
    }) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                bpm?.toString() ?: "–",
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold,
                color = if (zoneIdx >= 0) Palette.zones[zoneIdx] else Palette.Coral,
            )
            Column(Modifier.padding(bottom = 10.dp)) {
                Text("bpm", style = MaterialTheme.typography.bodyMedium)
                if (zoneIdx >= 0) Text("Zone ${zoneIdx + 1} · ${HeartRateZones.names[zoneIdx]}", style = MaterialTheme.typography.bodySmall)
            }
        }
        state.hrv?.takeIf { fresh }?.let {
            Text("HRV (RMSSD): ${it.toInt()} ms", style = MaterialTheme.typography.bodySmall)
        }
        if (state.recordingHr) {
            Text(
                "Puls-Aufzeichnung läuft (Einstellungen → Puls-Aufzeichnung)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!state.liveHeartRate && !fresh && !state.recordingHr) {
            Text(
                "Schalte die Live-Messung ein, um den Puls in Echtzeit zu sehen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
