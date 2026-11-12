package de.waschbeken.heliolink.ui.training

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.waschbeken.heliolink.core.HeartRateZones
import de.waschbeken.heliolink.core.Track
import de.waschbeken.heliolink.core.TrackPoint
import de.waschbeken.heliolink.data.HrPoint
import de.waschbeken.heliolink.data.Workout
import de.waschbeken.heliolink.service.LiveWorkout
import de.waschbeken.heliolink.service.RouteTracker
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.StatTile
import de.waschbeken.heliolink.ui.formatDuration
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Sports measured in km/h instead of minutes per kilometre. */
private fun usesSpeed(sport: String) = sport == "Radfahren"

/** "5:32 /km" or "24,3 km/h" for a stretch, "–" when it is too short to say. */
internal fun paceOrSpeed(sport: String, metres: Double, seconds: Long): String = when {
    usesSpeed(sport) -> if (metres < 50 || seconds <= 0) "–" else String.format(java.util.Locale.GERMANY, "%.1f km/h", Track.kmh(metres, seconds))
    else -> Track.pace(metres, seconds)?.let { "${Track.paceText(it)} /km" } ?: "–"
}

/**
 * "Strecke mit Handy-GPS" on the start card. Switching it on asks for the precise location once;
 * a hint with a button shows when the phone's location is switched off.
 */
@Composable
internal fun GpsChoice(selected: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        onChange(granted)
        if (!granted) Toast.makeText(context, "Ohne genauen Standort kann HelioLink keine Strecke aufzeichnen", Toast.LENGTH_LONG).show()
    }
    FilterChip(
        selected = selected,
        onClick = {
            when {
                selected -> onChange(false)
                RouteTracker.permitted(context) -> onChange(true)
                else -> launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        },
        label = { Text("Strecke mit Handy-GPS") },
        leadingIcon = { Icon(Icons.Default.MyLocation, contentDescription = null) },
    )
    if (!selected) return
    // The location can be switched on in the quick settings any time: re-check while the page is visible.
    var gpsOn by remember { mutableStateOf(RouteTracker.gpsOn(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                gpsOn = RouteTracker.gpsOn(context)
                delay(2_000)
            }
        }
    }
    if (!gpsOn) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Der Standort ist am Handy ausgeschaltet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                }
            }) { Text("Einschalten") }
        }
    }
}

/** Distance, pace and the state of the GPS while a workout with a route runs. */
@Composable
internal fun LiveRoute(live: LiveWorkout, now: Long) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("Strecke", Track.distanceText(live.distance), Modifier.weight(1f))
        StatTile(if (usesSpeed(live.sport)) "Ø Tempo" else "Ø Pace", paceOrSpeed(live.sport, live.distance, now - live.start), Modifier.weight(1f))
    }
    val accuracy = live.gpsAccuracy
    Text(
        when {
            accuracy == null -> "GPS sucht noch – am besten unter freiem Himmel"
            now - live.gpsAt > 20 -> "GPS-Signal verloren"
            accuracy > Track.MAX_ACCURACY -> "GPS ungenau (±${accuracy.roundToInt()} m) – wird noch nicht gezählt"
            else -> "GPS ±${accuracy.roundToInt()} m"
        },
        style = MaterialTheme.typography.labelSmall,
    )
}

/** Color of every stretch of the route: the heart rate zone at that time, [fallback] without a value. */
private fun zoneColors(track: List<TrackPoint>, hr: List<HrPoint>, maxHr: Int, rest: Int?, fallback: Color): List<Color> {
    var j = 0
    return track.map { p ->
        while (j + 1 < hr.size && hr[j + 1].timestamp <= p.timestamp) j++
        val bpm = hr.getOrNull(j)?.takeIf { it.timestamp <= p.timestamp && p.timestamp - it.timestamp <= 30 }?.bpm
        val zone = bpm?.let { HeartRateZones.zoneOf(it, maxHr, rest) } ?: -1
        if (zone >= 0) Palette.zones[zone] else fallback
    }
}

/**
 * The route drawn north up in true proportions, colored by heart rate zone, with a dot at every
 * kilometre, the start (green) and the end (dark) and a scale. No map tiles: nothing is loaded
 * from the internet, so nobody learns where you were.
 */
@Composable
internal fun RouteMap(track: List<TrackPoint>, hr: List<HrPoint>, maxHr: Int, rest: Int?, modifier: Modifier = Modifier.fillMaxWidth().height(280.dp)) {
    val fallback = MaterialTheme.colorScheme.primary
    val colors = remember(track, hr, maxHr, rest) { zoneColors(track, hr, maxHr, rest, fallback) }
    val sums = remember(track) { Track.cumulative(track) }
    val surface = MaterialTheme.colorScheme.surfaceContainerLowest
    val ink = MaterialTheme.colorScheme.onSurface
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    val m = rememberTextMeasurer()
    Box(modifier.background(surface, RoundedCornerShape(14.dp))) {
        Canvas(Modifier.fillMaxWidth().height(280.dp)) {
            val pad = 22.dp.toPx()
            val xy = Track.project(track, size.width, size.height, pad)
            if (xy.size < 2) return@Canvas
            val stroke = 4.dp.toPx()
            for (i in 1 until xy.size) {
                drawLine(colors[i], Offset(xy[i - 1].first, xy[i - 1].second), Offset(xy[i].first, xy[i].second), stroke, cap = StrokeCap.Round)
            }
            // a numbered dot at every full kilometre
            var next = 1000.0
            val small = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ink)
            for (i in 1 until xy.size) {
                while (sums[i] >= next) {
                    val span = sums[i] - sums[i - 1]
                    val f = if (span <= 0) 0f else ((next - sums[i - 1]) / span).toFloat()
                    val c = Offset(xy[i - 1].first + (xy[i].first - xy[i - 1].first) * f, xy[i - 1].second + (xy[i].second - xy[i - 1].second) * f)
                    drawCircle(surface, 8.dp.toPx(), c)
                    drawCircle(faint, 8.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    val text = m.measure("${(next / 1000).toInt()}", small)
                    drawText(text, topLeft = Offset(c.x - text.size.width / 2f, c.y - text.size.height / 2f))
                    next += 1000.0
                }
            }
            val start = Offset(xy.first().first, xy.first().second)
            val end = Offset(xy.last().first, xy.last().second)
            drawCircle(Color.White, 7.dp.toPx(), end)
            drawCircle(ink, 5.dp.toPx(), end)
            drawCircle(Color.White, 7.dp.toPx(), start)
            drawCircle(Color(0xFF46A758), 5.dp.toPx(), start)

            // scale: the longest round length that fits into a third of the width
            val perPixel = Track.metresPerPixel(track, size.width, size.height, pad)
            if (perPixel > 0) {
                val length = listOf(5000, 2000, 1000, 500, 200, 100, 50, 20, 10).firstOrNull { it / perPixel <= size.width / 3 } ?: 10
                val px = (length / perPixel).toFloat()
                val y = size.height - 10.dp.toPx()
                val x = 12.dp.toPx()
                drawLine(faint, Offset(x, y), Offset(x + px, y), 2.dp.toPx())
                drawLine(faint, Offset(x, y - 4.dp.toPx()), Offset(x, y + 1.dp.toPx()), 2.dp.toPx())
                drawLine(faint, Offset(x + px, y - 4.dp.toPx()), Offset(x + px, y + 1.dp.toPx()), 2.dp.toPx())
                val label = m.measure(if (length >= 1000) "${length / 1000} km" else "$length m", TextStyle(fontSize = 10.sp, color = faint))
                drawText(label, topLeft = Offset(x + px + 6.dp.toPx(), y - label.size.height / 2f))
            }
            val north = m.measure("N ↑", TextStyle(fontSize = 10.sp, color = faint))
            drawText(north, topLeft = Offset(size.width - north.size.width - 10.dp.toPx(), 8.dp.toPx()))
        }
    }
}

/** Everything about a recorded route on the page of a workout: the drawing, distance and pace, the kilometres. */
@Composable
internal fun RoutePanels(w: Workout, track: List<TrackPoint>, hr: List<HrPoint>, maxHr: Int, rest: Int?) {
    val metres = remember(track) { Track.length(track) }
    val moving = remember(track) { Track.movingSeconds(track) }
    val ascent = remember(track) { Track.ascent(track) }
    val splits = remember(track, hr) { Track.splits(track, hr.map { it.timestamp to it.bpm }) }
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Panel {
        Text("Strecke", style = MaterialTheme.typography.titleMedium)
        RouteMap(track, hr, maxHr, rest)
        Text("Farbe = Pulszone · Punkte = Kilometer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("Strecke", Track.distanceText(metres), Modifier.weight(1f))
        // Breaks (traffic lights, a rest) do not slow the pace down.
        StatTile(if (usesSpeed(w.sport)) "Ø Tempo" else "Ø Pace", paceOrSpeed(w.sport, metres, moving), Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("In Bewegung", formatDuration(moving), Modifier.weight(1f))
        StatTile("Höhenmeter", if (track.any { it.altitude != null }) "↑ ${ascent.roundToInt()} m" else "–", Modifier.weight(1f))
    }
    if (splits.size >= 2) {
        Panel {
            Text("Kilometer", style = MaterialTheme.typography.titleMedium)
            val fastest = splits.filter { it.metres >= 500 }.minOfOrNull { it.pace } ?: splits.minOf { it.pace }
            splits.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (s.metres >= 995) "${s.number}" else "${s.number}*",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.width(30.dp),
                    )
                    Text(
                        paceOrSpeed(w.sport, s.metres, s.seconds.toLong()),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (s.pace == fastest) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.width(92.dp),
                    )
                    // the faster the kilometre, the longer its bar
                    Box(Modifier.weight(1f)) {
                        Box(
                            Modifier.fillMaxWidth((fastest.toFloat() / s.pace).coerceIn(0.05f, 1f)).height(10.dp)
                                .background(if (s.pace == fastest) Palette.Steps else Palette.Steps.copy(alpha = 0.45f), RoundedCornerShape(5.dp)),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        s.avgHr?.let { "Ø $it" } ?: "",
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.Heart,
                        modifier = Modifier.width(44.dp),
                    )
                }
            }
            if (splits.last().metres < 995) {
                Text(
                    "* letzter Abschnitt: ${splits.last().metres.roundToInt()} m",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    }
}
