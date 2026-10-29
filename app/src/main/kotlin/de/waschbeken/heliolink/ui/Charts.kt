package de.waschbeken.heliolink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import de.waschbeken.heliolink.core.ActivitySample
import de.waschbeken.heliolink.core.SleepStage
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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

/** Time axis labels below a chart area of width [w] starting at [left]; the step follows the zoom. */
private fun DrawScope.timeAxis(m: TextMeasurer, from: Long, to: Long, left: Float, w: Float, y: Float, color: Color) {
    if (to <= from) return
    val zone = ZoneId.systemDefault()
    val span = to - from
    val steps = longArrayOf(60, 120, 300, 600, 900, 1800, 3600, 7200, 10800, 21600, 43200, 86400)
    val step = steps.firstOrNull { span / it <= 6 } ?: 86400
    val midnight = Instant.ofEpochSecond(from).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()
    var t = midnight + ((from - midnight + step - 1) / step) * step
    while (t <= to) {
        val local = Instant.ofEpochSecond(t).atZone(zone)
        val txt = if (step >= 3600) "%02d".format(local.hour) else "%02d:%02d".format(local.hour, local.minute)
        val px = left + (t - from).toFloat() / span * w
        label(m, txt, px, y, color, center = true)
        t += step
    }
}

/**
 * Visible part of a time chart. Pinch with two fingers to zoom, drag sideways to move once zoomed,
 * double tap to zoom in or back out – like the charts in Gadgetbridge.
 */
@Stable
class TimeZoom(val from: Long, val to: Long, private val minSpan: Long = 600) {
    var start by mutableLongStateOf(from)
        private set
    var end by mutableLongStateOf(to)
        private set
    val zoomed get() = end - start < to - from - 1

    fun reset() {
        start = from
        end = to
    }

    /** Zooms by [factor] around [focus] (0 = left edge, 1 = right edge). */
    fun zoom(factor: Float, focus: Float) {
        if (to <= from || factor <= 0f) return
        val span = (end - start).toDouble()
        val newSpan = (span / factor).coerceIn(minOf(minSpan, to - from).toDouble(), (to - from).toDouble())
        val center = start + span * focus
        val s = (center - newSpan * focus).coerceIn(from.toDouble(), to - newSpan)
        start = s.toLong()
        end = (s + newSpan).toLong()
    }

    /** Moves by [fraction] of the visible width (positive = towards earlier). */
    fun pan(fraction: Float) {
        val span = end - start
        val s = (start - (span * fraction).toLong()).coerceIn(from, to - span)
        start = s
        end = s + span
    }
}

@Composable
fun rememberTimeZoom(from: Long, to: Long, minSpan: Long = 600): TimeZoom = remember(from, to) { TimeZoom(from, to, minSpan) }

/** Gestures for [TimeZoom]; vertical drags stay with the list so the page still scrolls. */
fun Modifier.timeZoom(z: TimeZoom): Modifier = this
    .pointerInput(z) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false)
            var horizontal = false
            var decided = false
            var total = Offset.Zero
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                val held = event.changes.first().uptimeMillis - first.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                if (event.changes.any { it.isConsumed } || (pressed == 1 && !decided && held)) {
                    // the finger was held to inspect values: the chart stays where it is
                    decided = true
                    horizontal = false
                } else if (pressed >= 2) {
                    val zoom = event.calculateZoom()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (zoom != 1f && centroid != Offset.Unspecified) z.zoom(zoom, (centroid.x / size.width).coerceIn(0f, 1f))
                    val pan = event.calculatePan()
                    if (pan.x != 0f) z.pan(pan.x / size.width)
                    event.changes.forEach { it.consume() }
                    decided = true
                    horizontal = true
                } else if (pressed == 1 && z.zoomed) {
                    val pan = event.calculatePan()
                    if (!decided) {
                        total += pan
                        if (total.getDistance() > viewConfiguration.touchSlop) {
                            decided = true
                            horizontal = kotlin.math.abs(total.x) > kotlin.math.abs(total.y)
                        }
                    }
                    if (decided && horizontal) {
                        if (pan.x != 0f) z.pan(pan.x / size.width)
                        event.changes.forEach { it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .pointerInput(z) {
        detectTapGestures(onDoubleTap = { o -> if (z.zoomed) z.reset() else z.zoom(4f, (o.x / size.width).coerceIn(0f, 1f)) })
    }

/** Where a finger is held on a chart (long press); stays a few seconds after letting go. */
@Stable
class InspectState {
    var position by mutableStateOf<Offset?>(null)
    var holding by mutableStateOf(false)
}

@Composable
fun rememberInspect(): InspectState {
    val s = remember { InspectState() }
    LaunchedEffect(s.holding, s.position) {
        if (!s.holding && s.position != null) {
            delay(4_000)
            s.position = null
        }
    }
    return s
}

/**
 * Long press and hold to inspect: the position follows the finger. Taps and scrolling are left to
 * the page; after a long press the gesture is used up so a surrounding card does not react.
 */
fun Modifier.holdToInspect(s: InspectState): Modifier = pointerInput(s) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var stop = false
            while (!stop) {
                val e = awaitPointerEvent()
                val c = e.changes.firstOrNull { it.id == down.id }
                stop = c == null || !c.pressed || c.isConsumed || e.changes.count { it.pressed } > 1 ||
                    (c.position - down.position).getDistance() > viewConfiguration.touchSlop
            }
            true
        }
        if (endedEarly != null) return@awaitEachGesture
        s.holding = true
        s.position = down.position
        try {
            while (true) {
                val e = awaitPointerEvent()
                e.changes.forEach { it.consume() }
                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                s.position = c.position
            }
        } finally {
            s.holding = false
        }
    }
}

/** A small dark box with text lines (first line is the heading), placed above [x]. */
private fun DrawScope.tooltip(m: TextMeasurer, lines: List<Pair<String, Color>>, x: Float, bg: Color, top: Float = 0f) {
    if (lines.isEmpty()) return
    val layouts = lines.mapIndexed { i, (t, c) ->
        m.measure(t, TextStyle(color = c, fontSize = if (i == 0) 11.sp else 12.sp, fontWeight = if (i == 0) FontWeight.Normal else FontWeight.SemiBold))
    }
    val pad = 6.dp.toPx()
    val w = layouts.maxOf { it.size.width } + pad * 2
    val h = layouts.sumOf { it.size.height } + pad * 2
    val left = (x - w / 2).coerceIn(0f, max(0f, size.width - w))
    drawRoundRect(bg, Offset(left, top), Size(w, h), CornerRadius(8.dp.toPx(), 8.dp.toPx()))
    var y = top + pad
    layouts.forEach {
        drawText(it, topLeft = Offset(left + pad, y))
        y += it.size.height
    }
}

private fun timeText(t: Long, span: Long): String {
    val local = Instant.ofEpochSecond(t).atZone(ZoneId.systemDefault())
    return if (span < 2 * 3600) "%02d:%02d:%02d".format(local.hour, local.minute, local.second) else "%02d:%02d".format(local.hour, local.minute)
}

/** "45 min" or "2 h 05 min". */
fun spanText(seconds: Long): String {
    val m = (seconds + 30) / 60
    return if (m >= 60) "%d h %02d min".format(m / 60, m % 60) else "$m min"
}

/** The point closest to [t] in a list sorted by time, if not further away than [maxDistance]. */
private fun nearest(points: List<Pair<Long, Float>>, t: Long, maxDistance: Long): Pair<Long, Float>? {
    if (points.isEmpty()) return null
    var lo = 0
    var hi = points.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (points[mid].first < t) lo = mid + 1 else hi = mid
    }
    val candidates = listOfNotNull(points.getOrNull(lo), points.getOrNull(lo - 1))
    return candidates.minByOrNull { kotlin.math.abs(it.first - t) }?.takeIf { kotlin.math.abs(it.first - t) <= maxDistance }
}

/** Quick entries (caffeine, …) as small flags: a dashed line with the name at the top. */
private fun DrawScope.eventMarks(m: TextMeasurer, events: List<Pair<Long, String>>, x: (Long) -> Float, from: Long, to: Long, left: Float, right: Float, h: Float, color: Color) {
    var lastLabelEnd = -1f
    for ((t, name) in events.sortedBy { it.first }) {
        if (t < from || t > to) continue
        val px = x(t)
        if (px < left || px > right) continue
        drawLine(color.copy(alpha = 0.7f), Offset(px, 0f), Offset(px, h), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(px, 3.dp.toPx()))
        val layout = m.measure(name, TextStyle(color = color, fontSize = 9.sp, fontWeight = FontWeight.SemiBold))
        val lx = (px + 4.dp.toPx()).coerceAtMost(right - layout.size.width)
        if (lx > lastLabelEnd) {
            drawText(layout, topLeft = Offset(lx, 0f))
            lastLabelEnd = lx + layout.size.width + 4.dp.toPx()
        }
    }
}

/** Points inside [from]..[to] (plus [margin]); dense data is thinned to the min and max per pixel column. */
private fun visiblePoints(points: List<Pair<Long, Float>>, from: Long, to: Long, columns: Int, margin: Long): List<Pair<Long, Float>> {
    val sorted = if (points.zipWithNext().all { (a, b) -> a.first <= b.first }) points else points.sortedBy { it.first }
    val visible = sorted.filter { it.first in (from - margin)..(to + margin) }
    if (visible.size <= columns * 2 || columns <= 0 || to <= from) return visible
    val out = ArrayList<Pair<Long, Float>>(columns * 2)
    val perColumn = (to - from).toDouble() / columns
    var i = 0
    while (i < visible.size) {
        val col = ((visible[i].first - from) / perColumn).toLong()
        var lo = visible[i]
        var hi = visible[i]
        var j = i
        while (j < visible.size && ((visible[j].first - from) / perColumn).toLong() == col) {
            if (visible[j].second < lo.second) lo = visible[j]
            if (visible[j].second > hi.second) hi = visible[j]
            j++
        }
        if (lo.first <= hi.first) { out += lo; if (hi !== lo) out += hi } else { out += hi; out += lo }
        i = j
    }
    return out
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
    /** Colored areas between two values, e.g. heart rate zones (from, to, color). */
    areas: List<Triple<Float, Float, Color>> = emptyList(),
    /** Highlighted points with a short text, e.g. the minimum and maximum. */
    markers: List<Triple<Long, Float, String>> = emptyList(),
    /** Colored band under the chart, one color per minute (see [minuteStrip]). */
    strip: List<Pair<Long, Color>> = emptyList(),
    /** Makes the chart zoomable; [from] and [to] are then taken from it. */
    zoom: TimeZoom? = null,
    /** Unit shown with the values when holding a finger on the chart. */
    unit: String = "",
    /** Quick entries shown as flags (time, name). */
    events: List<Pair<Long, String>> = emptyList(),
) {
    val eventColor = MaterialTheme.colorScheme.tertiary
    val m = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val markerColor = MaterialTheme.colorScheme.onSurface
    val tipBg = MaterialTheme.colorScheme.inverseSurface
    val tipFg = MaterialTheme.colorScheme.inverseOnSurface
    val inspect = rememberInspect()
    // every time chart can be zoomed; without a shared zoom it gets its own
    val z = zoom ?: rememberTimeZoom(from, to, minSpan = minOf(600L, maxOf(60L, (to - from) / 4)))
    Canvas(modifier.timeZoom(z).holdToInspect(inspect)) {
        val from = z.start
        val to = z.end
        if (to <= from) return@Canvas
        val values = series.flatMap { s -> s.points.filter { it.first in from..to }.map { it.second } }
            .ifEmpty { series.flatMap { s -> s.points.map { it.second } } }
        if (values.isEmpty()) return@Canvas
        var lo = minY ?: values.min()
        var hi = maxY ?: values.max()
        if (hi - lo < 1e-3f) { lo -= 1; hi += 1 }
        val step = niceStep(hi - lo)
        lo = floor(lo / step) * step
        hi = ceil(hi / step) * step
        val axisH = 18.dp.toPx()
        val stripH = if (strip.isEmpty()) 0f else 10.dp.toPx()
        val left = m.measure(format(hi), TextStyle(fontSize = 10.sp)).size.width + 6.dp.toPx()
        val w = size.width - left
        val h = size.height - axisH - stripH
        fun x(t: Long) = left + (t - from).toFloat() / (to - from) * w
        if (strip.isNotEmpty()) {
            clipRect(left, h, size.width, h + stripH) {
                for ((t, c) in strip) {
                    if (t + 60 < from || t > to) continue
                    drawRect(c, Offset(x(t), h + 3.dp.toPx()), Size((x(t + 60) - x(t)).coerceAtLeast(1f), stripH - 3.dp.toPx()))
                }
            }
        }
        fun y(v: Float) = h - (v - lo) / (hi - lo) * h

        for ((a, b, c) in areas) {
            val top = y(minOf(b, hi))
            val bottom = y(maxOf(a, lo))
            if (bottom > top) drawRect(c.copy(alpha = 0.10f), Offset(left, top), Size(w, bottom - top))
        }
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
        timeAxis(m, from, to, left, w, h + stripH + 3.dp.toPx(), labelColor)

        clipRect(left, 0f, size.width, h) {
        for (s in series) {
            val path = Path()
            val fillPath = Path()
            var last: Long? = null
            var segmentStart = 0f
            var lastX = 0f
            for ((t, value) in visiblePoints(s.points, from, to, w.toInt(), gapSeconds)) {
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
        // zoomed in far enough: write the values next to the points, like Gadgetbridge
        val visibleRaw = series.sumOf { sr -> sr.points.count { it.first in from..to } }
        if (visibleRaw in 1..(w / 30.dp.toPx()).toInt()) {
            for (sr in series) {
                for ((t, value) in sr.points) {
                    if (t !in from..to) continue
                    drawCircle(sr.color, radius = 2.5.dp.toPx(), center = Offset(x(t), y(value)))
                    label(m, format(value), x(t), y(value) - 15.dp.toPx(), sr.color, center = true)
                }
            }
        }
        }
        if (events.isNotEmpty()) eventMarks(m, events, ::x, from, to, left, size.width, h, eventColor)
        inspect.position?.let { p ->
            val t = from + (((p.x - left) / w).coerceIn(0f, 1f) * (to - from)).toLong()
            val maxDistance = maxOf(gapSeconds / 2, ((to - from) / w * 24).toLong(), 60)
            val hits = series.mapNotNull { sr -> nearest(sr.points, t, maxDistance)?.let { sr to it } }
            val at = hits.firstOrNull()?.second?.first ?: t
            drawLine(markerColor.copy(alpha = 0.6f), Offset(x(at), 0f), Offset(x(at), h), 1.5f)
            hits.forEach { (sr, pt) -> drawCircle(sr.color, radius = 5.dp.toPx(), center = Offset(x(pt.first), y(pt.second))) }
            tooltip(
                m,
                listOf(timeText(at, to - from) to tipFg) + hits.map { (sr, pt) -> (format(pt.second) + if (unit.isNotEmpty()) " $unit" else "") to sr.color },
                x(at), tipBg,
            )
        }
        for ((t, value, text) in markers) {
            if (inspect.position != null || t < from || t > to) continue
            val c = Offset(x(t), y(value))
            drawCircle(markerColor, radius = 4.dp.toPx(), center = c)
            drawCircle(Color.White, radius = 2.dp.toPx(), center = c)
            val above = c.y > 18.dp.toPx()
            label(m, text, c.x, if (above) c.y - 17.dp.toPx() else c.y + 5.dp.toPx(), markerColor, center = true)
        }
    }
}

/**
 * Line over buckets (e.g. resting heart rate per week); null values leave a gap. [baseline] draws a
 * dashed reference line (e.g. your average).
 */
@Composable
fun TrendChart(
    values: List<Float?>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(160.dp),
    baseline: Float? = null,
    format: (Float) -> String = { it.toInt().toString() },
    /** Heading when holding a finger on entry i (e.g. the week). */
    tooltip: ((Int) -> String)? = null,
) {
    val m = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val tipBg = MaterialTheme.colorScheme.inverseSurface
    val tipFg = MaterialTheme.colorScheme.inverseOnSurface
    val inspect = rememberInspect()
    Canvas(modifier.holdToInspect(inspect)) {
        val known = values.filterNotNull() + listOfNotNull(baseline)
        if (known.isEmpty()) return@Canvas
        var lo = known.min()
        var hi = known.max()
        if (hi - lo < 1e-3f) { lo -= 1; hi += 1 }
        val step = niceStep(hi - lo)
        lo = floor(lo / step) * step
        hi = ceil(hi / step) * step
        val labelH = 16.dp.toPx()
        val left = m.measure(format(hi), TextStyle(fontSize = 10.sp)).size.width + 6.dp.toPx()
        val h = size.height - labelH
        val w = size.width - left
        val slot = w / values.size.coerceAtLeast(1)
        fun x(i: Int) = left + i * slot + slot / 2
        fun y(v: Float) = h - (v - lo) / (hi - lo) * h
        var v = lo
        while (v <= hi + step / 2) {
            drawLine(grid, Offset(left, y(v)), Offset(size.width, y(v)), 1f)
            label(m, format(v), left - 4.dp.toPx(), y(v) - 7.dp.toPx(), labelColor, alignRight = true)
            v += step
        }
        if (baseline != null) {
            drawLine(color.copy(alpha = 0.6f), Offset(left, y(baseline)), Offset(size.width, y(baseline)), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        val path = Path()
        var open = false
        values.forEachIndexed { i, value ->
            if (value == null) { open = false; return@forEachIndexed }
            if (!open) path.moveTo(x(i), y(value)) else path.lineTo(x(i), y(value))
            open = true
        }
        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        val dot = (slot * 0.18f).coerceIn(2.dp.toPx(), 4.dp.toPx())
        values.forEachIndexed { i, value -> if (value != null) drawCircle(color, radius = dot, center = Offset(x(i), y(value))) }
        labels.forEachIndexed { i, text -> if (text.isNotEmpty()) label(m, text, x(i), h + 2.dp.toPx(), labelColor, center = true) }
        if (values.size <= (w / 34.dp.toPx()).toInt() && inspect.position == null) {
            values.forEachIndexed { i, value -> if (value != null) label(m, format(value), x(i), y(value) - 16.dp.toPx(), color, center = true) }
        }
        inspect.position?.let { p ->
            val i = ((p.x - left) / slot).toInt().coerceIn(0, values.lastIndex)
            drawLine(labelColor.copy(alpha = 0.5f), Offset(x(i), 0f), Offset(x(i), h), 1.5f)
            val head = tooltip?.invoke(i) ?: labels.getOrNull(i).orEmpty()
            tooltip(m, listOfNotNull(head.takeIf { it.isNotBlank() }?.let { it to tipFg }, (values[i]?.let { format(it) } ?: "keine Werte") to color), x(i), tipBg)
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

/** Min–max range per day with a dot for the average (e.g. heart rate per day). */
@Composable
fun RangeChart(
    ranges: List<Triple<Float?, Float?, Float?>>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(160.dp),
    /** Heading when holding a finger on entry i (e.g. the date); values are added. */
    tooltip: ((Int) -> String)? = null,
) {
    val m = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val tipBg = MaterialTheme.colorScheme.inverseSurface
    val tipFg = MaterialTheme.colorScheme.inverseOnSurface
    val inspect = rememberInspect()
    Canvas(modifier.holdToInspect(inspect)) {
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
        inspect.position?.let { p ->
            val i = ((p.x - left) / slot).toInt().coerceIn(0, ranges.lastIndex)
            val (mn, avg, mx) = ranges[i]
            val cx = left + i * slot + slot / 2
            drawLine(labelColor.copy(alpha = 0.5f), Offset(cx, 0f), Offset(cx, h), 1.5f)
            val head = tooltip?.invoke(i) ?: labels.getOrNull(i).orEmpty()
            val body = if (mn == null || mx == null) "keine Werte" else "${mn.toInt()}–${mx.toInt()}" + (avg?.let { " · Ø ${it.roundToInt()}" } ?: "")
            tooltip(m, listOfNotNull(head.takeIf { it.isNotBlank() }?.let { it to tipFg }, body to color), cx, tipBg)
        }
    }
}

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
private fun stateColors(): Map<DayState, Color> = DayState.entries.associateWith { dayStateColor(it) }

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

private fun clockState(s: DayState) = when (s) {
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
