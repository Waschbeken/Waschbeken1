package de.waschbeken.heliolink.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlinx.coroutines.delay

// What all charts share: axes and labels, zoom by two fingers, holding a finger for the values.

/** One line of a [LineChart]: points as (epoch seconds, value). */
class Series(val points: List<Pair<Long, Float>>, val color: Color, val fill: Boolean = false, val width: Float = 2.5f)

/** Text drawing that never fails on tight space: the text is measured first and placed freely. */
internal fun DrawScope.label(m: TextMeasurer, text: String, x: Float, y: Float, color: Color, alignRight: Boolean = false, center: Boolean = false) {
    val layout = m.measure(text, TextStyle(color = color, fontSize = 10.sp))
    val w = layout.size.width.toFloat()
    val px = when {
        alignRight -> x - w
        center -> x - w / 2
        else -> x
    }.coerceIn(0f, max(0f, size.width - w))
    drawText(layout, topLeft = Offset(px, y.coerceIn(0f, max(0f, size.height - layout.size.height))))
}

internal fun niceStep(range: Float, lines: Int = 4): Float {
    val raw = range / lines
    val steps = listOf(1f, 2f, 2.5f, 5f, 10f, 20f, 25f, 50f, 100f, 200f, 250f, 500f, 1000f, 2000f, 2500f, 5000f, 10000f)
    return steps.firstOrNull { it >= raw } ?: raw
}

/** Time axis labels below a chart area of width [w] starting at [left]; the step follows the zoom. */
internal fun DrawScope.timeAxis(m: TextMeasurer, from: Long, to: Long, left: Float, w: Float, y: Float, color: Color) {
    if (to <= from) return
    val zone = ZoneId.systemDefault()
    val span = to - from
    if (span > 36 * 3600) {
        // Several days: one label per day (or every few days), counted in local days so a change to or
        // from summer time does not move the ticks to 23:00. Up to a week the weekday under the middle
        // of the day, otherwise the date at midnight.
        var d = Instant.ofEpochSecond(from).atZone(zone).toLocalDate()
        val last = Instant.ofEpochSecond(to).atZone(zone).toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(d, last) + 1
        val week = days <= 8
        val every = maxOf(1L, (days + 6) / 7)
        var i = 0L
        while (!d.isAfter(last)) {
            val t = if (week) d.atTime(12, 0).atZone(zone).toEpochSecond() else d.atStartOfDay(zone).toEpochSecond()
            if (i % every == 0L && t in from..to) {
                val txt = if (week) d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.GERMANY).take(2)
                else "${d.dayOfMonth}.${d.monthValue}."
                label(m, txt, left + (t - from).toFloat() / span * w, y, color, center = true)
            }
            d = d.plusDays(1)
            i++
        }
        return
    }
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
internal fun DrawScope.tooltip(m: TextMeasurer, lines: List<Pair<String, Color>>, x: Float, bg: Color, top: Float = 0f) {
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

internal fun timeText(t: Long, span: Long): String {
    val local = Instant.ofEpochSecond(t).atZone(ZoneId.systemDefault())
    return if (span < 2 * 3600) "%02d:%02d:%02d".format(local.hour, local.minute, local.second) else "%02d:%02d".format(local.hour, local.minute)
}

/** "45 min" or "2 h 05 min". */
fun spanText(seconds: Long): String {
    val m = (seconds + 30) / 60
    return if (m >= 60) "%d h %02d min".format(m / 60, m % 60) else "$m min"
}

/** The point closest to [t] in a list sorted by time, if not further away than [maxDistance]. */
internal fun nearest(points: List<Pair<Long, Float>>, t: Long, maxDistance: Long): Pair<Long, Float>? {
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
internal fun DrawScope.eventMarks(m: TextMeasurer, events: List<Pair<Long, String>>, x: (Long) -> Float, from: Long, to: Long, left: Float, right: Float, h: Float, color: Color) {
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
internal fun visiblePoints(points: List<Pair<Long, Float>>, from: Long, to: Long, columns: Int, margin: Long): List<Pair<Long, Float>> {
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
