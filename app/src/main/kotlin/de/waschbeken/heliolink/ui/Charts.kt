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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.waschbeken.heliolink.core.ActivitySample
import de.waschbeken.heliolink.core.SleepStage
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** One line of a [LineChart]: points as (epoch seconds, value). */
class Series(val points: List<Pair<Long, Float>>, val color: Color, val fill: Boolean = false, val width: Float = 2.5f)

/** Text drawing that never fails on tight space: the text is measured first and placed freely. */
private fun DrawScope.label(m: TextMeasurer, text: String, x: Float, y: Float, color: Color, alignRight: Boolean = false, center: Boolean = false) {
    val layout = m.measure(text, TextStyle(color = color, fontSize = 10.sp))
    val w = layout.size.width.toFloat()
    val px = when {
        alignRight -> x - w
        center -> x - w / 2
        else -> x
    }.coerceIn(0f, max(0f, size.width - w))
    drawText(layout, topLeft = Offset(px, y.coerceIn(0f, max(0f, size.height - layout.size.height))))
}

private fun niceStep(range: Float, lines: Int = 4): Float {
    val raw = range / lines
    val steps = listOf(1f, 2f, 2.5f, 5f, 10f, 20f, 25f, 50f, 100f, 200f, 250f, 500f, 1000f, 2000f, 2500f, 5000f, 10000f)
    return steps.firstOrNull { it >= raw } ?: raw
}

/** Time axis labels (hours) below a chart area of width [w] starting at [left]. */
private fun DrawScope.timeAxis(m: TextMeasurer, from: Long, to: Long, left: Float, w: Float, y: Float, color: Color) {
    if (to <= from) return
    val zone = ZoneId.systemDefault()
    val hours = (to - from) / 3600.0
    val step = when {
        hours > 36 -> 12
        hours > 16 -> 6
        hours > 8 -> 3
        hours > 3 -> 1
        else -> 0
    }
    if (step == 0) {
        listOf(from, (from + to) / 2, to).forEachIndexed { i, t ->
            val txt = Instant.ofEpochSecond(t).atZone(zone).toLocalTime().toString().take(5)
            val px = left + (t - from).toFloat() / (to - from) * w
            label(m, txt, px, y, color, alignRight = i == 2, center = i == 1)
        }
        return
    }
    var t = Instant.ofEpochSecond(from).atZone(zone).withMinute(0).withSecond(0).withNano(0).plusHours(1)
    while (t.toEpochSecond() < to) {
        if (t.hour % step == 0) {
            val px = left + (t.toEpochSecond() - from).toFloat() / (to - from) * w
            label(m, "%02d".format(t.hour), px, y, color, center = true)
        }
        t = t.plusHours(1)
    }
}

/**
 * Line chart over time. Points further apart than [gapSeconds] are not connected. [bands] draws
 * dashed horizontal lines (e.g. heart rate zones).
 */
@Composable
fun LineChart(
    series: List<Series>,
    from: Long,
    to: Long,
    modifier: Modifier = Modifier.fillMaxWidth().height(180.dp),
    gapSeconds: Long = 600,
    minY: Float? = null,
    maxY: Float? = null,
    bands: List<Pair<Float, Color>> = emptyList(),
    format: (Float) -> String = { it.toInt().toString() },
) {
    val m = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    Canvas(modifier) {
        if (to <= from) return@Canvas
        val values = series.flatMap { s -> s.points.map { it.second } }
        if (values.isEmpty()) return@Canvas
        var lo = minY ?: values.min()
        var hi = maxY ?: values.max()
        if (hi - lo < 1e-3f) { lo -= 1; hi += 1 }
        val step = niceStep(hi - lo)
        lo = floor(lo / step) * step
        hi = ceil(hi / step) * step
        val axisH = 18.dp.toPx()
        val left = m.measure(format(hi), TextStyle(fontSize = 10.sp)).size.width + 6.dp.toPx()
        val w = size.width - left
        val h = size.height - axisH
        fun x(t: Long) = left + (t - from).toFloat() / (to - from) * w
        fun y(v: Float) = h - (v - lo) / (hi - lo) * h

        var v = lo
        while (v <= hi + step / 2) {
            drawLine(grid, Offset(left, y(v)), Offset(size.width, y(v)), 1f)
            label(m, format(v), left - 4.dp.toPx(), y(v) - 7.dp.toPx(), labelColor, alignRight = true)
            v += step
        }
        for ((value, c) in bands) {
            if (value in lo..hi) {
                drawLine(c.copy(alpha = 0.7f), Offset(left, y(value)), Offset(size.width, y(value)), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
        }
        timeAxis(m, from, to, left, w, h + 3.dp.toPx(), labelColor)

        for (s in series) {
            val path = Path()
            val fillPath = Path()
            var last: Long? = null
            var segmentStart = 0f
            var lastX = 0f
            for ((t, value) in s.points.sortedBy { it.first }) {
                val px = x(t)
                val py = y(value)
                if (last == null || t - last > gapSeconds) {
                    if (last != null && s.fill) {
                        fillPath.lineTo(lastX, h)
                        fillPath.lineTo(segmentStart, h)
                        fillPath.close()
                    }
                    path.moveTo(px, py)
                    if (s.fill) {
                        fillPath.moveTo(px, h)
                        fillPath.lineTo(px, py)
                    }
                    segmentStart = px
                } else {
                    path.lineTo(px, py)
                    if (s.fill) fillPath.lineTo(px, py)
                }
                last = t
                lastX = px
            }
            if (s.fill && last != null) {
                fillPath.lineTo(lastX, h)
                fillPath.lineTo(segmentStart, h)
                fillPath.close()
                drawPath(fillPath, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.35f), s.color.copy(alpha = 0.02f)), 0f, h))
            }
            drawPath(path, s.color, style = Stroke(width = s.width, cap = StrokeCap.Round))
        }
    }
}

/** Kept for older call sites: one heart rate like line. */
@Composable
fun TimeLineChart(
    points: List<Pair<Long, Int>>,
    from: Long,
    to: Long,
    color: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(180.dp),
    gapSeconds: Long = 600,
    bands: List<Pair<Int, Color>> = emptyList(),
) = LineChart(
    listOf(Series(points.filter { it.second > 0 }.map { it.first to it.second.toFloat() }, color, fill = true)),
    from, to, modifier, gapSeconds,
    bands = bands.map { it.first.toFloat() to it.second },
)

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
) {
    val m = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val goalColor = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
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
        highlight?.let { i ->
            val v = values.getOrNull(i) ?: return@let
            if (v > 0) label(m, format(v), i * slot + slot / 2, topLabel + h - v / max * h - 14.dp.toPx(), labelColor, center = true)
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
) {
    val m = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val goalColor = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
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
    }
}

/** Min–max range per day with a dot for the average (e.g. heart rate per day). */
@Composable
fun RangeChart(
    ranges: List<Triple<Float?, Float?, Float?>>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(160.dp),
) {
    val m = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    Canvas(modifier) {
        val all = ranges.flatMap { listOfNotNull(it.first, it.third) }
        if (all.isEmpty()) return@Canvas
        val step = niceStep(all.max() - all.min())
        val lo = floor(all.min() / step) * step
        val hi = ceil(all.max() / step) * step + if (all.max() == all.min()) step else 0f
        val labelH = 16.dp.toPx()
        val left = m.measure(hi.toInt().toString(), TextStyle(fontSize = 10.sp)).size.width + 6.dp.toPx()
        val h = size.height - labelH
        val w = size.width - left
        fun y(v: Float) = h - (v - lo) / (hi - lo) * h
        var v = lo
        while (v <= hi + step / 2) {
            drawLine(grid, Offset(left, y(v)), Offset(size.width, y(v)), 1f)
            label(m, v.toInt().toString(), left - 4.dp.toPx(), y(v) - 7.dp.toPx(), labelColor, alignRight = true)
            v += step
        }
        val slot = w / ranges.size
        val bw = (slot * 0.4f).coerceAtMost(10.dp.toPx())
        ranges.forEachIndexed { i, (min, avg, max) ->
            val cx = left + i * slot + slot / 2
            if (min != null && max != null) {
                drawRoundRect(color.copy(alpha = 0.35f), Offset(cx - bw / 2, y(max)), Size(bw, (y(min) - y(max)).coerceAtLeast(2f)), CornerRadius(bw / 2, bw / 2))
            }
            if (avg != null) drawCircle(color, radius = bw * 0.6f, center = Offset(cx, y(avg)))
            labels.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { label(m, it, cx, h + 2.dp.toPx(), labelColor, center = true) }
        }
    }
}

/** Sleep stages over time as steps (deep at the bottom), like the sleep charts of Gadgetbridge and Zepp. */
@Composable
fun Hypnogram(stages: List<Pair<Long, SleepStage>>, modifier: Modifier = Modifier.fillMaxWidth().height(170.dp)) {
    val m = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    Canvas(modifier) {
        if (stages.isEmpty()) return@Canvas
        val from = stages.first().first
        val to = stages.last().first + 60
        val rows = listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)
        val left = rows.maxOf { m.measure(stageName(it), TextStyle(fontSize = 10.sp)).size.width } + 8.dp.toPx()
        val axisH = 18.dp.toPx()
        val w = size.width - left
        val h = size.height - axisH
        val rowH = h / rows.size
        fun x(t: Long) = left + (t - from).toFloat() / (to - from) * w
        rows.forEachIndexed { i, s ->
            label(m, stageName(s), 0f, i * rowH + rowH / 2 - 7.dp.toPx(), Palette.stage(s))
            drawLine(grid, Offset(left, i * rowH + rowH / 2), Offset(size.width, i * rowH + rowH / 2), 1f)
        }
        var runStart = 0
        var prevCenter: Offset? = null
        for (i in 1..stages.size) {
            if (i == stages.size || stages[i].second != stages[runStart].second) {
                val s = stages[runStart].second
                val row = rows.indexOf(s)
                val x0 = x(stages[runStart].first)
                val x1 = x(stages[i - 1].first + 60)
                val top = row * rowH + rowH * 0.18f
                val bh = rowH * 0.64f
                prevCenter?.let { p ->
                    drawLine(labelColor.copy(alpha = 0.25f), Offset(x0, p.y), Offset(x0, top + bh / 2), 1.5f)
                }
                drawRoundRect(Palette.stage(s), Offset(x0, top), Size((x1 - x0).coerceAtLeast(2f), bh), CornerRadius(6f, 6f))
                prevCenter = Offset(x1, top + bh / 2)
                runStart = i
            }
        }
        timeAxis(m, from, to, left, w, h + 3.dp.toPx(), labelColor)
    }
}

/**
 * Overview of a day like Gadgetbridge's activity chart: bars every 5 minutes coloured by what
 * happened (sleep stage, movement, not worn), the height is the movement; the line is the heart rate.
 */
@Composable
fun DayChart(
    samples: List<ActivitySample>,
    from: Long,
    to: Long,
    sleepStages: Map<Long, SleepStage> = emptyMap(),
    modifier: Modifier = Modifier.fillMaxWidth().height(170.dp),
) {
    val m = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val moveColor = Palette.Steps
    val notWorn = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        if (to <= from) return@Canvas
        val axisH = 18.dp.toPx()
        val h = size.height - axisH
        val w = size.width
        fun x(t: Long) = (t - from).toFloat() / (to - from) * w
        val buckets = samples.groupBy { (it.timestamp - from) / 300 }
        val maxIntensity = (buckets.values.maxOfOrNull { b -> b.maxOf { it.intensity } } ?: 1).coerceAtLeast(40)
        val bw = (w / ((to - from) / 300f)).coerceAtLeast(1f)
        for ((idx, b) in buckets) {
            val t = from + idx * 300
            val stage = b.firstNotNullOfOrNull { sleepStages[it.timestamp] }
            val sleeping = stage != null || b.count { it.isSleep } > b.size / 2
            val color = when {
                stage != null -> Palette.stage(stage)
                sleeping -> Palette.Light
                b.all { it.notWorn } -> notWorn
                else -> moveColor
            }
            val height = when {
                sleeping -> h * 0.25f
                b.all { it.notWorn } -> h * 0.05f
                else -> (b.maxOf { it.intensity }.toFloat() / maxIntensity * h * 0.9f).coerceAtLeast(2f)
            }
            drawRect(color.copy(alpha = if (sleeping) 0.75f else 0.85f), Offset(x(t), h - height), Size(bw * 0.9f, height))
        }
        // heart rate line
        val hr = samples.filter { it.heartRate > 0 }
        if (hr.isNotEmpty()) {
            val lo = hr.minOf { it.heartRate } - 5f
            val hi = hr.maxOf { it.heartRate } + 5f
            val path = Path()
            var last: Long? = null
            for (s in hr) {
                val px = x(s.timestamp)
                val py = h - (s.heartRate - lo) / (hi - lo) * h
                if (last == null || s.timestamp - last > 600) path.moveTo(px, py) else path.lineTo(px, py)
                last = s.timestamp
            }
            drawPath(path, Palette.Heart, style = Stroke(width = 2f))
            label(m, "${hi.toInt() - 5}", w, 0f, Palette.Heart, alignRight = true)
        }
        timeAxis(m, from, to, 0f, w, h + 3.dp.toPx(), labelColor)
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
