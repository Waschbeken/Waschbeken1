package de.waschbeken.heliolink.ui.metrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import de.waschbeken.heliolink.core.Bucket
import de.waschbeken.heliolink.core.Buckets
import de.waschbeken.heliolink.data.Workout
import de.waschbeken.heliolink.ui.Legend
import de.waschbeken.heliolink.ui.StackedBarChart
import de.waschbeken.heliolink.ui.TrendChart
import de.waschbeken.heliolink.ui.describe
import de.waschbeken.heliolink.ui.minuteStrip
import de.waschbeken.heliolink.ui.rememberTimeZoom
import de.waschbeken.heliolink.core.SleepStage
import androidx.compose.material3.TextButton
import kotlin.math.roundToInt
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
            "höher als sonst heißt meist gut erholt, deutlich niedriger heißt Belastung. Nachts kommt sie aus dem Import; " +
            "tagsüber berechnet HelioLink sie jede Minute aus den Herzschlag-Abständen, solange der Puls live gemessen wird " +
            "(Live-Puls, Puls-Aufzeichnung, Training). Werte in Bewegung schwanken stark.",
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
        "Atemzüge pro Minute im Schlaf. Normal sind etwa 12–20. Wichtig sind Änderungen gegenüber deinem Schnitt. " +
            "Der Strap misst die Atemfrequenz nur im Schlaf (auch Gadgetbridge liest nur diese Werte).",
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
    val period: Period,
    val date: LocalDate,
    val from: LocalDate,
    val to: LocalDate,
    val start: Long,
    val end: Long,
    val buckets: List<Bucket>,
    val activity: List<ActivitySample> = emptyList(),
    /** Per-day totals for every period but a single day (heart rate and steps). */
    val totals: Map<LocalDate, Queries.DayTotals> = emptyMap(),
    val values: List<ValueSample> = emptyList(),
    val stress: List<StressSample> = emptyList(),
    val pai: List<PaiSample> = emptyList(),
    val hr: List<Pair<Long, Float>> = emptyList(),
    val strapResting: Int? = null,
    /** Average strap resting heart rate of the 30 days before the period. */
    val restingBaseline: Int? = null,
    val heatmap: List<Pair<LocalDate, Float>> = emptyList(),
    val sleepHr: Pair<Int?, Int?>? = null,
    val workouts: List<Workout> = emptyList(),
    val stages: Map<Long, SleepStage> = emptyMap(),
)

private fun zoneLower(profile: UserProfile, rest: Int?) = IntArray(5) { HeartRateZones.lowerBpm(it, profile.maxHeartRate, rest) }

private fun load(db: HealthDb, metric: Metric, period: Period, date: LocalDate, profile: UserProfile): MetricData {
    val zone = ZoneId.systemDefault()
    val (from, to) = period.range(date)
    var start = from.atStartOfDay(zone).toEpochSecond()
    val end = to.plusDays(1).atStartOfDay(zone).toEpochSecond()
    val buckets = period.buckets(date)
    // HRV, temperature and breathing belong to the night before the day
    if (metric in setOf(Metric.HRV, Metric.TEMPERATURE, Metric.RESPIRATION, Metric.SPO2)) start -= 6 * 3600
    val base = MetricData(period, date, from, to, start, end, buckets)
    fun copy(
        activity: List<ActivitySample> = emptyList(),
        totals: Map<LocalDate, Queries.DayTotals> = emptyMap(),
        values: List<ValueSample> = emptyList(),
        stress: List<StressSample> = emptyList(),
        pai: List<PaiSample> = emptyList(),
        hr: List<Pair<Long, Float>> = emptyList(),
        strapResting: Int? = null,
        restingBaseline: Int? = null,
        heatmap: List<Pair<LocalDate, Float>> = emptyList(),
        sleepHr: Pair<Int?, Int?>? = null,
        workouts: List<Workout> = emptyList(),
        stages: Map<Long, SleepStage> = emptyMap(),
    ) = MetricData(
        base.period, base.date, base.from, base.to, base.start, base.end, base.buckets,
        activity, totals, values, stress, pai, hr, strapResting, restingBaseline, heatmap, sleepHr, workouts, stages,
    )
    return when (metric) {
        Metric.HEART, Metric.STEPS -> {
            val resting = db.values(HealthDb.RESTING_HR, start - 30 * 86400L, end)
            val strapResting = resting.lastOrNull { it.timestamp < end }?.value
            val baseline = resting.filter { it.timestamp < start }.map { it.value }.takeIf { it.size >= 3 }?.average()?.toInt()
            val rest = profile.zoneRest(strapResting)
            val heat = if (metric == Metric.STEPS) {
                val hFrom = LocalDate.now().minusWeeks(19).let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
                Queries.dayTotals(db, hFrom, LocalDate.now(), zone, zoneLower(profile, rest)).map { (d, t) -> d to t.steps.toFloat() }
            } else emptyList()
            if (period == Period.DAY) {
                val activity = db.activity(start, end)
                copy(
                    activity = activity,
                    totals = Queries.dayTotals(db, from, to, zone, zoneLower(profile, rest)),
                    hr = Queries.heartRatePoints(db, start, end, activity),
                    strapResting = strapResting,
                    restingBaseline = baseline,
                    values = resting.filter { it.timestamp >= start },
                    heatmap = heat,
                    sleepHr = if (metric == Metric.HEART) Queries.night(db, date, zone)?.let { it.avgHeartRate to it.minHeartRate } else null,
                    workouts = if (metric == Metric.HEART) db.workouts().filter { it.start in start until end } else emptyList(),
                    stages = if (metric == Metric.HEART) {
                        Queries.nights(db, date, date.plusDays(1), zone).values.flatMap { it.stages }.associate { (t, st) -> t / 60 * 60 to st }
                    } else emptyMap(),
                )
            } else {
                copy(
                    totals = Queries.dayTotals(db, from, to, zone, zoneLower(profile, rest)),
                    strapResting = strapResting,
                    restingBaseline = baseline,
                    values = resting.filter { it.timestamp >= start },
                    heatmap = heat,
                )
            }
        }
        Metric.STRESS -> copy(stress = db.stress(start, end))
        Metric.PAI -> copy(pai = db.pai(start - 86400, end))
        Metric.SPO2 -> copy(values = db.values(HealthDb.SPO2, start, end))
        Metric.HRV -> copy(values = db.values(HealthDb.HRV, start, end))
        Metric.TEMPERATURE -> copy(values = db.values(HealthDb.TEMPERATURE, start, end))
        Metric.RESPIRATION -> copy(values = db.values(HealthDb.RESPIRATION, start, end))
        Metric.BATTERY -> copy(values = db.values(HealthDb.BATTERY, start - 3 * 86400, end))
    }
}

/** Per-day average of a value table; values in the evening count for the next day ([shiftHours]). */
private fun perDay(values: List<ValueSample>, scale: Double = 1.0, shiftHours: Long = 0): Map<LocalDate, List<Double>> {
    val zone = ZoneId.systemDefault()
    return values.groupBy { java.time.Instant.ofEpochSecond(it.timestamp + shiftHours * 3600).atZone(zone).toLocalDate() }
        .mapValues { (_, v) -> v.map { it.value * scale } }
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
    val profile = app.prefs.profile.also { tick }
    val data by dbValue(metric, period, date, state.dataVersion, tick) { load(it, metric, period, date, profile) }
    val startRecording = de.waschbeken.heliolink.ui.record.rememberRecordStarter(nav)

    Scaffold(topBar = { BackTopBar(metric.title, onBack = { nav.popBackStack() }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PeriodSelector(period, { periodName = it.name }) }
            item { DateNavigator(period, date) { dateEpoch = it.toEpochDay() } }
            // Older data stays visible while the new period loads; only show it when it matches.
            val d = data?.takeIf { it.period == period && it.date == date }
            if (d == null) {
                item { Text("Lade …", style = MaterialTheme.typography.bodyMedium) }
            } else {
                item {
                    when (metric) {
                        Metric.HEART -> if (d.period == Period.DAY) {
                            HeartDay(
                                d, profile,
                                state.heartRate.takeIf { date == LocalDate.now() && System.currentTimeMillis() - state.heartRateAt < 120_000 },
                                onRecord = startRecording.takeIf { date == LocalDate.now() },
                            )
                        } else HeartRange(d, profile)
                        Metric.STEPS -> StepsContent(d, profile)
                        Metric.STRESS -> StressContent(d)
                        Metric.SPO2 -> ValueContent(d, metric, "%", 1.0, minY = 80f, maxY = 100f)
                        Metric.HRV -> ValueContent(d, metric, "ms", 1.0)
                        Metric.TEMPERATURE -> ValueContent(d, metric, "°C", 0.01, decimals = 1)
                        Metric.RESPIRATION -> ValueContent(d, metric, "/min", 1.0)
                        Metric.PAI -> PaiContent(d)
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

private val clockFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
private fun clock(epoch: Long) = java.time.Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(clockFormat)

private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

/** Big number with a caption, used at the top of the heart rate pages. */
@Composable
private fun HeartHero(value: String, unit: String, caption: String, side: List<Pair<String, String>>, onClick: (() -> Unit)? = null) {
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
private fun ZonePanel(title: String, minutes: IntArray, lower: IntArray, daily: Boolean = false) {
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
private fun HeartDay(d: MetricData, profile: UserProfile, live: Int?, onRecord: (() -> Unit)?) {
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
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (dayZoom.zoomed) "${clock(dayZoom.start)} – ${clock(dayZoom.end)}" else "Finger halten: Werte · zwei Finger oder Doppeltipp: zoomen",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                if (dayZoom.zoomed) TextButton(onClick = { dayZoom.reset() }) { Text("Ganzer Tag") }
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
private fun HeartRange(d: MetricData, profile: UserProfile) {
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

@Composable
private fun StepsContent(d: MetricData, profile: UserProfile) {
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
private fun StressContent(d: MetricData) {
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
private fun ValueContent(
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
private fun PaiContent(d: MetricData) {
    val period = d.period
    val zone = ZoneId.systemDefault()
    val byDay = d.pai.associateBy { java.time.Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val latest = d.pai.lastOrNull()
        if (latest == null) {
            Panel { Text("Keine PAI-Werte in diesem Zeitraum.", style = MaterialTheme.typography.bodyMedium) }
            return@Column
        }
        TileRow("PAI (7 Tage)" to "${latest.total.toInt()}", "Heute verdient" to "%.1f".format(latest.today))
        if (period != Period.DAY) {
            Panel {
                Text("PAI-Stand ${period.perBar}", style = MaterialTheme.typography.titleSmall)
                BarChart(Buckets.average(d.buckets) { byDay[it]?.total?.toDouble() }.map { it ?: 0f }, Palette.Pai, labels = d.buckets.map { it.label }, goal = 100f)
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
