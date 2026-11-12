package de.waschbeken.heliolink.ui.metrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import de.waschbeken.heliolink.core.Buckets
import de.waschbeken.heliolink.ui.StackedBarChart
import de.waschbeken.heliolink.ui.TrendChart
import de.waschbeken.heliolink.ui.describe
import de.waschbeken.heliolink.ui.minuteStrip
import de.waschbeken.heliolink.ui.rememberTimeZoom
import androidx.compose.material3.TextButton
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.waschbeken.heliolink.core.Aggregates
import de.waschbeken.heliolink.core.HeartRateZones
import de.waschbeken.heliolink.data.UserProfile
import de.waschbeken.heliolink.ui.BarChart
import de.waschbeken.heliolink.ui.German
import de.waschbeken.heliolink.ui.LineChart
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.RangeChart
import de.waschbeken.heliolink.ui.Series
import de.waschbeken.heliolink.ui.StackedBar
import de.waschbeken.heliolink.ui.StatTile
import de.waschbeken.heliolink.ui.dbValue
import de.waschbeken.heliolink.ui.formatMinutes
import java.time.ZoneId
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// The heart rate page: today with zones and the live value, longer periods, the rhythm check.

/** Heart rhythm checks of the last 30 days (from the beat intervals while the pulse is streamed). */
@Composable
internal fun RhythmPanel(dataVersion: Int) {
    val rows by dbValue(dataVersion) { db ->
        val now = System.currentTimeMillis() / 1000
        db.rhythm(now - 30 * 86_400L, now + 60)
    }
    val list = rows ?: return
    Panel {
        Text("Herzrhythmus", style = MaterialTheme.typography.titleMedium)
        if (list.isEmpty()) {
            Text(
                "Noch nicht geprüft – braucht Live-Puls in Ruhe.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Panel
        }
        val irregular = list.filter { it.irregular }
        Text(
            if (irregular.isEmpty()) "Regelmäßig" else "${irregular.size} von ${list.size} Abschnitten unregelmäßig",
            style = MaterialTheme.typography.headlineSmall,
            color = if (irregular.isEmpty()) Color(0xFF30A46C) else MaterialTheme.colorScheme.error,
        )
        Text(
            "${list.size} Abschnitte à 64 Schläge in den letzten 30 Tagen geprüft" +
                (irregular.lastOrNull()?.let { " · zuletzt unregelmäßig am " + java.time.Instant.ofEpochSecond(it.timestamp).atZone(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d.M. 'um' HH:mm")) } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (irregular.isNotEmpty()) {
            Text(
                "Der optische Sensor stört sich an Bewegung und lockerem Sitz – einzelne Treffer heißen wenig. Kommt es öfter vor oder " +
                    "spürst du Herzstolpern, Schwindel oder Atemnot, lass es ärztlich mit einem EKG prüfen. Keine Diagnose.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Big number with a caption, used at the top of the heart rate pages. */
@Composable
internal fun HeartHero(value: String, unit: String, caption: String, side: List<Pair<String, String>>, onClick: (() -> Unit)? = null) {
    Card(
        onClick = onClick ?: {},
        enabled = onClick != null,
        colors = CardDefaults.cardColors(
            containerColor = Palette.Heart.copy(alpha = 0.13f),
            disabledContainerColor = Palette.Heart.copy(alpha = 0.13f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = Palette.Heart, modifier = Modifier.padding(bottom = 10.dp).size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(value, fontSize = 52.sp, fontWeight = FontWeight.Bold, color = Palette.Heart)
                    Spacer(Modifier.width(4.dp))
                    Text(unit, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
                }
                Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                side.forEach { (label, v) ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text(v, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Zone list with bpm range, minutes and a bar for the share. */
@Composable
internal fun ZonePanel(title: String, minutes: IntArray, lower: IntArray, daily: Boolean = false) {
    val total = minutes.sum()
    if (total == 0) return
    Panel {
        Text(title, style = MaterialTheme.typography.titleMedium)
        StackedBar(minutes.mapIndexed { i, v -> v.toFloat() to Palette.zones[i] }, Modifier.fillMaxWidth().height(12.dp))
        for (i in 4 downTo 0) {
            val v = minutes[i]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Palette.zones[i], CircleShape))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Zone ${i + 1} · ${HeartRateZones.names[i]}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (i < 4) "${lower[i]}–${lower[i + 1] - 1} bpm" else "ab ${lower[i]} bpm",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(formatMinutes(v) + if (daily) " /Tag" else "", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Text("${v * 100 / total} %", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(40.dp))
            }
        }
    }
}

@Composable
internal fun HeartDay(d: MetricData, profile: UserProfile, live: Int?, onRecord: (() -> Unit)?) {
    val zone = ZoneId.systemDefault()
    val rest = profile.zoneRest(d.strapResting)
    val lower = zoneLower(profile, rest)
    val points = d.hr
    var details by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (points.isEmpty()) {
            if (onRecord != null) {
                HeartHero("–", "bpm", "Tippen, um den Puls jede Sekunde aufzuzeichnen", emptyList(), onRecord)
            }
            Panel { Text("Keine Pulswerte an diesem Tag.", style = MaterialTheme.typography.bodyMedium) }
            return@Column
        }
        val values = points.map { it.second }
        val minP = points.minBy { it.second }
        val maxP = points.maxBy { it.second }
        val avg = values.average().roundToInt()
        val restingToday = d.strapResting ?: d.totals[d.date]?.hrMin
        val last = points.last()
        HeartHero(
            value = (live ?: last.second.toInt()).toString(),
            unit = "bpm",
            caption = (if (live != null) "Jetzt" else "Zuletzt um ${clock(last.first)}") +
                if (onRecord != null) " · Tippen zum Aufzeichnen" else "",
            side = listOf("Ruhepuls" to (restingToday?.let { "$it" } ?: "–"), "Ø Tag" to "$avg"),
            onClick = onRecord,
        )
        Panel {
            val dayZoom = rememberTimeZoom(d.start, d.end)
            LineChart(
                listOf(Series(points, Palette.Heart, fill = true, width = 2f)), dayZoom.start, dayZoom.end,
                modifier = Modifier.fillMaxWidth().height(260.dp), zoom = dayZoom, unit = "bpm",
                areas = (0..4).map { i -> Triple(lower[i].toFloat(), if (i < 4) lower[i + 1].toFloat() else 260f, Palette.zones[i]) },
                markers = listOf(
                    Triple(minP.first, minP.second, "${minP.second.toInt()}"),
                    Triple(maxP.first, maxP.second, "${maxP.second.toInt()}"),
                ),
                strip = minuteStrip(d.activity, d.stages),
                events = d.events,
            )
            if (dayZoom.zoomed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${clock(dayZoom.start)} – ${clock(dayZoom.end)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { dayZoom.reset() }) { Text("Ganzer Tag") }
                }
            }
        }
        Panel {
            Row {
                de.waschbeken.heliolink.ui.Metric("Ruhe", restingToday?.toString() ?: "–", Modifier.weight(1f))
                de.waschbeken.heliolink.ui.Metric("Ø", "$avg", Modifier.weight(1f))
                de.waschbeken.heliolink.ui.Metric("Min ${clock(minP.first)}", "${minP.second.toInt()}", Modifier.weight(1f))
                de.waschbeken.heliolink.ui.Metric("Max ${clock(maxP.first)}", "${maxP.second.toInt()}", Modifier.weight(1f), color = Palette.Heart)
            }
            val diff = if (restingToday != null && d.restingBaseline != null) restingToday - d.restingBaseline else null
            val lines = listOfNotNull(
                diff?.let {
                    when {
                        it <= -2 -> "Ruhepuls ${signed(it)} unter deinem Schnitt (${d.restingBaseline})"
                        it >= 3 -> "Ruhepuls ${signed(it)} über deinem Schnitt (${d.restingBaseline})"
                        else -> "Ruhepuls wie üblich (Ø ${d.restingBaseline})"
                    }
                },
                d.sleepHr?.let { (a, mn) -> if (a != null) "Im Schlaf Ø $a, tiefster ${mn ?: "–"} bpm" else null },
            )
            if (lines.isNotEmpty()) Text(lines.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        d.totals[d.date]?.let { ZonePanel("Zeit in Pulszonen", it.zones, lower) }
        if (d.workouts.isNotEmpty()) {
            val sections = de.waschbeken.heliolink.ui.rememberSections()
            val open = sections.isOpen("heart-workouts", default = true)
            de.waschbeken.heliolink.ui.CollapsibleHeader("Aufzeichnungen und Trainings", open, count = d.workouts.size) {
                sections.toggle("heart-workouts", default = true)
            }
            if (open) d.workouts.forEach { w ->
                Panel {
                    Text("${w.sport} · ${clock(w.start)}", style = MaterialTheme.typography.titleSmall)
                    Text("${formatMinutes(((w.end - w.start) / 60).toInt())} · Ø ${w.avgHr} · max. ${w.maxHr} bpm", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        TextButton(onClick = { details = !details }) { Text(if (details) "Weniger anzeigen" else "Mehr Details: Stundenverlauf und Verteilung") }
        if (details) {
            val byHour = points.groupBy { java.time.Instant.ofEpochSecond(it.first).atZone(zone).hour }
            Panel {
                Text("Stundenverlauf", style = MaterialTheme.typography.titleMedium)
                RangeChart(
                    List(24) { h ->
                        val v = byHour[h]?.map { it.second }
                        if (v.isNullOrEmpty()) Triple(null, null, null) else Triple(v.min(), v.average().toFloat(), v.max())
                    },
                    List(24) { if (it % 3 == 0) "$it" else "" }, Palette.Heart,
                )
                Text("Balken: niedrigster bis höchster Wert der Stunde, Punkt: Durchschnitt", style = MaterialTheme.typography.labelSmall)
            }
            val bins = (40 until 200 step 10).toList()
            val counts = bins.map { b -> values.count { it >= b && it < b + 10 }.toFloat() }
            val first = counts.indexOfFirst { it > 0 }.coerceAtLeast(0)
            val lastBin = counts.indexOfLast { it > 0 }.coerceAtLeast(first)
            Panel {
                Text("Verteilung", style = MaterialTheme.typography.titleMedium)
                BarChart(
                    counts.subList(first, lastBin + 1), Palette.Heart,
                    colors = bins.subList(first, lastBin + 1).map { b -> HeartRateZones.zoneOf(b + 5, profile.maxHeartRate, rest).let { if (it >= 0) Palette.zones[it] else Palette.Heart.copy(alpha = 0.5f) } },
                    labels = bins.subList(first, lastBin + 1).mapIndexed { i, b -> if (i % 2 == 0) "$b" else "" },
                )
                val common = bins[counts.indices.maxBy { counts[it] }]
                Text("Am häufigsten: $common–${common + 9} bpm", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun HeartRange(d: MetricData, profile: UserProfile) {
    val rest = profile.zoneRest(d.strapResting)
    val lower = zoneLower(profile, rest)
    val labels = d.buckets.map { it.label }
    val days = d.totals.values.filter { it.hrAvg != null }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (days.isEmpty()) {
            Panel { Text("Keine Pulswerte in diesem Zeitraum.", style = MaterialTheme.typography.bodyMedium) }
            return@Column
        }
        val restingByDay = perDay(d.values)
        val restingValues = restingByDay.values.flatten()
        val restingAvg = restingValues.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        val restingTrend = Buckets.average(d.buckets) { day -> restingByDay[day]?.average() }
        val firstR = restingTrend.firstOrNull { it != null }
        val lastR = restingTrend.lastOrNull { it != null }
        val change = if (firstR != null && lastR != null && restingTrend.count { it != null } >= 2) (lastR - firstR).roundToInt() else null
        HeartHero(
            value = restingAvg?.toString() ?: (Aggregates.avg(days.map { it.hrAvg })?.toString() ?: "–"),
            unit = "bpm",
            caption = if (restingAvg != null) "Ø Ruhepuls · ${d.period.label}" else "Ø Puls · ${d.period.label}",
            side = listOfNotNull(
                change?.let { "Veränderung" to "${signed(it)} bpm" },
                "Ø Puls" to (Aggregates.avg(days.map { it.hrAvg })?.toString() ?: "–"),
                "Tage mit Daten" to "${days.size}",
            ),
        )
        if (restingTrend.any { it != null }) {
            Panel {
                Text("Ruhepuls", style = MaterialTheme.typography.titleMedium)
                TrendChart(restingTrend, labels, Palette.Heart, baseline = d.restingBaseline?.toFloat(), tooltip = { d.buckets[it].describe() })
                Text(
                    (if (d.restingBaseline != null) "Gestrichelt: dein Schnitt davor (${d.restingBaseline} bpm). " else "") +
                        "Sinkt der Ruhepuls über Wochen, wirst du fitter.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Panel {
            Text("Puls ${d.period.perBar}", style = MaterialTheme.typography.titleMedium)
            RangeChart(
                d.buckets.map { b ->
                    val inB = days.filter { it.date in b }
                    if (inB.isEmpty()) Triple(null, null, null)
                    else Triple(inB.mapNotNull { it.hrMin }.minOrNull()?.toFloat(), inB.mapNotNull { it.hrAvg }.average().toFloat(), inB.mapNotNull { it.hrMax }.maxOrNull()?.toFloat())
                },
                labels, Palette.Heart, tooltip = { d.buckets[it].describe() },
            )
            Text("Balken: niedrigster bis höchster Wert, Punkt: Durchschnitt", style = MaterialTheme.typography.labelSmall)
        }
        Panel {
            Text("Intensive Minuten (Zone 2–5)", style = MaterialTheme.typography.titleMedium)
            StackedBarChart(
                d.buckets.map { b ->
                    val inB = days.filter { it.date in b }
                    if (inB.isEmpty()) emptyList() else (1..4).map { z -> inB.sumOf { it.zones[z] }.toFloat() / inB.size to Palette.zones[z] }
                },
                labels,
                tooltip = { i ->
                    val b = d.buckets[i]
                    val inB = days.filter { it.date in b }
                    listOf(b.describe()) + if (inB.isEmpty()) listOf("keine Werte") else (1..4).map { z -> "Zone ${z + 1}: ${formatMinutes(inB.sumOf { it.zones[z] } / inB.size)}/Tag" }
                },
            )
            Text("Ø Minuten pro Tag", style = MaterialTheme.typography.labelSmall)
        }
        val zoneSums = IntArray(5) { z -> days.sumOf { it.zones[z] } / days.size.coerceAtLeast(1) }
        ZonePanel("Zeit in Pulszonen", zoneSums, lower, daily = true)
        val record = days.maxBy { it.hrMax ?: 0 }
        val calm = days.filter { it.hrMin != null }.minByOrNull { it.hrMin!! }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Höchster Puls", "${record.hrMax ?: "–"} bpm", Modifier.weight(1f), caption = record.date.format(java.time.format.DateTimeFormatter.ofPattern("d. MMM yyyy", German)))
            StatTile("Tiefster Puls", calm?.hrMin?.let { "$it bpm" } ?: "–", Modifier.weight(1f), caption = calm?.date?.format(java.time.format.DateTimeFormatter.ofPattern("d. MMM yyyy", German)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Intensiv Ø", "${formatMinutes(days.sumOf { it.intenseMinutes } / days.size)} /Tag", Modifier.weight(1f))
            StatTile("Tiefster Ruhepuls", restingValues.minOrNull()?.let { "${it.toInt()} bpm" } ?: "–", Modifier.weight(1f))
        }
    }
}
