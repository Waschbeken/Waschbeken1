package de.waschbeken.heliolink.ui.metrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import de.waschbeken.heliolink.core.Bucket
import de.waschbeken.heliolink.data.Workout
import de.waschbeken.heliolink.core.SleepStage
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.ActivitySample
import de.waschbeken.heliolink.core.HeartRateZones
import de.waschbeken.heliolink.core.PaiSample
import de.waschbeken.heliolink.core.StressSample
import de.waschbeken.heliolink.core.ValueSample
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.data.Queries
import de.waschbeken.heliolink.data.UserProfile
import de.waschbeken.heliolink.ui.BackTopBar
import de.waschbeken.heliolink.ui.DateNavigator
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.Period
import de.waschbeken.heliolink.ui.PeriodSelector
import de.waschbeken.heliolink.ui.StatTile
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** A metric with its own detail page. */
enum class Metric(val key: String, val title: String, val color: Color, val about: String) {
    HEART(
        "heart", "Herzfrequenz", Palette.Heart,
        "Der Strap misst den Puls im eingestellten Intervall (Einstellungen → Messungen). Der Ruhepuls ist ein guter " +
            "Gradmesser für Fitness und Erholung: sinkt er über Wochen, wirst du fitter; steigt er plötzlich, kann das auf Stress, " +
            "zu wenig Schlaf oder eine beginnende Erkältung hindeuten. Den Herzrhythmus prüft HelioLink, wenn der Puls live " +
            "läuft und du ruhig bist (je 64 Schläge).",
    ),
    STEPS(
        "steps", "Schritte", Palette.Steps,
        "Schritte zählt der Strap am Arm oder Oberarm. Aktive Minuten sind Minuten mit mindestens 30 Schritten oder deutlicher Bewegung.",
    ),
    STRESS(
        "stress", "Stress", Palette.Stress,
        "Der Stresswert (0–100) wird aus der Herzfrequenzvariabilität berechnet. Unter 40 ist entspannt, über 80 hoch. " +
            "Sport erhöht den Wert kurzzeitig ebenfalls. Nach längerem hohem Stress fragt HelioLink „Was war los?“ – " +
            "deine Antworten stehen unter „Deine Stress-Auslöser“.",
    ),
    SPO2(
        "spo2", "Blutsauerstoff (SpO₂)", Palette.Spo2,
        "Normal sind 95–100 %. Einzelne niedrige Werte entstehen oft durch Bewegung oder schlechten Sitz. Dauerhaft unter 90 % gehört ärztlich abgeklärt.",
    ),
    HRV(
        "hrv", "Herzfrequenzvariabilität", Palette.Hrv,
        "Die HRV zeigt, wie flexibel dein Nervensystem ist. Wichtig ist der Vergleich mit deinem eigenen Schnitt: " +
            "höher als sonst heißt meist gut erholt, deutlich niedriger heißt Belastung. Der Strap misst sie selbst alle ein " +
            "bis zwei Minuten, Tag und Nacht, und HelioLink holt sie beim Abruf. Läuft der Puls live (Live-Puls, Aufzeichnung, " +
            "Training), rechnet HelioLink zusätzlich jede Minute aus den Herzschlag-Abständen. Werte in Bewegung schwanken stark; " +
            "am aussagekräftigsten ist die HRV im Schlaf. SDNN ist ein zweites HRV-Maß, das der Strap 3–4 Mal am Tag über " +
            "mehrere Minuten selbst berechnet.",
    ),
    TEMPERATURE(
        "temperature", "Hauttemperatur", Palette.Temperature,
        "Die Hauttemperatur in der Nacht schwankt mit Raumtemperatur und Zyklus. Ein Anstieg um mehr als etwa 0,5 °C über " +
            "deinen Schnitt kann auf eine Erkältung hindeuten.",
    ),
    PAI(
        "pai", "PAI", Palette.Pai,
        "PAI (Personal Activity Intelligence) bewertet, wie sehr Aktivität deinen Puls hebt. Wer über 7 Tage mindestens " +
            "100 PAI hält, profitiert laut Studien deutlich für die Herzgesundheit. Der Ring zeigt deinen Stand der letzten " +
            "7 Tage, orange ist der Teil, der am gewählten Tag dazukam.",
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
internal class MetricData(
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
    val events: List<Pair<Long, String>> = emptyList(),
)

internal fun zoneLower(profile: UserProfile, rest: Int?) = IntArray(5) { HeartRateZones.lowerBpm(it, profile.maxHeartRate, rest) }

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
        events: List<Pair<Long, String>> = emptyList(),
    ) = MetricData(
        base.period, base.date, base.from, base.to, base.start, base.end, base.buckets,
        activity, totals, values, stress, pai, hr, strapResting, restingBaseline, heatmap, sleepHr, workouts, stages, events,
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
                    events = db.events(start, end).map { it.timestamp to it.tag },
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
        // A week before the period for the daily bars under the ring.
        Metric.PAI -> copy(pai = db.pai(start - 7 * 86400, end))
        Metric.SPO2 -> copy(values = db.values(HealthDb.SPO2, start, end))
        Metric.HRV -> copy(values = db.values(HealthDb.HRV, start, end))
        Metric.TEMPERATURE -> copy(values = db.values(HealthDb.TEMPERATURE, start, end))
        Metric.RESPIRATION -> copy(values = db.values(HealthDb.RESPIRATION, start, end))
        Metric.BATTERY -> copy(values = db.values(HealthDb.BATTERY, start - 3 * 86400, end))
    }
}

/** Per-day average of a value table; values in the evening count for the next day ([shiftHours]). */
internal fun perDay(values: List<ValueSample>, scale: Double = 1.0, shiftHours: Long = 0): Map<LocalDate, List<Double>> {
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
    val data by de.waschbeken.heliolink.ui.cachedDbValue("metric-${metric.key}-$period-$date", state.dataVersion, tick) { load(it, metric, period, date, profile) }
    val startRecording = de.waschbeken.heliolink.ui.record.rememberRecordStarter(nav)

    var showAbout by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            BackTopBar(metric.title, onBack = { nav.popBackStack() }) {
                androidx.compose.material3.IconButton(onClick = { showAbout = true }) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Outlined.Info,
                        contentDescription = "Was bedeutet ${metric.title}?",
                    )
                }
            }
        },
    ) { padding ->
        if (showAbout) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showAbout = false },
                title = { Text(metric.title) },
                text = { Text(metric.about, style = MaterialTheme.typography.bodyMedium) },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { showAbout = false }) { Text("OK") } },
            )
        }
        de.waschbeken.heliolink.ui.SyncRefreshBox(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
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
                            Metric.BATTERY -> BatteryContent(d) { nav.navigate(de.waschbeken.heliolink.ui.Routes.settings("battery")) }
                        }
                    }
                }
                if (metric == Metric.HEART) {
                    item {
                        Panel {
                            Text("Wirkung deiner Einträge", style = MaterialTheme.typography.titleMedium)
                            de.waschbeken.heliolink.ui.events.EntryEffectsList(state.dataVersion)
                        }
                    }
                    item { RhythmPanel(state.dataVersion) }
                }
                if (metric == Metric.HRV) item { HrvStatusPanel(state.dataVersion) }
                if (metric == Metric.HRV) item { SdnnPanel(state.dataVersion) }
                if (metric == Metric.STRESS) item { StressTriggersPanel(state.dataVersion) }
            }
        }
    }
}

@Composable
internal fun TileRow(vararg tiles: Pair<String, String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f)) }
    }
}

internal val clockFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

internal fun clock(epoch: Long) = java.time.Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(clockFormat)

internal fun signed(v: Int) = if (v > 0) "+$v" else "$v"
