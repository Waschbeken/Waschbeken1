package de.waschbeken.heliolink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.waschbeken.heliolink.core.SleepStage
import java.time.Instant
import java.time.ZoneId

private const val LABEL_SPACE = 34f

/** Line chart for heart rate like data. Points further apart than [gapSeconds] are not connected. */
@Composable
fun TimeLineChart(
    points: List<Pair<Long, Int>>,
    from: Long,
    to: Long,
    color: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(160.dp),
    gapSeconds: Long = 600,
    bands: List<Pair<Int, Color>> = emptyList(),
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    Canvas(modifier) {
        if (to <= from) return@Canvas
        val values = points.map { it.second }
        val minV = ((values.minOrNull() ?: 50) - 5).coerceAtMost(50) / 10 * 10
        val maxV = ((values.maxOrNull() ?: 120) + 10).coerceAtLeast(100).let { (it + 9) / 10 * 10 }
        val chartW = size.width - LABEL_SPACE
        val h = size.height - 18f
        fun x(t: Long) = LABEL_SPACE + (t - from).toFloat() / (to - from) * chartW
        fun y(v: Int) = h - (v - minV).toFloat() / (maxV - minV) * h

        // zone bands
        for ((lower, c) in bands) {
            if (lower in minV until maxV) {
                drawLine(c.copy(alpha = 0.6f), Offset(LABEL_SPACE, y(lower)), Offset(size.width, y(lower)), 1.5f)
            }
        }
        // grid + labels
        val step = if (maxV - minV > 80) 40 else 20
        var v = (minV + step - 1) / step * step
        while (v <= maxV) {
            drawLine(gridColor, Offset(LABEL_SPACE, y(v)), Offset(size.width, y(v)), 1f)
            drawText(measurer, "$v", Offset(0f, y(v) - 8f), TextStyle(color = labelColor, fontSize = 10.sp))
            v += step
        }
        timeAxis(from, to, chartW, h, measurer, labelColor)

        val path = Path()
        var last: Long? = null
        for ((t, value) in points) {
            if (value <= 0) continue
            if (last == null || t - last > gapSeconds) path.moveTo(x(t), y(value)) else path.lineTo(x(t), y(value))
            last = t
        }
        drawPath(path, color, style = Stroke(width = 2.5f))
    }
}

private fun DrawScope.timeAxis(
    from: Long,
    to: Long,
    chartW: Float,
    h: Float,
    measurer: androidx.compose.ui.text.TextMeasurer,
    labelColor: Color,
) {
    val zone = ZoneId.systemDefault()
    val spanHours = (to - from) / 3600.0
    val stepHours = when {
        spanHours > 18 -> 6
        spanHours > 8 -> 3
        spanHours > 3 -> 1
        else -> 0
    }
    if (stepHours == 0) {
        listOf(from, (from + to) / 2, to).forEach { t ->
            val txt = Instant.ofEpochSecond(t).atZone(zone).toLocalTime().toString().take(5)
            val px = LABEL_SPACE + (t - from).toFloat() / (to - from) * chartW
            drawText(measurer, txt, Offset((px - 14f).coerceIn(LABEL_SPACE, size.width - 30f), h + 2f), TextStyle(color = labelColor, fontSize = 10.sp))
        }
        return
    }
    var t = Instant.ofEpochSecond(from).atZone(zone).withMinute(0).withSecond(0).plusHours(1)
    while (t.toEpochSecond() < to) {
        if (t.hour % stepHours == 0) {
            val px = LABEL_SPACE + (t.toEpochSecond() - from).toFloat() / (to - from) * chartW
            drawText(measurer, "%02d".format(t.hour), Offset(px - 6f, h + 2f), TextStyle(color = labelColor, fontSize = 10.sp))
        }
        t = t.plusHours(1)
    }
}

@Composable
fun BarChart(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(110.dp),
    labels: List<String> = emptyList(),
    colors: List<Color>? = null,
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1f
        val labelH = if (labels.isEmpty()) 0f else 18f
        val h = size.height - labelH
        val slot = size.width / values.size
        val bar = slot * 0.7f
        values.forEachIndexed { i, v ->
            val bh = v / max * h
            val c = colors?.getOrNull(i) ?: color
            if (bh > 0) {
                drawRoundRect(c, Offset(i * slot + (slot - bar) / 2, h - bh), Size(bar, bh), CornerRadius(3f, 3f))
            }
            labels.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                drawText(measurer, it, Offset(i * slot, h + 2f), TextStyle(color = labelColor, fontSize = 10.sp))
            }
        }
    }
}

/** Sleep stages over time, deep sleep at the bottom. */
@Composable
fun Hypnogram(stages: List<Pair<Long, SleepStage>>, modifier: Modifier = Modifier.fillMaxWidth().height(140.dp)) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        if (stages.isEmpty()) return@Canvas
        val from = stages.first().first
        val to = stages.last().first + 60
        val rows = listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)
        val chartW = size.width - LABEL_SPACE
        val h = size.height - 18f
        val rowH = h / rows.size
        rows.forEachIndexed { i, s ->
            drawText(measurer, stageName(s), Offset(0f, i * rowH + rowH / 2 - 8f), TextStyle(color = labelColor, fontSize = 9.sp))
        }
        var runStart = 0
        for (i in 1..stages.size) {
            if (i == stages.size || stages[i].second != stages[runStart].second) {
                val s = stages[runStart].second
                val row = rows.indexOf(s)
                val x0 = LABEL_SPACE + (stages[runStart].first - from).toFloat() / (to - from) * chartW
                val x1 = LABEL_SPACE + (stages[i - 1].first + 60 - from).toFloat() / (to - from) * chartW
                drawRoundRect(
                    Palette.stage(s),
                    Offset(x0, row * rowH + rowH * 0.15f),
                    Size((x1 - x0).coerceAtLeast(1.5f), rowH * 0.7f),
                    CornerRadius(4f, 4f),
                )
                runStart = i
            }
        }
        timeAxis(from, to, chartW, h, measurer, labelColor)
    }
}

/** Horizontal stacked bar, e.g. time in heart rate zones or sleep stages. */
@Composable
fun StackedBar(parts: List<Pair<Float, Color>>, modifier: Modifier = Modifier.fillMaxWidth().height(14.dp)) {
    val track = MaterialTheme.colorScheme.surfaceVariant
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

@Composable
fun ProgressRing(progress: Float, color: Color, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        val stroke = size.minDimension * 0.12f
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}
