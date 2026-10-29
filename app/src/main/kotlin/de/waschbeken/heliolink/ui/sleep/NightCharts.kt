package de.waschbeken.heliolink.ui.sleep

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.waschbeken.heliolink.core.SleepSession
import de.waschbeken.heliolink.core.ValueSample
import de.waschbeken.heliolink.ui.LineChart
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.Series
import de.waschbeken.heliolink.ui.rememberSections
import de.waschbeken.heliolink.ui.rememberTimeZoom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One value of the night: name, unit, color, points (epoch seconds, value) and an optional reference line. */
private class NightLine(val key: String, val title: String, val unit: String, val color: Color, val points: List<Pair<Long, Float>>, val decimals: Int, val reference: Float? = null)

/**
 * The night's values over time, each with the sleep stages as colored strip underneath – to see how
 * skin temperature, breathing, heart rate, SpO₂ and HRV go along with the stages. All charts share one
 * zoom, so they stay aligned; chips choose which ones are shown (remembered).
 */
@Composable
fun NightCharts(
    night: SleepSession,
    heartRate: List<Pair<Long, Float>>,
    temperature: List<ValueSample>,
    breath: List<ValueSample>,
    spo2: List<ValueSample>,
    hrv: List<ValueSample>,
    tempBaseline: Double? = null,
) {
    val inNight = { t: Long -> t >= night.start - 1800 && t <= night.end + 1800 }
    val lines = remember(night, heartRate, temperature, breath, spo2, hrv, tempBaseline) {
        listOf(
            NightLine("temp", "Hauttemperatur", "°C", Palette.Temperature, temperature.filter { inNight(it.timestamp) }.map { it.timestamp to it.value / 100f }, 1, tempBaseline?.toFloat()),
            NightLine("breath", "Atmung", "/min", Palette.Breath, breath.filter { inNight(it.timestamp) }.map { it.timestamp to it.value.toFloat() }, 0),
            NightLine("heart", "Puls", "bpm", Palette.Heart, heartRate.filter { inNight(it.first) }, 0),
            NightLine("spo2", "SpO₂", "%", Palette.Spo2, spo2.filter { inNight(it.timestamp) }.map { it.timestamp to it.value.toFloat() }, 0),
            NightLine("hrv", "HRV", "ms", Palette.Hrv, hrv.filter { inNight(it.timestamp) }.map { it.timestamp to it.value.toFloat() }, 0),
        ).filter { it.points.isNotEmpty() }
    }
    if (lines.isEmpty()) return
    val sections = rememberSections()
    // shown by default: temperature and breathing, otherwise the first line with values
    val defaults = lines.filter { it.key == "temp" || it.key == "breath" }.ifEmpty { lines.take(1) }.map { it.key }.toSet()
    val strip = remember(night) { night.stages.map { (t, st) -> t / 60 * 60 to Palette.stage(st) } }
    val zoom = rememberTimeZoom(night.start, night.end, minSpan = 900)
    val clock = DateTimeFormatter.ofPattern("HH:mm")
    fun hhmm(t: Long) = Instant.ofEpochSecond(t).atZone(ZoneId.systemDefault()).format(clock)
    Panel {
        Text("Nachtverlauf", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.forEach { l ->
                val key = "night-${l.key}"
                FilterChip(
                    selected = sections.isOpen(key, l.key in defaults),
                    onClick = { sections.toggle(key, l.key in defaults) },
                    label = { Text(l.title) },
                )
            }
        }
        lines.filter { sections.isOpen("night-${it.key}", it.key in defaults) }.forEach { l ->
            val values = l.points.map { it.second }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l.title, style = MaterialTheme.typography.titleSmall, color = l.color, modifier = Modifier.weight(1f))
                Text(
                    "Ø ${"%.${l.decimals}f".format(values.average())} · ${"%.${l.decimals}f".format(values.min())}–${"%.${l.decimals}f".format(values.max())} ${l.unit}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LineChart(
                listOf(Series(l.points, l.color, fill = true, width = 2f)), night.start, night.end,
                modifier = Modifier.fillMaxWidth().height(150.dp),
                gapSeconds = 1800,
                format = { "%.${l.decimals}f".format(it) },
                bands = listOfNotNull(l.reference?.let { it to l.color }),
                strip = strip,
                zoom = zoom,
                unit = l.unit,
            )
            if (l.reference != null) {
                Text("Gestrichelt: dein Schnitt (%.1f °C)".format(l.reference), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (zoom.zoomed) "${hhmm(zoom.start)} – ${hhmm(zoom.end)} · alle Diagramme zoomen mit" else "Leiste unten: Schlafphasen · Finger halten: Werte · zwei Finger: zoomen",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            if (zoom.zoomed) TextButton(onClick = { zoom.reset() }) { Text("Ganze Nacht") }
        }
    }
}
