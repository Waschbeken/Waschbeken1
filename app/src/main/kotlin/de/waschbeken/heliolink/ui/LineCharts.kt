package de.waschbeken.heliolink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

// Values over time: heart rate, HRV, temperature … and their trends and ranges.

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
