package de.waschbeken.heliolink.ui.metrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.ActivitySample
import de.waschbeken.heliolink.core.Aggregates
import de.waschbeken.heliolink.core.BatteryForecast
import de.waschbeken.heliolink.core.DayStats
import de.waschbeken.heliolink.core.HeartRateZones
import de.waschbeken.heliolink.core.PaiSample
import de.waschbeken.heliolink.core.StressSample
import de.waschbeken.heliolink.core.ValueSample
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.data.Queries
import de.waschbeken.heliolink.data.UserProfile
import de.waschbeken.heliolink.ui.BackTopBar
import de.waschbeken.heliolink.ui.BarChart
import de.waschbeken.heliolink.ui.DateNavigator
import de.waschbeken.heliolink.ui.German
import de.waschbeken.heliolink.ui.Heatmap
import de.waschbeken.heliolink.ui.LineChart
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.Period
import de.waschbeken.heliolink.ui.PeriodSelector
import de.waschbeken.heliolink.ui.RangeChart
import de.waschbeken.heliolink.ui.SectionHeader
import de.waschbeken.heliolink.ui.Series
import de.waschbeken.heliolink.ui.StackedBar
import de.waschbeken.heliolink.ui.StatTile
import de.waschbeken.heliolink.ui.dbValue
import de.waschbeken.heliolink.ui.fmt
import de.waschbeken.heliolink.ui.formatMinutes
import de.waschbeken.heliolink.ui.stressColor
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle

/** A metric with its own detail page. */
enum class Metric(val key: String, val title: String, val color: Color, val about: String) {
    HEART(
        "heart", "Herzfrequenz", Palette.Heart,
        "Der Strap misst den Puls im eingestellten Intervall (Einstellungen → Messungen). Der Ruhepuls ist ein guter " +
            "Gradmesser für Fitness und Erholung: sinkt er über Wochen, wirst du fitter; steigt er plötzlich, kann das auf Stress, " +
            "zu wenig Schlaf oder eine beginnende Erkältung hindeuten.",
    ),
    STEPS(
        "steps", "Schritte", Palette.Steps,
        "Schritte zählt der Strap am Arm oder Oberarm. Aktive Minuten sind Minuten mit mindestens 30 Schritten oder deutlicher Bewegung.",
    ),
    STRESS(
        "stress", "Stress", Palette.Stress,
        "Der Stresswert (0–100) wird aus der Herzfrequenzvariabilität berechnet. Unter 40 ist entspannt, über 80 hoch. " +
            "Sport erhöht den Wert kurzzeitig ebenfalls.",
    ),
    SPO2(
        "spo2", "Blutsauerstoff (SpO₂)", Palette.Spo2,
        "Normal sind 95–100 %. Einzelne niedrige Werte entstehen oft durch Bewegung oder schlechten Sitz. Dauerhaft unter 90 % gehört ärztlich abgeklärt.",
    ),
    HRV(
        "hrv", "Herzfrequenzvariabilität", Palette.Hrv,
        "Die HRV zeigt, wie flexibel dein Nervensystem ist. Wichtig ist der Vergleich mit deinem eigenen Schnitt: " +
            "höher als sonst heißt meist gut erholt, deutlich niedriger heißt Belastung.",
    ),
    TEMPERATURE(
        "temperature", "Hauttemperatur", Palette.Temperature,
        "Die Hauttemperatur in der Nacht schwankt mit Raumtemperatur und Zyklus. Ein Anstieg um mehr als etwa 0,5 °C über " +
            "deinen Schnitt kann auf eine Erkältung hindeuten.",
    ),
    PAI(
        "pai", "PAI", Palette.Pai,
        "PAI (Personal Activity Intelligence) bewertet, wie sehr Aktivität deinen Puls hebt. Wer über 7 Tage mindestens " +
            "100 PAI hält, profitiert laut Studien deutlich für die Herzgesundheit.",
    ),
    RESPIRATION(
        "respiration", "Atemfrequenz", Palette.Breath,
        "Atemzüge pro Minute im Schlaf. Normal sind etwa 12–20. Wichtig sind Änderungen gegenüber deinem Schnitt.",
    ),
    BATTERY(
        "battery", "Akku", Palette.Battery,
        "Der Akkuverlauf des Straps. Dauerhafte Puls-Aufzeichnung, Puls-Broadcast und SpO₂ verbrauchen am meisten.",
    );

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: HEART
    }
}

/** Data of one metric for a period. */
private class MetricData(
    val from: LocalDate,
    val to: LocalDate,
    val start: Long,
    val end: Long,
    val activity: List<ActivitySample> = emptyList(),
    val days: List<DayStats> = emptyList(),
    val values: List<ValueSample> = emptyList(),
    val stress: List<StressSample> = emptyList(),
    val pai: List<PaiSample> = emptyList(),
    val hr: List<Pair<Long, Float>> = emptyList(),
    val strapResting: Int? = null,
    val heatmap: List<Pair<LocalDate, Float>> = emptyList(),
)

private fun load(db: HealthDb, metric: Metric, period: Period, date: LocalDate): MetricData {
    val zone = ZoneId.systemDefault()
    val (from, to) = period.range(date)
    var start = from.atStartOfDay(zone).toEpochSecond()
    val end = to.plusDays(1).atStartOfDay(zone).toEpochSecond()
    // HRV, temperature and breathing belong to the night before the day
    if (metric in setOf(Metric.HRV, Metric.TEMPERATURE, Metric.RESPIRATION, Metric.SPO2)) start -= 6 * 3600
    return when (metric) {
        Metric.HEART, Metric.STEPS -> {
            val activity = db.activity(start, end)
            val heat = if (metric == Metric.STEPS) {
                val hFrom = LocalDate.now().minusWeeks(19).let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
                Aggregates.days(db.activity(hFrom.atStartOfDay(zone).toEpochSecond(), end), hFrom, LocalDate.now(), zone)
                    .map { it.date to it.steps.toFloat() }
            } else emptyList()
            MetricData(
                from, to, start, end,
                activity = activity,
                days = Aggregates.days(activity, from, to, zone),
                hr = if (period == Period.DAY) Queries.heartRatePoints(db, start, end, activity) else emptyList(),
                strapResting = db.values(HealthDb.RESTING_HR, start - 86400, end).lastOrNull()?.value,
                values = db.values(HealthDb.RESTING_HR, start - 86400, end),
                heatmap = heat,
            )
        }
        Metric.STRESS -> MetricData(from, to, start, end, stress = db.stress(start, end))
        Metric.PAI -> MetricData(from, to, start, end, pai = db.pai(start - 86400, end))
        Metric.SPO2 -> MetricData(from, to, start, end, values = db.values(HealthDb.SPO2, start, end))
        Metric.HRV -> MetricData(from, to, start, end, values = db.values(HealthDb.HRV, start, end))
        Metric.TEMPERATURE -> MetricData(from, to, start, end, values = db.values(HealthDb.TEMPERATURE, start, end))
        Metric.RESPIRATION -> MetricData(from, to, start, end, values = db.values(HealthDb.RESPIRATION, start, end))
        Metric.BATTERY -> MetricData(from, to, start, end, values = db.values(HealthDb.BATTERY, start - 3 * 86400, end))
    }
}

private fun dayLabels(from: LocalDate, to: LocalDate, period: Period): List<String> {
    val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
    return days.mapIndexed { i, d ->
        when (period) {
            Period.WEEK -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, German).take(2)
            else -> if (d.dayOfMonth == 1 || d.dayOfMonth % 5 == 0) "${d.dayOfMonth}" else ""
        }
    }
}

@Composable
fun MetricScreen(nav: NavHostController, key: String) {
    val metric = Metric.of(key)
    val context = LocalContext.current
    val app = context.app
    val state by app.repo.state.collectAsStateWithLifecycle()
    val tick by app.prefs.changes.collectAsStateWithLifecycle()
    var periodName by rememberSaveable { mutableStateOf(Period.DAY.name) }
    var dateEpoch by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    val period = Period.valueOf(periodName)
    val date = LocalDate.ofEpochDay(dateEpoch)
    val data by dbValue(metric, period, date, state.dataVersion) { load(it, metric, period, date) }
    val profile = app.prefs.profile.also { tick }

    Scaffold(topBar = { BackTopBar(metric.title, onBack = { nav.popBackStack() }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PeriodSelector(period, { periodName = it.name }) }
            item { DateNavigator(period, date) { dateEpoch = it.toEpochDay() } }
            val d = data
            if (d == null) {
                item { Text("Lade …", style = MaterialTheme.typography.bodyMedium) }
            } else {
                item {
                    when (metric) {
                        Metric.HEART -> HeartContent(d, period, profile)
                        Metric.STEPS -> StepsContent(d, period, profile)
                        Metric.STRESS -> StressContent(d, period)
                        Metric.SPO2 -> ValueContent(d, period, metric, "%", 1.0, minY = 80f, maxY = 100f)
                        Metric.HRV -> ValueContent(d, period, metric, "ms", 1.0)
                        Metric.TEMPERATURE -> ValueContent(d, period, metric, "°C", 0.01, decimals = 1)
                        Metric.RESPIRATION -> ValueContent(d, period, metric, "/min", 1.0)
                        Metric.PAI -> PaiContent(d, period)
                        Metric.BATTERY -> BatteryContent(d)
                    }
                }
            }
            item {
                Panel(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text("Was bedeutet das?", style = MaterialTheme.typography.titleSmall)
                    Text(metric.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TileRow(vararg tiles: Pair<String, String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f)) }
    }
}

@Composable
private fun HeartContent(d: MetricData, period: Period, profile: UserProfile) {
    val zone = ZoneId.systemDefault()
    val rest = profile.zoneRest(d.strapResting)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (period == Period.DAY) {
            val values = d.hr.map { it.second }
            Panel {
                if (values.isEmpty()) {
                    Text("Keine Pulswerte an diesem Tag.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LineChart(
                        listOf(Series(d.hr, Palette.Heart, fill = true)), d.start, d.end,
                        bands = (1..4).map { HeartRateZones.lowerBpm(it, profile.maxHeartRate, rest).toFloat() to Palette.zones[it] },
                    )
                }
            }
            if (values.isNotEmpty()) {
                TileRow("Ruhepuls" to (d.strapResting?.let { "$it" } ?: d.days.firstOrNull()?.restingHeartRate?.toString() ?: "–"), "Durchschnitt" to "${values.average().toInt()}")
                Spacer(Modifier.height(0.dp))
                TileRow("Minimum" to "${values.min().toInt()}", "Maximum" to "${values.max().toInt()}")
                // time in zones from minute values (app values count as their share of a minute)
                val minuteValues = d.activity.filter { it.heartRate > 0 }.map { it.heartRate }
                val zones = IntArray(5)
                minuteValues.forEach { bpm -> HeartRateZones.zoneOf(bpm, profile.maxHeartRate, rest).takeIf { it >= 0 }?.let { zones[it]++ } }
                if (zones.sum() > 0) {
                    SectionHeader("Zeit in Pulszonen")
                    Panel {
                        StackedBar(zones.mapIndexed { i, v -> v.toFloat() to Palette.zones[i] })
                        zones.forEachIndexed { i, v ->
                            if (v > 0) Text("Zone ${i + 1} · ${HeartRateZones.names[i]}: ${formatMinutes(v)}", style = MaterialTheme.typography.bodySmall, color = Palette.zones[i])
                        }
                    }
                }
            }
        } else {
            Panel {
                RangeChart(
                    d.days.map { Triple(it.minHeartRate?.toFloat(), it.avgHeartRate?.toFloat(), it.maxHeartRate?.toFloat()) },
                    dayLabels(d.from, d.to, period), Palette.Heart,
                )
                Text("Balken: niedrigster bis höchster Wert, Punkt: Durchschnitt", style = MaterialTheme.typography.labelSmall)
            }
            val restingPerDay = Queries.dailyAverage(d.values, d.from, d.to, zone)
            if (restingPerDay.any { it != null }) {
                SectionHeader("Ruhepuls")
                Panel { BarChart(restingPerDay.map { it ?: 0f }, Palette.Heart, labels = dayLabels(d.from, d.to, period)) }
            }
            TileRow(
                "Ø Ruhepuls" to (restingPerDay.filterNotNull().takeIf { it.isNotEmpty() }?.average()?.toInt()?.toString() ?: "–"),
                "Ø Puls" to (Aggregates.avg(d.days.map { it.avgHeartRate })?.toString() ?: "–"),
            )
        }
    }
}

@Composable
private fun StepsContent(d: MetricData, period: Period, profile: UserProfile) {
    val zone = ZoneId.systemDefault()
    val strideKm = profile.heightCm * 0.414 / 100_000
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (period == Period.DAY) {
            val hourly = FloatArray(24)
            d.activity.forEach { s -> hourly[java.time.Instant.ofEpochSecond(s.timestamp).atZone(zone).hour] += s.steps.toFloat() }
            val total = d.days.firstOrNull()?.steps ?: 0
            Panel { BarChart(hourly.toList(), Palette.Steps, labels = List(24) { if (it % 6 == 0) "$it" else "" }) }
            TileRow("Schritte" to total.fmt(), "Ziel" to "${total * 100 / profile.stepGoal.coerceAtLeast(1)} %")
            TileRow("Strecke" to "%.1f km".format(total * strideKm), "Aktive Minuten" to "${d.days.firstOrNull()?.activeMinutes ?: 0}")
        } else {
            val steps = d.days.map { it.steps.toFloat() }
            Panel { BarChart(steps, Palette.Steps, labels = dayLabels(d.from, d.to, period), goal = profile.stepGoal.toFloat()) }
            val withData = d.days.filter { it.steps > 0 }
            TileRow(
                "Ø pro Tag" to (withData.takeIf { it.isNotEmpty() }?.map { it.steps }?.average()?.toInt()?.fmt() ?: "–"),
                "Gesamt" to d.days.sumOf { it.steps }.fmt(),
            )
            TileRow(
                "Ziel erreicht" to "${d.days.count { it.steps >= profile.stepGoal }} Tage",
                "Bester Tag" to (d.days.maxByOrNull { it.steps }?.takeIf { it.steps > 0 }?.steps?.fmt() ?: "–"),
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
private fun StressContent(d: MetricData, period: Period) {
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
            val perDay = Queries.dailyAverage(d.stress.map { ValueSample(it.timestamp, it.stress) }, d.from, d.to, zone)
            Panel {
                BarChart(perDay.map { it ?: 0f }, Palette.Stress, colors = perDay.map { stressColor((it ?: 0f).toInt()) }, labels = dayLabels(d.from, d.to, period))
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
private fun ValueContent(
    d: MetricData,
    period: Period,
    metric: Metric,
    unit: String,
    scale: Double,
    decimals: Int = 0,
    minY: Float? = null,
    maxY: Float? = null,
) {
    val zone = ZoneId.systemDefault()
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
            val byDay = d.values.groupBy { java.time.Instant.ofEpochSecond(it.timestamp + 6 * 3600).atZone(zone).toLocalDate() }
            val days = generateSequence(d.from) { it.plusDays(1) }.takeWhile { !it.isAfter(d.to) }.toList()
            Panel {
                RangeChart(
                    days.map { day ->
                        val v = byDay[day]?.map { it.value * scale }
                        if (v.isNullOrEmpty()) Triple(null, null, null) else Triple(v.min().toFloat(), v.average().toFloat(), v.max().toFloat())
                    },
                    dayLabels(d.from, d.to, period), metric.color,
                )
            }
        }
        TileRow("Durchschnitt" to "${f(values.average())} $unit", "Messungen" to "${values.size}")
        TileRow("Minimum" to "${f(values.min())} $unit", "Maximum" to "${f(values.max())} $unit")
    }
}

@Composable
private fun PaiContent(d: MetricData, period: Period) {
    val zone = ZoneId.systemDefault()
    val byDay = d.pai.associateBy { java.time.Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }
    val days = generateSequence(d.from) { it.plusDays(1) }.takeWhile { !it.isAfter(d.to) }.toList()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val latest = d.pai.lastOrNull()
        if (latest == null) {
            Panel { Text("Keine PAI-Werte in diesem Zeitraum.", style = MaterialTheme.typography.bodyMedium) }
            return@Column
        }
        TileRow("PAI (7 Tage)" to "${latest.total.toInt()}", "Heute verdient" to "%.1f".format(latest.today))
        if (period != Period.DAY) {
            Panel {
                Text("PAI-Stand pro Tag", style = MaterialTheme.typography.titleSmall)
                BarChart(days.map { byDay[it]?.total ?: 0f }, Palette.Pai, labels = dayLabels(d.from, d.to, period), goal = 100f)
            }
        }
        Panel {
            Text("Minuten mit erhöhtem Puls (heute)", style = MaterialTheme.typography.titleSmall)
            Text("leicht ${latest.lowMinutes} · mittel ${latest.moderateMinutes} · hoch ${latest.highMinutes} min", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BatteryContent(d: MetricData) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        TileRow("Aktuell" to "${d.values.last().value} %", "Reicht noch" to (days?.let { "%.1f Tage".format(it) } ?: "–"))
    }
}
