package de.waschbeken.heliolink.ui.metrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import de.waschbeken.heliolink.core.Buckets
import de.waschbeken.heliolink.ui.TrendChart
import de.waschbeken.heliolink.ui.describe
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.BatteryForecast
import de.waschbeken.heliolink.core.ValueSample
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.data.Queries
import de.waschbeken.heliolink.data.UserProfile
import de.waschbeken.heliolink.ui.BarChart
import de.waschbeken.heliolink.ui.Heatmap
import de.waschbeken.heliolink.ui.LineChart
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.Period
import de.waschbeken.heliolink.ui.RangeChart
import de.waschbeken.heliolink.ui.SectionHeader
import de.waschbeken.heliolink.ui.Series
import de.waschbeken.heliolink.ui.StackedBar
import de.waschbeken.heliolink.ui.dbValue
import de.waschbeken.heliolink.ui.fmt
import de.waschbeken.heliolink.ui.stressColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// The other metric pages: steps, stress, SpO₂/HRV/temperature/breathing, PAI and battery, with their extra panels.

/** What stressed you: answers to "Was war los?" of the last 90 days, plus a way to note one now. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun StressTriggersPanel(dataVersion: Int) {
    val app = LocalContext.current.app
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    val counts by dbValue(dataVersion, reload) { db ->
        val now = System.currentTimeMillis() / 1000
        db.events(now - 90 * 86_400L, now + 60).map { it.tag }
            .filter { it.startsWith(de.waschbeken.heliolink.service.StressPrompt.PREFIX) }
            .groupingBy { it.removePrefix(de.waschbeken.heliolink.service.StressPrompt.PREFIX) }.eachCount()
            .entries.sortedByDescending { it.value }
    }
    Panel {
        Text("Deine Stress-Auslöser", style = MaterialTheme.typography.titleMedium)
        val list = counts.orEmpty()
        if (list.isEmpty()) {
            Text(
                "Noch nichts eingetragen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val max = list.first().value.toFloat()
            list.forEach { (reason, n) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(reason, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("$n×", style = MaterialTheme.typography.labelLarge)
                }
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { n / max }, color = Palette.Stress, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Text("Gerade gestresst? Tippe, was es ist:", style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (de.waschbeken.heliolink.service.StressPrompt.REASONS + listOf("Termin/Prüfung", "Müde", "Krank")).forEach { reason ->
                androidx.compose.material3.AssistChip(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                app.db.addEvent(System.currentTimeMillis() / 1000, de.waschbeken.heliolink.service.StressPrompt.PREFIX + reason)
                            }
                            reload++
                        }
                    },
                    label = { Text(reason) },
                )
            }
        }
    }
}

/** The strap's SDNN (a few values a day) of the last 14 days, as average per day. */
@Composable
internal fun SdnnPanel(dataVersion: Int) {
    val zone = ZoneId.systemDefault()
    val values by dbValue(dataVersion) { db ->
        val today = LocalDate.now()
        db.values(HealthDb.SDNN, today.minusDays(13).atStartOfDay(zone).toEpochSecond(), today.plusDays(1).atStartOfDay(zone).toEpochSecond())
    }
    val list = values ?: return
    if (list.isEmpty()) return
    val today = LocalDate.now()
    val days = (13 downTo 0).map { today.minusDays(it.toLong()) }
    val byDay = list.groupBy { java.time.Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }
    Panel {
        Text("SDNN vom Strap", style = MaterialTheme.typography.titleMedium)
        BarChart(
            days.map { d -> byDay[d]?.map { it.value }?.average()?.toFloat() ?: 0f }, Palette.Hrv,
            labels = days.map { it.dayOfMonth.toString() },
            format = { "${it.toInt()}" },
            tooltip = { i -> "${days[i].dayOfMonth}.${days[i].monthValue}.: " + (byDay[days[i]]?.joinToString(", ") { "${it.value} ms" } ?: "keine Werte") },
        )
        Text(
            "Zuletzt ${list.last().value} ms",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** HRV status: 7-night average against the usual range, with the course of the last six weeks. */
@Composable
internal fun HrvStatusPanel(dataVersion: Int) {
    val result by dbValue(dataVersion) { db ->
        val today = LocalDate.now()
        val nightly = Queries.nightVitals(db, today.minusDays(60), today, ZoneId.systemDefault()).mapNotNull { v -> v.hrv?.let { v.date to it } }.toMap()
        de.waschbeken.heliolink.core.HrvStatus.status(nightly, today)
    }
    Panel {
        Text("HRV-Status", style = MaterialTheme.typography.titleMedium)
        val r = result
        if (r == null) {
            Text(
                "Braucht die HRV aus mindestens 14 Nächten, davon 3 in der letzten Woche. Der Strap liefert sie beim Abruf – " +
                    "nach ein paar Nächten erscheint der Status.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Panel
        }
        Text(r.status.label, style = MaterialTheme.typography.headlineSmall, color = Palette.Hrv)
        Text(
            "Schnitt der letzten 7 Tage ${r.weekAverage.toInt()} ms · dein üblicher Bereich ${r.low.toInt()}–${r.high.toInt()} ms",
            style = MaterialTheme.typography.bodyMedium,
        )
        val zone = ZoneId.systemDefault()
        val points = r.series.map { (d, v) -> d.atTime(12, 0).atZone(zone).toEpochSecond() to v.toFloat() }
        if (points.size >= 2) {
            LineChart(
                listOf(Series(points, Palette.Hrv, width = 2.5f)),
                points.first().first, points.last().first,
                gapSeconds = 3 * 86_400L,
                areas = listOf(Triple(r.low.toFloat(), r.high.toFloat(), Palette.Hrv.copy(alpha = 0.15f))),
                unit = "ms",
            )
            Text("Linie: Schnitt der jeweils letzten 7 Tage · Fläche: dein üblicher Bereich", style = MaterialTheme.typography.labelSmall)
        }
        Text(r.status.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun StepsContent(d: MetricData, profile: UserProfile) {
    val zone = ZoneId.systemDefault()
    val strideKm = profile.heightCm * 0.414 / 100_000
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (d.period == Period.DAY) {
            val hourly = FloatArray(24)
            d.activity.forEach { s -> hourly[java.time.Instant.ofEpochSecond(s.timestamp).atZone(zone).hour] += s.steps.toFloat() }
            val t = d.totals[d.date]
            val total = t?.steps ?: 0
            Panel { BarChart(hourly.toList(), Palette.Steps, labels = List(24) { if (it % 6 == 0) "$it" else "" }) }
            TileRow("Schritte" to total.fmt(), "Ziel" to "${total * 100 / profile.stepGoal.coerceAtLeast(1)} %")
            TileRow("Strecke" to "%.1f km".format(total * strideKm), "Aktive Minuten" to "${t?.activeMinutes ?: 0}")
        } else {
            val withData = d.totals.values.filter { it.steps > 0 }
            val perBar = Buckets.average(d.buckets) { day -> d.totals[day]?.steps?.takeIf { it > 0 }?.toDouble() }
            Panel {
                Text("Schritte ${d.period.perBar}", style = MaterialTheme.typography.titleMedium)
                BarChart(
                    perBar.map { it ?: 0f }, Palette.Steps, labels = d.buckets.map { it.label }, goal = profile.stepGoal.toFloat(),
                    tooltip = { i -> d.buckets[i].describe() + ": " + (perBar[i]?.toInt()?.fmt()?.let { "$it Schritte" + if (d.buckets[i].days > 1) "/Tag" else "" } ?: "keine Daten") },
                )
            }
            TileRow(
                "Ø pro Tag" to (withData.takeIf { it.isNotEmpty() }?.map { it.steps }?.average()?.toInt()?.fmt() ?: "–"),
                "Gesamt" to withData.sumOf { it.steps }.fmt(),
            )
            TileRow(
                "Ziel erreicht" to "${withData.count { it.steps >= profile.stepGoal }} Tage",
                "Bester Tag" to (withData.maxByOrNull { it.steps }?.steps?.fmt() ?: "–"),
            )
            TileRow(
                "Strecke" to "%.0f km".format(withData.sumOf { it.steps } * strideKm),
                "Aktive Min. Ø" to (withData.takeIf { it.isNotEmpty() }?.map { it.activeMinutes }?.average()?.toInt()?.let { "$it /Tag" } ?: "–"),
            )
        }
        if (d.heatmap.isNotEmpty()) {
            SectionHeader("Letzte 20 Wochen")
            Panel {
                Heatmap(d.heatmap, Palette.Steps)
                Text("Je dunkler, desto mehr Schritte", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun StressContent(d: MetricData) {
    val period = d.period
    val zone = ZoneId.systemDefault()
    val values = d.stress.map { it.stress }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (values.isEmpty()) {
            Panel { Text("Keine Stresswerte in diesem Zeitraum.", style = MaterialTheme.typography.bodyMedium) }
            return@Column
        }
        if (period == Period.DAY) {
            val buckets = List(48) { i ->
                val from = d.start + i * 1800L
                d.stress.filter { it.timestamp in from until from + 1800 }.map { it.stress }.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f
            }
            Panel { BarChart(buckets, Palette.Stress, colors = buckets.map { stressColor(it.toInt()) }, labels = List(48) { if (it % 12 == 0) "${it / 2}" else "" }) }
        } else {
            val byDay = perDay(d.stress.map { ValueSample(it.timestamp, it.stress) })
            val perBar = Buckets.average(d.buckets) { day -> byDay[day]?.average() }
            Panel {
                Text("Ø Stress ${period.perBar}", style = MaterialTheme.typography.titleMedium)
                BarChart(
                    perBar.map { it ?: 0f }, Palette.Stress, colors = perBar.map { stressColor((it ?: 0f).toInt()) }, labels = d.buckets.map { it.label },
                    tooltip = { i -> d.buckets[i].describe() + ": " + (perBar[i]?.let { "Ø ${it.toInt()}" } ?: "keine Werte") },
                )
            }
        }
        val parts = listOf(0 until 40, 40 until 60, 60 until 80, 80..100).map { r -> values.count { it in r } }
        Panel {
            Text("Verteilung", style = MaterialTheme.typography.titleSmall)
            StackedBar(parts.mapIndexed { i, v -> v.toFloat() to stressColor(listOf(20, 50, 70, 90)[i]) })
            listOf("entspannt", "normal", "mittel", "hoch").forEachIndexed { i, name ->
                Text("$name: ${parts[i] * 100 / values.size} %", style = MaterialTheme.typography.bodySmall, color = stressColor(listOf(20, 50, 70, 90)[i]))
            }
        }
        TileRow("Durchschnitt" to "${values.average().toInt()}", "Maximum" to "${values.max()}")
    }
}

/** Generic page for value tables (SpO₂, HRV, temperature, breathing). */
@Composable
internal fun ValueContent(
    d: MetricData,
    metric: Metric,
    unit: String,
    scale: Double,
    decimals: Int = 0,
    minY: Float? = null,
    maxY: Float? = null,
) {
    val period = d.period
    fun f(v: Double) = "%.${decimals}f".format(v)
    val values = d.values.map { it.value * scale }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (values.isEmpty()) {
            Panel { Text("Keine Werte in diesem Zeitraum.", style = MaterialTheme.typography.bodyMedium) }
            return@Column
        }
        if (period == Period.DAY) {
            Panel {
                LineChart(
                    listOf(Series(d.values.map { it.timestamp to (it.value * scale).toFloat() }, metric.color, fill = true)),
                    d.start, d.end, gapSeconds = 3 * 3600, minY = minY, maxY = maxY, format = { f(it.toDouble()) },
                )
            }
        } else {
            val byDay = perDay(d.values, scale, shiftHours = 6)
            val avgPerBar = Buckets.average(d.buckets) { day -> byDay[day]?.average() }
            Panel {
                Text("${metric.title} ${period.perBar}", style = MaterialTheme.typography.titleMedium)
                RangeChart(
                    d.buckets.mapIndexed { i, b ->
                        val v = generateSequence(b.from) { it.plusDays(1) }.takeWhile { !it.isAfter(b.to) }.flatMap { byDay[it].orEmpty() }.toList()
                        if (v.isEmpty()) Triple(null, null, null) else Triple(v.min().toFloat(), avgPerBar[i], v.max().toFloat())
                    },
                    d.buckets.map { it.label }, metric.color, tooltip = { d.buckets[it].describe() },
                )
            }
            if (period.long) {
                Panel {
                    Text("Trend", style = MaterialTheme.typography.titleMedium)
                    TrendChart(avgPerBar, d.buckets.map { it.label }, metric.color, baseline = values.average().toFloat(), format = { f(it.toDouble()) }, tooltip = { d.buckets[it].describe() })
                    Text("Gestrichelt: Durchschnitt des Zeitraums", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        TileRow("Durchschnitt" to "${f(values.average())} $unit", "Messungen" to "${values.size}")
        TileRow("Minimum" to "${f(values.min())} $unit", "Maximum" to "${f(values.max())} $unit")
    }
}

@Composable
internal fun PaiContent(d: MetricData) {
    val period = d.period
    val zone = ZoneId.systemDefault()
    val byDay = d.pai.associateBy { java.time.Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The ring shows the last day of the period (today for the current one).
        val last = if (period == Period.DAY) d.date else d.buckets.lastOrNull()?.to ?: d.date
        val now = LocalDate.now()
        PaiOverview(d.pai, if (last.isAfter(now)) now else last)
        if (period != Period.DAY) {
            Panel {
                Text("PAI-Stand ${period.perBar}", style = MaterialTheme.typography.titleSmall)
                BarChart(Buckets.average(d.buckets) { byDay[it]?.total?.toDouble() }.map { it ?: 0f }, Palette.Pai, labels = d.buckets.map { it.label }, goal = 100f)
            }
        }
    }
}

@Composable
internal fun BatteryContent(d: MetricData, onSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(onClick = onSettings) {
            Text("Akku & Leistung", style = MaterialTheme.typography.titleMedium)
            Text(
                "Profile zum Akkusparen oder für maximale Genauigkeit, Verbrauch pro Tag und was am meisten Akku kostet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (d.values.isEmpty()) {
            Panel { Text("Noch kein Akkuverlauf gespeichert.", style = MaterialTheme.typography.bodyMedium) }
            return@Column
        }
        Panel {
            LineChart(
                listOf(Series(d.values.map { it.timestamp to it.value.toFloat() }, Palette.Battery, fill = true)),
                d.values.first().timestamp.coerceAtLeast(d.start - 3 * 86400), d.end, gapSeconds = 12 * 3600, minY = 0f, maxY = 100f,
            )
        }
        val days = BatteryForecast.daysLeft(d.values)
        TileRow("Aktuell" to "${d.values.last().value} %", "Reicht noch" to (days?.let { "%.1f Tage".format(java.util.Locale.GERMANY, it) } ?: "–"))
    }
}
