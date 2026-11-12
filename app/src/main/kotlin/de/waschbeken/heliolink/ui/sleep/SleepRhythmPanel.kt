package de.waschbeken.heliolink.ui.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.waschbeken.heliolink.core.SleepRhythm
import de.waschbeken.heliolink.data.Queries
import de.waschbeken.heliolink.ui.German
import de.waschbeken.heliolink.ui.Metric
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.formatHours
import de.waschbeken.heliolink.ui.formatMinutes
import java.time.ZoneId
import java.time.format.TextStyle as DayStyle
import kotlin.math.abs

private fun ratingColor(r: SleepRhythm.Rating) = when (r) {
    SleepRhythm.Rating.VERY_REGULAR, SleepRhythm.Rating.REGULAR -> Color(0xFF46A758)
    SleepRhythm.Rating.VARIABLE -> Color(0xFFF59E0B)
    SleepRhythm.Rating.IRREGULAR -> Color(0xFFE5484D)
}

/** "1:10 h" or "40 min". */
private fun span(minutes: Int): String = if (abs(minutes) >= 60) formatHours(abs(minutes)) else "${abs(minutes)} min"

/**
 * When you go to bed and get up: one line per night from falling asleep to waking up, your usual
 * times lightly marked, with how much they vary, the shift at the weekend and what it does to your score.
 */
@Composable
internal fun SleepRhythmPanel(nights: List<Queries.NightSummary>, goal: Int) {
    val zone = ZoneId.systemDefault()
    val sorted = remember(nights) { nights.sortedBy { it.date } }
    val result = remember(sorted) { SleepRhythm.of(sorted.map { SleepRhythm.Night(it.date, it.start, it.end, it.score) }, zone) }
    val debt = nights.sumOf { (goal - it.asleepMinutes).coerceAtLeast(0) }
    if (result == null) {
        if (debt > 0) Panel { Text("Schlafdefizit zum Ziel: ${formatMinutes(debt)}", style = MaterialTheme.typography.bodyMedium) }
        return
    }
    // The drawing shows at most the last two weeks; the numbers count every night of the period.
    val shown = sorted.takeLast(14)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Schlafrhythmus", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(result.rating.label, style = MaterialTheme.typography.labelLarge, color = ratingColor(result.rating))
        }
        if (shown.size < sorted.size) {
            Text("Balken: letzte ${shown.size} Nächte", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ScheduleChart(
            shown.map { n ->
                Triple(
                    n.date.dayOfWeek.getDisplayName(DayStyle.SHORT, German).take(2) + " ${n.date.dayOfMonth}.",
                    SleepRhythm.minutesAfterNoon(n.start, n.date, zone),
                    SleepRhythm.minutesAfterNoon(n.end, n.date, zone),
                )
            },
            result.bedtime, result.wake,
        )
        Row {
            Metric("Ø ins Bett · ± ${result.bedSpread} min", SleepRhythm.clock(result.bedtime), Modifier.weight(1f))
            Metric("Ø aufgestanden · ± ${result.wakeSpread} min", SleepRhythm.clock(result.wake), Modifier.weight(1f))
        }
        result.weekendShift?.let { shift ->
            Text(
                when {
                    abs(shift) < 30 -> "Wochenende und Werktage fast gleich."
                    shift > 0 -> "Am Wochenende liegt deine Schlafmitte ${span(shift)} später als unter der Woche."
                    else -> "Am Wochenende liegt deine Schlafmitte ${span(shift)} früher als unter der Woche."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (result.scoreNear != null && result.scoreFar != null) {
            Text(
                "Score Ø ${result.scoreNear} bei deiner üblichen Zeit, Ø ${result.scoreFar} bei einer Stunde oder mehr daneben.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (debt > 0) Text("Schlafdefizit zum Ziel: ${formatMinutes(debt)}", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * One row per night: (label, asleep, awake) in minutes after noon. The usual bedtime and wake-up
 * time ± 30 minutes are shaded behind the bars; the axis shows the clock every two or three hours.
 */
@Composable
private fun ScheduleChart(rows: List<Triple<String, Int, Int>>, usualBed: Int, usualWake: Int) {
    val m = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val band = Palette.Sleep.copy(alpha = 0.13f)
    val rowHeight = 22.dp
    Canvas(Modifier.fillMaxWidth().height(rowHeight * rows.size + 20.dp)) {
        if (rows.isEmpty()) return@Canvas
        val small = TextStyle(fontSize = 10.sp, color = labelColor)
        val left = 46.dp.toPx()
        val rowH = rowHeight.toPx()
        val chartH = rowH * rows.size
        val w = size.width - left
        // whole hours around all nights and the usual times
        val lo = (minOf(rows.minOf { it.second }, usualBed - 30) - 30).floorDiv(60) * 60
        val hi = (maxOf(rows.maxOf { it.third }, usualWake + 30) + 89).floorDiv(60) * 60
        val range = (hi - lo).coerceAtLeast(60).toFloat()
        fun x(min: Int) = left + (min - lo) / range * w

        for (centre in listOf(usualBed, usualWake)) {
            drawRect(band, Offset(x(centre - 30), 0f), Size(x(centre + 30) - x(centre - 30), chartH))
        }
        val step = if (hi - lo > 12 * 60) 180 else 120
        var t = lo
        while (t <= hi) {
            drawLine(grid, Offset(x(t), 0f), Offset(x(t), chartH), 1f)
            val text = m.measure(SleepRhythm.clock(t).take(2), small)
            drawText(text, topLeft = Offset(x(t) - text.size.width / 2f, chartH + 4.dp.toPx()))
            t += step
        }
        rows.forEachIndexed { i, (label, start, end) ->
            val y = i * rowH + rowH / 2
            val text = m.measure(label, small)
            drawText(text, topLeft = Offset(0f, y - text.size.height / 2f))
            val barH = 10.dp.toPx()
            drawRoundRect(
                Palette.Sleep,
                Offset(x(start), y - barH / 2),
                Size((x(end) - x(start)).coerceAtLeast(2f), barH),
                CornerRadius(barH / 2),
            )
        }
    }
}
