package de.waschbeken.heliolink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.max

// Bars, rings and small charts: per day or week, stacked parts, progress, sparklines, the year heatmap.

/** Bars with labels below; [goal] draws a dashed target line. */
@Composable
fun BarChart(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(140.dp),
    labels: List<String> = emptyList(),
    colors: List<Color>? = null,
    goal: Float? = null,
    highlight: Int? = null,
    format: (Float) -> String = { it.toInt().fmt() },
    /** Text when holding a finger on bar i; defaults to its label and value. */
    tooltip: ((Int) -> String)? = null,
) {
    val m = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val goalColor = MaterialTheme.colorScheme.primary
    val tipBg = MaterialTheme.colorScheme.inverseSurface
    val tipFg = MaterialTheme.colorScheme.inverseOnSurface
    val inspect = rememberInspect()
    Canvas(modifier.holdToInspect(inspect)) {
        if (values.isEmpty()) return@Canvas
        val max = maxOf(values.maxOrNull() ?: 0f, goal ?: 0f).takeIf { it > 0 } ?: 1f
        val labelH = if (labels.isEmpty()) 0f else 16.dp.toPx()
        val topLabel = 14.dp.toPx()
        val h = size.height - labelH - topLabel
        val slot = size.width / values.size
        val bar = (slot * 0.66f).coerceAtMost(28.dp.toPx())
        values.forEachIndexed { i, v ->
            val x = i * slot + (slot - bar) / 2
            drawRoundRect(track, Offset(x, topLabel), Size(bar, h), CornerRadius(6f, 6f))
            val bh = v / max * h
            val c = colors?.getOrNull(i) ?: color
            if (bh > 0) {
                drawRoundRect(
                    if (highlight == null || highlight == i) c else c.copy(alpha = 0.55f),
                    Offset(x, topLabel + h - bh), Size(bar, bh), CornerRadius(6f, 6f),
                )
            }
            labels.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                label(m, it, i * slot + slot / 2, topLabel + h + 2.dp.toPx(), labelColor, center = true)
            }
        }
        if (goal != null && goal > 0) {
            val gy = topLabel + h - goal / max * h
            drawLine(goalColor, Offset(0f, gy), Offset(size.width, gy), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            label(m, "Ziel ${format(goal)}", size.width, gy - 14.dp.toPx(), goalColor, alignRight = true)
        }
        val selected = inspect.position?.let { p -> (p.x / slot).toInt().coerceIn(0, values.lastIndex) }
        val wide = slot >= 30.dp.toPx()
        values.forEachIndexed { i, v ->
            if (v > 0 && (i == highlight || (wide && selected == null))) {
                label(m, format(v), i * slot + slot / 2, topLabel + h - v / max * h - 14.dp.toPx(), labelColor, center = true)
            }
        }
        if (selected != null) {
            val cx = selected * slot + slot / 2
            drawLine(labelColor.copy(alpha = 0.5f), Offset(cx, topLabel), Offset(cx, topLabel + h), 1.5f)
            val text = tooltip?.invoke(selected) ?: listOfNotNull(labels.getOrNull(selected)?.takeIf { it.isNotBlank() }, format(values[selected])).joinToString(" · ")
            tooltip(m, listOf(text to tipFg), cx, tipBg)
        }
    }
}

/** Stacked bars, e.g. sleep stages per night. Each stack is a list of (value, color) from bottom to top. */
@Composable
fun StackedBarChart(
    stacks: List<List<Pair<Float, Color>>>,
    labels: List<String>,
    modifier: Modifier = Modifier.fillMaxWidth().height(160.dp),
    goal: Float? = null,
    format: (Float) -> String = { it.toInt().toString() },
    /** Lines shown when holding a finger on stack i. */
    tooltip: ((Int) -> List<String>)? = null,
) {
    val m = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val goalColor = MaterialTheme.colorScheme.primary
    val tipBg = MaterialTheme.colorScheme.inverseSurface
    val tipFg = MaterialTheme.colorScheme.inverseOnSurface
    val inspect = rememberInspect()
    Canvas(modifier.holdToInspect(inspect)) {
        if (stacks.isEmpty()) return@Canvas
        val max = maxOf(stacks.maxOf { s -> s.sumOf { it.first.toDouble() }.toFloat() }, goal ?: 0f).takeIf { it > 0 } ?: 1f
        val labelH = 16.dp.toPx()
        val top = 14.dp.toPx()
        val h = size.height - labelH - top
        val slot = size.width / stacks.size
        val bar = (slot * 0.66f).coerceAtMost(28.dp.toPx())
        stacks.forEachIndexed { i, stack ->
            val x = i * slot + (slot - bar) / 2
            drawRoundRect(track, Offset(x, top), Size(bar, h), CornerRadius(6f, 6f))
            var y = top + h
            for ((v, c) in stack) {
                val bh = v / max * h
                if (bh <= 0) continue
                drawRect(c, Offset(x, y - bh), Size(bar, bh))
                y -= bh
            }
            labels.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { label(m, it, i * slot + slot / 2, top + h + 2.dp.toPx(), labelColor, center = true) }
        }
        if (goal != null && goal > 0) {
            val gy = top + h - goal / max * h
            drawLine(goalColor, Offset(0f, gy), Offset(size.width, gy), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            label(m, "Ziel ${format(goal)}", size.width, gy - 14.dp.toPx(), goalColor, alignRight = true)
        }
        inspect.position?.let { p ->
            val i = (p.x / slot).toInt().coerceIn(0, stacks.lastIndex)
            val cx = i * slot + slot / 2
            drawLine(labelColor.copy(alpha = 0.5f), Offset(cx, top), Offset(cx, top + h), 1.5f)
            val lines = tooltip?.invoke(i) ?: listOf(format(stacks[i].sumOf { it.first.toDouble() }.toFloat()))
            tooltip(m, lines.mapIndexed { n, l -> l to tipFg }, cx, tipBg)
        }
    }
}

/** Horizontal stacked bar, e.g. time in heart rate zones or sleep stages. */
@Composable
fun StackedBar(parts: List<Pair<Float, Color>>, modifier: Modifier = Modifier.fillMaxWidth().height(14.dp)) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier) {
        val total = parts.sumOf { it.first.toDouble() }.toFloat()
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2, size.height / 2))
        if (total <= 0f) return@Canvas
        var x = 0f
        for ((v, c) in parts) {
            val w = v / total * size.width
            if (w > 0) drawRect(c, Offset(x, 0f), Size(w, size.height))
            x += w
        }
    }
}

/** Calendar heatmap: one column per week, rows Monday–Sunday (like the learning heatmap). */
@Composable
fun Heatmap(values: List<Pair<java.time.LocalDate, Float>>, color: Color, modifier: Modifier = Modifier.fillMaxWidth().height(120.dp)) {
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val first = values.first().first.let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
        val weeks = ((values.last().first.toEpochDay() - first.toEpochDay()) / 7 + 1).toInt()
        val max = values.maxOf { it.second }.takeIf { it > 0 } ?: 1f
        val cell = minOf(size.width / weeks, size.height / 7)
        val gap = cell * 0.15f
        for ((date, v) in values) {
            val col = ((date.toEpochDay() - first.toEpochDay()) / 7).toInt()
            val row = date.dayOfWeek.value - 1
            val c = if (v <= 0) empty else color.copy(alpha = 0.2f + 0.8f * (v / max).coerceIn(0f, 1f))
            drawRoundRect(c, Offset(col * cell, row * cell), Size(cell - gap, cell - gap), CornerRadius(cell * 0.2f, cell * 0.2f))
        }
    }
}

@Composable
fun ProgressRing(progress: Float, color: Color, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier) {
        val stroke = size.minDimension * 0.12f
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** Tiny line without axes for the dashboard tiles. */
@Composable
fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier.fillMaxWidth().height(28.dp)) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max().let { if (it - lo < 1e-3f) lo + 1 else it }
        val path = Path()
        values.forEachIndexed { i, v ->
            val px = i.toFloat() / (values.size - 1) * size.width
            val py = size.height - (v - lo) / (hi - lo) * size.height
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
    }
}
