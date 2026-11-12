package de.waschbeken.heliolink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import de.waschbeken.heliolink.core.ActivitySample
import de.waschbeken.heliolink.core.SleepStage

// A day or a night: sleep stages, what the strap saw per minute, the day clock and the activity chart.

/**
 * Sleep stages over time as steps (deep at the bottom), like the sleep charts of Gadgetbridge and Zepp.
 * Zoom with two fingers or a double tap; hold a finger on it to see the stage with start, end and length.
 */
@Composable
fun Hypnogram(stages: List<Pair<Long, SleepStage>>, modifier: Modifier = Modifier.fillMaxWidth().height(170.dp)) {
    val m = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val tipBg = MaterialTheme.colorScheme.inverseSurface
    val tipFg = MaterialTheme.colorScheme.inverseOnSurface
    val sorted = remember(stages) { stages.sortedBy { it.first } }
    val nightFrom = sorted.firstOrNull()?.first ?: 0L
    val nightTo = (sorted.lastOrNull()?.first ?: 0L) + 60
    val zoom = rememberTimeZoom(nightFrom, nightTo, minSpan = 900)
    val inspect = rememberInspect()
    // runs of the same stage: (start, end, stage)
    val runs = remember(sorted) {
        val out = ArrayList<Triple<Long, Long, SleepStage>>()
        for ((t, st) in sorted) {
            val last = out.lastOrNull()
            if (last != null && last.third == st && t - last.second <= 60) out[out.lastIndex] = Triple(last.first, t + 60, st)
            else out += Triple(t, t + 60, st)
        }
        out
    }
    Canvas(modifier.timeZoom(zoom).holdToInspect(inspect)) {
        if (sorted.isEmpty()) return@Canvas
        val from = zoom.start
        val to = zoom.end
        if (to <= from) return@Canvas
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
        clipRect(left, 0f, size.width, h) {
            var prevCenter: Offset? = null
            for ((a, b, st) in runs) {
                if (b < from || a > to) { prevCenter = null; continue }
                val row = rows.indexOf(st)
                val x0 = x(a)
                val x1 = x(b)
                val top = row * rowH + rowH * 0.18f
                val bh = rowH * 0.64f
                prevCenter?.let { p -> drawLine(labelColor.copy(alpha = 0.25f), Offset(x0, p.y), Offset(x0, top + bh / 2), 1.5f) }
                drawRoundRect(Palette.stage(st), Offset(x0, top), Size((x1 - x0).coerceAtLeast(2f), bh), CornerRadius(6f, 6f))
                // zoomed in: the length is written into the bar
                val text = spanText(b - a)
                val tw = m.measure(text, TextStyle(fontSize = 10.sp)).size.width
                if (x1 - x0 > tw + 8.dp.toPx() && zoom.zoomed) {
                    label(m, text, (x0 + x1) / 2, top + bh / 2 - 7.dp.toPx(), Color.White, center = true)
                }
                prevCenter = Offset(x1, top + bh / 2)
            }
        }
        timeAxis(m, from, to, left, w, h + 3.dp.toPx(), labelColor)
        inspect.position?.let { p ->
            val t = from + (((p.x - left) / w).coerceIn(0f, 1f) * (to - from)).toLong()
            val run = runs.firstOrNull { t >= it.first && t < it.second } ?: runs.minByOrNull { minOf(kotlin.math.abs(it.first - t), kotlin.math.abs(it.second - t)) }
            if (run != null) {
                val (a, b, st) = run
                val row = rows.indexOf(st)
                drawRoundRect(
                    labelColor, Offset(x(a), row * rowH + rowH * 0.12f), Size((x(b) - x(a)).coerceAtLeast(2f), rowH * 0.76f),
                    CornerRadius(7f, 7f), style = Stroke(2.dp.toPx()),
                )
                tooltip(
                    m,
                    listOf(stageName(st) to tipFg, "${timeText(a, 86400)} – ${timeText(b, 86400)}" to tipFg, spanText(b - a) to Palette.stage(st)),
                    (x(a) + x(b)) / 2, tipBg,
                )
            }
        }
    }
}

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
            // Only sleep the sleep detection confirmed (night or nap): the strap also marks short calm
            // stretches with a high pulse as sleep.
            val sleeping = stage != null
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

/** What the strap saw in a stretch of time; colors follow Gadgetbridge's activity charts. */
enum class DayState { NO_DATA, NOT_WORN, IDLE, LIGHT, ACTIVE, AWAKE, SLEEP_LIGHT, SLEEP_DEEP, SLEEP_REM }

fun dayState(samples: List<ActivitySample>, stages: Map<Long, SleepStage>): DayState {
    if (samples.isEmpty()) return DayState.NO_DATA
    val stage = samples.mapNotNull { stages[it.timestamp / 60 * 60] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    if (stage != null) {
        return when (stage) {
            SleepStage.DEEP -> DayState.SLEEP_DEEP
            SleepStage.REM -> DayState.SLEEP_REM
            SleepStage.AWAKE -> DayState.AWAKE
            SleepStage.LIGHT -> DayState.SLEEP_LIGHT
        }
    }
    if (samples.count { it.notWorn } * 2 > samples.size) return DayState.NOT_WORN
    val stepsPerMinute = samples.sumOf { it.steps }.toFloat() / samples.size
    return when {
        stepsPerMinute >= 60 -> DayState.ACTIVE
        stepsPerMinute >= 10 || samples.any { it.intensity >= 60 } -> DayState.LIGHT
        else -> DayState.IDLE
    }
}

fun dayStateName(s: DayState): String = when (s) {
    DayState.NO_DATA -> "Keine Daten"
    DayState.NOT_WORN -> "Nicht getragen"
    DayState.IDLE -> "Ruhig"
    DayState.LIGHT -> "Leicht aktiv"
    DayState.ACTIVE -> "Aktiv"
    DayState.AWAKE -> "Wach im Bett"
    DayState.SLEEP_LIGHT -> "Leichter Schlaf"
    DayState.SLEEP_DEEP -> "Tiefschlaf"
    DayState.SLEEP_REM -> "REM-Schlaf"
}

@Composable
fun dayStateColor(s: DayState): Color = when (s) {
    DayState.NO_DATA -> Color.Transparent
    DayState.NOT_WORN -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    DayState.IDLE -> MaterialTheme.colorScheme.surfaceContainerHighest
    DayState.LIGHT -> Palette.Steps.copy(alpha = 0.5f)
    DayState.ACTIVE -> Palette.Steps
    DayState.AWAKE -> Palette.Awake
    DayState.SLEEP_LIGHT -> Palette.Light
    DayState.SLEEP_DEEP -> Palette.Deep
    DayState.SLEEP_REM -> Palette.Rem
}

@Composable
internal fun stateColors(): Map<DayState, Color> = DayState.entries.associateWith { dayStateColor(it) }

/** Legend entries for [DayClock], [ActivityChart] and [DayStrip]. */
@Composable
fun dayStateLegend(): List<Pair<String, Color>> = listOf(
    "Aktiv" to Palette.Steps,
    "Leicht aktiv" to Palette.Steps.copy(alpha = 0.5f),
    "Ruhig" to MaterialTheme.colorScheme.surfaceContainerHighest,
    "Schlaf" to Palette.Light,
    "Tief" to Palette.Deep,
    "REM" to Palette.Rem,
    "Nicht getragen" to MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
)

/**
 * The day as a 24-hour clock (midnight at the top) like Gadgetbridge's dashboard: activity, sleep and
 * the time the strap was not worn as colored ring, the current time as a dot. [center] is drawn inside.
 */
/** The day clock only knows four things, so it stays readable: sleep, active, calm (worn) and not worn. */
enum class ClockState(val label: String) { NONE("Keine Daten"), NOT_WORN("Nicht getragen"), CALM("Ruhig"), ACTIVE("Aktiv"), SLEEP("Schlaf") }

internal fun clockState(s: DayState) = when (s) {
    DayState.NO_DATA -> ClockState.NONE
    DayState.NOT_WORN -> ClockState.NOT_WORN
    DayState.IDLE -> ClockState.CALM
    DayState.LIGHT, DayState.ACTIVE -> ClockState.ACTIVE
    DayState.AWAKE, DayState.SLEEP_LIGHT, DayState.SLEEP_DEEP, DayState.SLEEP_REM -> ClockState.SLEEP
}

/** 144 parts of 10 minutes; parts shorter than half an hour are merged into their neighbour. */
fun clockSegments(samples: List<ActivitySample>, stages: Map<Long, SleepStage>, dayStart: Long): Array<ClockState> {
    val counts = Array(144) { IntArray(ClockState.entries.size) }
    for (s in samples) {
        if (s.timestamp < dayStart || s.timestamp >= dayStart + 86400) continue
        counts[((s.timestamp - dayStart) / 600).toInt()][clockState(dayState(listOf(s), stages)).ordinal]++
    }
    val seg = Array(144) { i ->
        val c = counts[i]
        if (c.sum() == 0) ClockState.NONE else ClockState.entries.maxBy { c[it.ordinal] * 10 + it.ordinal }
    }
    repeat(2) {
        var i = 0
        while (i < seg.size) {
            var j = i
            while (j + 1 < seg.size && seg[j + 1] == seg[i]) j++
            if (j - i + 1 < 3 && seg[i] != ClockState.NONE) {
                val target = listOfNotNull(seg.getOrNull(i - 1), seg.getOrNull(j + 1)).firstOrNull { it != ClockState.NONE }
                if (target != null) for (k in i..j) seg[k] = target
            }
            i = j + 1
        }
    }
    return seg
}

@Composable
fun clockColor(s: ClockState): Color = when (s) {
    ClockState.NONE -> Color.Transparent
    ClockState.NOT_WORN -> MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    ClockState.CALM -> Palette.Steps.copy(alpha = 0.3f)
    ClockState.ACTIVE -> Palette.Steps
    ClockState.SLEEP -> Palette.Sleep
}

/** Legend for [DayClock]. */
@Composable
fun clockLegend(): List<Pair<String, Color>> =
    listOf(ClockState.SLEEP, ClockState.ACTIVE, ClockState.CALM, ClockState.NOT_WORN).map { it.label to clockColor(it) }

/**
 * The day as a 24-hour clock (midnight at the top) like Gadgetbridge's dashboard: sleep, activity and
 * the time the strap was not worn as a few large colored parts, the current time as a dot. Hold a
 * finger on it to see a part with start, end and length. [center] is drawn inside.
 */
@Composable
fun DayClock(
    samples: List<ActivitySample>,
    stages: Map<Long, SleepStage>,
    dayStart: Long,
    modifier: Modifier = Modifier.size(180.dp),
    center: @Composable () -> Unit = {},
) {
    val colors = ClockState.entries.associateWith { clockColor(it) }
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    val now = MaterialTheme.colorScheme.onSurface
    val m = rememberTextMeasurer()
    val segments = remember(samples, stages, dayStart) { clockSegments(samples, stages, dayStart) }
    val inspect = rememberInspect()
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // the touched run of equal parts: (first, last, state)
    val run = inspect.position?.let { p ->
        val angle = (Math.toDegrees(kotlin.math.atan2((p.y - boxSize.height / 2f).toDouble(), (p.x - boxSize.width / 2f).toDouble())) + 90 + 360) % 360
        val i = (angle / 360 * segments.size).toInt().coerceIn(0, segments.lastIndex)
        val st = segments[i]
        var a = i
        var b = i
        while (a > 0 && segments[a - 1] == st) a--
        while (b < segments.lastIndex && segments[b + 1] == st) b++
        Triple(a, b, st)
    }
    Box(modifier.onSizeChanged { boxSize = it }.holdToInspect(inspect), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.14f
            val inset = stroke / 2 + 4.dp.toPx()
            val arcSize = Size(size.minDimension - inset * 2, size.minDimension - inset * 2)
            val topLeft = Offset((size.width - arcSize.width) / 2, (size.height - arcSize.height) / 2)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            val sweep = 360f / segments.size
            // one arc per run of equal parts, with a small gap between runs
            var i = 0
            while (i < segments.size) {
                var j = i
                while (j + 1 < segments.size && segments[j + 1] == segments[i]) j++
                val st = segments[i]
                if (st != ClockState.NONE) {
                    val inRun = run != null && i >= run.first && j <= run.second
                    val color = colors.getValue(st).let { if (run != null && !inRun) it.copy(alpha = it.alpha * 0.35f) else it }
                    val gap = if (j - i >= 1) 0.8f else 0f
                    drawArc(color, -90f + i * sweep + gap / 2, (j - i + 1) * sweep - gap, false, topLeft, arcSize, style = Stroke(if (inRun) stroke * 1.25f else stroke))
                }
                i = j + 1
            }
            val c = Offset(size.width / 2, size.height / 2)
            val rInner = arcSize.width / 2 - stroke / 2 - 4.dp.toPx()
            for (h in 0 until 24 step 3) {
                val a = Math.toRadians((-90 + h * 15).toDouble())
                val len = if (h % 6 == 0) 6.dp.toPx() else 3.dp.toPx()
                val p1 = Offset(c.x + (rInner * kotlin.math.cos(a)).toFloat(), c.y + (rInner * kotlin.math.sin(a)).toFloat())
                val p2 = Offset(c.x + ((rInner - len) * kotlin.math.cos(a)).toFloat(), c.y + ((rInner - len) * kotlin.math.sin(a)).toFloat())
                drawLine(tick.copy(alpha = 0.6f), p1, p2, 2f)
                if (h % 6 == 0 && run == null) {
                    val layout = m.measure("$h", TextStyle(color = tick, fontSize = 11.sp))
                    val rl = rInner - len - 9.dp.toPx()
                    val lp = Offset(c.x + (rl * kotlin.math.cos(a)).toFloat() - layout.size.width / 2, c.y + (rl * kotlin.math.sin(a)).toFloat() - layout.size.height / 2)
                    drawText(layout, topLeft = lp)
                }
            }
            val nowSec = System.currentTimeMillis() / 1000
            if (nowSec in dayStart until dayStart + 86400) {
                val a = Math.toRadians(-90 + (nowSec - dayStart) / 86400.0 * 360)
                val r = arcSize.width / 2
                val p = Offset(c.x + (r * kotlin.math.cos(a)).toFloat(), c.y + (r * kotlin.math.sin(a)).toFloat())
                drawCircle(now, radius = stroke * 0.42f, center = p)
                drawCircle(track, radius = stroke * 0.22f, center = p)
            }
        }
        if (run == null) {
            center()
        } else {
            val (a, b, st) = run
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(st.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text("${timeText(dayStart + a * 600L, 86400)}–${timeText(dayStart + (b + 1) * 600L, 86400)}", style = MaterialTheme.typography.labelSmall)
                Text(spanText((b + 1 - a) * 600L), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * Gadgetbridge style day chart: one bar per few minutes colored by what you did (activity, sleep
 * stage, not worn), the bar height is the movement intensity, the heart rate as red line on top.
 * Zoomable with [zoom].
 */
@Composable
fun ActivityChart(
    samples: List<ActivitySample>,
    heartRate: List<Pair<Long, Float>>,
    stages: Map<Long, SleepStage>,
    zoom: TimeZoom,
    modifier: Modifier = Modifier.fillMaxWidth().height(220.dp),
    /** Quick entries shown as flags (time, name). */
    events: List<Pair<Long, String>> = emptyList(),
) {
    val eventColor = MaterialTheme.colorScheme.tertiary
    val colors = stateColors()
    val m = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val byMinute = remember(samples) { samples.associateBy { it.timestamp / 60 } }
    val maxIntensity = remember(samples) { (samples.filter { !it.isSleep && !it.notWorn }.maxOfOrNull { it.intensity } ?: 0).coerceAtLeast(40) }
    val inspect = rememberInspect()
    val tipBg = MaterialTheme.colorScheme.inverseSurface
    val tipFg = MaterialTheme.colorScheme.inverseOnSurface
    val cursorColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.timeZoom(zoom).holdToInspect(inspect)) {
        val from = zoom.start
        val to = zoom.end
        if (to <= from) return@Canvas
        val axisH = 18.dp.toPx()
        val right = 28.dp.toPx()
        val h = size.height - axisH
        val w = size.width - right
        fun x(t: Long) = (t - from).toFloat() / (to - from) * w
        drawLine(grid, Offset(0f, h), Offset(w, h), 1f)
        val bucket = maxOf(60L, ((to - from) / (w / 3f)).toLong() / 60 * 60)
        val bw = (bucket.toFloat() / (to - from) * w).coerceAtLeast(1f)
        clipRect(0f, 0f, w, h) {
            var t = from / bucket * bucket
            while (t < to) {
                val list = ArrayList<ActivitySample>()
                var minute = t / 60
                while (minute < (t + bucket) / 60) {
                    byMinute[minute]?.let { list += it }
                    minute++
                }
                val st = dayState(list, stages)
                if (st != DayState.NO_DATA) {
                    val height = when (st) {
                        DayState.SLEEP_DEEP -> 0.18f
                        DayState.SLEEP_LIGHT -> 0.26f
                        DayState.SLEEP_REM -> 0.34f
                        DayState.AWAKE -> 0.42f
                        DayState.NOT_WORN -> 0.05f
                        DayState.IDLE -> 0.07f
                        else -> (list.maxOf { it.intensity }.toFloat() / maxIntensity).coerceIn(0.12f, 1f) * 0.92f
                    } * h
                    drawRect(colors.getValue(st), Offset(x(t), h - height), Size(bw * 0.92f, height))
                }
                t += bucket
            }
            val visible = visiblePoints(heartRate, from, to, w.toInt(), 600)
            if (visible.isNotEmpty()) {
                val lo = (visible.minOf { it.second } - 5f).coerceAtLeast(0f)
                val hi = visible.maxOf { it.second } + 5f
                fun y(v: Float) = h - (v - lo) / (hi - lo) * h
                val path = Path()
                var last: Long? = null
                for ((ts, v) in visible) {
                    if (last == null || ts - last > 600) path.moveTo(x(ts), y(v)) else path.lineTo(x(ts), y(v))
                    last = ts
                }
                drawPath(path, Palette.Heart, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                val raw = heartRate.count { it.first in from..to }
                if (raw in 1..(w / 30.dp.toPx()).toInt() && inspect.position == null) {
                    for ((ts, v) in heartRate) if (ts in from..to) label(m, "${v.toInt()}", x(ts), y(v) - 15.dp.toPx(), Palette.Heart, center = true)
                }
            }
        }
        if (events.isNotEmpty()) eventMarks(m, events, ::x, from, to, 0f, w, h, eventColor)
        inspect.position?.let { p ->
            val t = from + ((p.x / w).coerceIn(0f, 1f) * (to - from)).toLong()
            val minute = t / 60
            val near = events.filter { kotlin.math.abs(it.first - t) <= maxOf(600L, (to - from) / 40) }
            val stateAt = { mm: Long -> dayState(listOfNotNull(byMinute[mm]), stages) }
            val st = stateAt(minute)
            var a = minute
            var b = minute
            if (st != DayState.NO_DATA) {
                while (a - 1 > minute - 1440 && stateAt(a - 1) == st) a--
                while (b + 1 < minute + 1440 && stateAt(b + 1) == st) b++
            }
            drawLine(cursorColor.copy(alpha = 0.6f), Offset(x(t), 0f), Offset(x(t), h), 1.5f)
            val sample = byMinute[minute]
            val hr = nearest(heartRate, t, 300)
            hr?.let { (ts, v) ->
                val visibleHr = visiblePoints(heartRate, from, to, w.toInt(), 600)
                if (visibleHr.isNotEmpty()) {
                    val lo = (visibleHr.minOf { it.second } - 5f).coerceAtLeast(0f)
                    val hi = visibleHr.maxOf { it.second } + 5f
                    drawCircle(Palette.Heart, radius = 5.dp.toPx(), center = Offset(x(ts), h - (v - lo) / (hi - lo) * h))
                }
            }
            val lines = ArrayList<Pair<String, Color>>()
            lines += timeText(t, 86400) to tipFg
            if (st != DayState.NO_DATA) {
                lines += dayStateName(st) to colors.getValue(st).copy(alpha = 1f).let { if (st == DayState.IDLE) tipFg else it }
                lines += "${timeText(a * 60, 86400)} – ${timeText((b + 1) * 60, 86400)} · ${spanText((b + 1 - a) * 60)}" to tipFg
            }
            val details = listOfNotNull(
                sample?.steps?.takeIf { it > 0 }?.let { "$it Schritte" },
                hr?.let { "Puls ${it.second.toInt()}" },
            )
            if (details.isNotEmpty()) lines += details.joinToString(" · ") to Palette.Heart
            near.forEach { (et, name) -> lines += "$name · ${timeText(et, 86400)}" to eventColor }
            tooltip(m, lines, x(t), tipBg)
        }
        val visible = visiblePoints(heartRate, from, to, w.toInt(), 600)
        if (visible.isNotEmpty()) {
            val lo = (visible.minOf { it.second } - 5f).coerceAtLeast(0f)
            val hi = visible.maxOf { it.second } + 5f
            label(m, "${(hi - 5).toInt()}", size.width, 0f, Palette.Heart, alignRight = true)
            label(m, "${(lo + 5).toInt()}", size.width, h - 14.dp.toPx(), Palette.Heart, alignRight = true)
        }
        timeAxis(m, from, to, 0f, w, h + 3.dp.toPx(), labelColor)
    }
}

/** Thin band with the state of every moment, to put under a time chart with the same [from]..[to]. */
@Composable
fun DayStrip(
    samples: List<ActivitySample>,
    stages: Map<Long, SleepStage>,
    from: Long,
    to: Long,
    modifier: Modifier = Modifier.fillMaxWidth().height(10.dp),
    leftInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val colors = stateColors()
    val byMinute = remember(samples) { samples.associateBy { it.timestamp / 60 } }
    Canvas(modifier) {
        if (to <= from) return@Canvas
        val left = leftInset.toPx()
        val w = size.width - left
        val bucket = maxOf(60L, ((to - from) / (w / 2f)).toLong() / 60 * 60)
        var t = from / bucket * bucket
        while (t < to) {
            val list = ArrayList<ActivitySample>()
            var minute = t / 60
            while (minute < (t + bucket) / 60) {
                byMinute[minute]?.let { list += it }
                minute++
            }
            val st = dayState(list, stages)
            if (st != DayState.NO_DATA) {
                val x0 = left + ((t - from).toFloat() / (to - from) * w).coerceAtLeast(0f)
                val x1 = left + ((t + bucket - from).toFloat() / (to - from) * w).coerceAtMost(w)
                if (x1 > x0) drawRect(colors.getValue(st), Offset(x0, 0f), Size(x1 - x0, size.height))
            }
            t += bucket
        }
    }
}

/** One color per minute for [LineChart]'s strip: activity, sleep or not worn. */
@Composable
fun minuteStrip(samples: List<ActivitySample>, stages: Map<Long, SleepStage>): List<Pair<Long, Color>> {
    val colors = stateColors()
    return remember(samples, stages, colors) {
        samples.mapNotNull { s ->
            val st = dayState(listOf(s), stages)
            if (st == DayState.NO_DATA) null else s.timestamp / 60 * 60 to colors.getValue(st)
        }
    }
}
