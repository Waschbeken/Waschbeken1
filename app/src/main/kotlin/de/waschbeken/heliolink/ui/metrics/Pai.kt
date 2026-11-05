package de.waschbeken.heliolink.ui.metrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.waschbeken.heliolink.core.PaiSample
import de.waschbeken.heliolink.ui.BarChart
import de.waschbeken.heliolink.ui.Metric as MetricValue
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.Panel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** What was added today stands out from the PAI collected on the days before. */
val PaiToday = Color(0xFFF76B15)

private fun gain(v: Float): String = if (v > 0f && v < 10f) String.format(Locale.GERMANY, "%.1f", v) else v.roundToInt().toString()

/**
 * PAI like in Gadgetbridge: a ring filled up to the 7-day total (full at 100), the part earned on
 * [day] drawn in its own color, the total in the middle; below it what each of the last 7 days added.
 */
@Composable
fun PaiOverview(samples: List<PaiSample>, day: LocalDate) {
    val zone = ZoneId.systemDefault()
    val byDay = remember(samples) { samples.associateBy { Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() } }
    val entry = byDay.entries.filter { !it.key.isAfter(day) }.maxByOrNull { it.key }
    if (entry == null) {
        Panel { Text("Keine PAI-Werte.", style = MaterialTheme.typography.bodyMedium) }
        return
    }
    val latest = entry.value
    // An older value still counts for the total, but nothing was added on [day] then.
    val sameDay = entry.key == day
    val today = if (sameDay) latest.today.coerceAtLeast(0f) else 0f
    val total = latest.total.coerceAtLeast(0f)
    Panel {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PaiRing(total, today, Modifier.size(196.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${total.roundToInt()}", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                Text("PAI", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "+${gain(today)} ${if (day == LocalDate.now()) "heute" else "an dem Tag"}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = PaiToday,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Key(Palette.Pai, "vorher ${(total - today).coerceAtLeast(0f).roundToInt()}")
            Key(PaiToday, "dazu +${gain(today)}")
            Text(
                if (total >= 100f) "Ziel 100 ✓" else "Ziel 100",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val days = (6 downTo 0).map { day.minusDays(it.toLong()) }
    Panel {
        Text("Pro Tag dazugekommen", style = MaterialTheme.typography.titleSmall)
        BarChart(
            days.map { byDay[it]?.today?.coerceAtLeast(0f) ?: 0f },
            Palette.Pai,
            labels = days.map { it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMANY).take(2) },
            colors = days.map { if (it == day) PaiToday else Palette.Pai },
            format = { gain(it) },
            tooltip = { i -> "${days[i].dayOfMonth}.${days[i].monthValue}. · +${gain(byDay[days[i]]?.today ?: 0f)} · Stand ${byDay[days[i]]?.total?.roundToInt() ?: "–"}" },
        )
        if (sameDay && latest.lowMinutes + latest.moderateMinutes + latest.highMinutes > 0) {
            Row(Modifier.fillMaxWidth()) {
                MetricValue("min leicht", "${latest.lowMinutes}", Modifier.weight(1f))
                MetricValue("min mittel", "${latest.moderateMinutes}", Modifier.weight(1f))
                MetricValue("min hoch", "${latest.highMinutes}", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Key(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/** The ring: track, the PAI from the days before, then what [today] added – full at 100. */
@Composable
fun PaiRing(total: Float, today: Float, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier) {
        val stroke = size.minDimension * 0.1f
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)
        drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
        val end = 360f * (total / 100f).coerceIn(0f, 1f)
        // Above 100 the ring is full; today's part still shows at its end.
        val before = (end - 360f * (today / 100f).coerceIn(0f, 1f)).coerceAtLeast(0f)
        if (before > 0f) drawArc(Palette.Pai, -90f, before, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
        if (end - before > 0.5f) {
            drawArc(PaiToday, -90f + before, end - before, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
        }
    }
}

/** The ring as a thin bar for the tile on "Heute". */
@Composable
fun PaiBar(total: Float, today: Float, modifier: Modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp)) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier) {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        val end = size.width * (total / 100f).coerceIn(0f, 1f)
        val before = (end - size.width * (today / 100f).coerceIn(0f, 1f)).coerceAtLeast(0f)
        if (end > 0f) drawRoundRect(PaiToday, size = Size(end, size.height), cornerRadius = r)
        if (before > 0f) drawRoundRect(Palette.Pai, size = Size(before, size.height), cornerRadius = r)
    }
}

/** "3,4" – one decimal for small daily gains. */
fun paiGain(v: Float): String = gain(v)
