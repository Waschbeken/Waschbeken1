package de.waschbeken.heliolink.ui.data

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.ActivitySample
import de.waschbeken.heliolink.core.BatteryForecast
import de.waschbeken.heliolink.core.HeartRateZones
import de.waschbeken.heliolink.core.PaiSample
import de.waschbeken.heliolink.core.SleepSession
import de.waschbeken.heliolink.core.SleepStage
import de.waschbeken.heliolink.core.StressSample
import de.waschbeken.heliolink.core.ValueSample
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.data.Queries
import de.waschbeken.heliolink.data.Workout
import de.waschbeken.heliolink.service.RECORDING_SPORT
import de.waschbeken.heliolink.ui.ActivityChart
import de.waschbeken.heliolink.ui.BarChart
import de.waschbeken.heliolink.ui.DateNavigator
import de.waschbeken.heliolink.ui.DayClock
import de.waschbeken.heliolink.ui.DayState
import de.waschbeken.heliolink.ui.Hypnogram
import de.waschbeken.heliolink.ui.Legend
import de.waschbeken.heliolink.ui.LineChart
import de.waschbeken.heliolink.ui.Metric
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.Period
import de.waschbeken.heliolink.ui.Routes
import de.waschbeken.heliolink.ui.Series
import de.waschbeken.heliolink.ui.StackedBar
import de.waschbeken.heliolink.ui.TimeZoom
import de.waschbeken.heliolink.ui.dayState
import de.waschbeken.heliolink.ui.dayStateLegend
import de.waschbeken.heliolink.ui.dbValue
import de.waschbeken.heliolink.ui.fmt
import de.waschbeken.heliolink.ui.formatDuration
import de.waschbeken.heliolink.ui.formatMinutes
import de.waschbeken.heliolink.ui.minuteStrip
import de.waschbeken.heliolink.ui.navigateToTab
import de.waschbeken.heliolink.ui.record.RecordingBanner
import de.waschbeken.heliolink.ui.record.rememberRecordStarter
import de.waschbeken.heliolink.ui.rememberTimeZoom
import de.waschbeken.heliolink.ui.stressColor
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** The pages of the data tab, like the chart pages of Gadgetbridge. [metric] opens the long-term statistics. */
private enum class DataPage(val title: String, val metric: String?) {
    ACTIVITY("Aktivität", "steps"),
    HEART("Puls", "heart"),
    SLEEP("Schlaf", null),
    SLEEP_WEEK("Schlaf-Woche", null),
    STEPS("Schritte", "steps"),
    STEPS_WEEK("Schritte-Woche", "steps"),
    STRESS("Stress", "stress"),
    SPO2("SpO₂", "spo2"),
    HRV("HRV", "hrv"),
    TEMPERATURE("Temperatur", "temperature"),
    BREATH("Atmung", "respiration"),
    PAI("PAI", "pai"),
    BATTERY("Akku", "battery"),
}

/** Everything of one day, loaded once for all pages. */
private class DayData(
    val date: LocalDate,
    val from: Long,
    val to: Long,
    val activity: List<ActivitySample>,
    val hr: List<Pair<Long, Float>>,
    val stages: Map<Long, SleepStage>,
    val night: SleepSession?,
    val stress: List<StressSample>,
    val spo2: List<ValueSample>,
    val hrv: List<ValueSample>,
    val temperature: List<ValueSample>,
    val breath: List<ValueSample>,
    val battery: List<ValueSample>,
    val pai: List<PaiSample>,
    val workouts: List<Workout>,
    val resting: Int?,
    /** The 7 days up to [date], for the week pages. */
    val weekNights: Map<LocalDate, Queries.NightSummary>,
    val weekTotals: Map<LocalDate, Queries.DayTotals>,
)

private fun loadDay(db: HealthDb, date: LocalDate): DayData {
    val zone = ZoneId.systemDefault()
    val from = date.atStartOfDay(zone).toEpochSecond()
    val to = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
    val activity = db.activity(from, to)
    // the night that ended this morning and the one that starts this evening
    val nights = Queries.nights(db, date, date.plusDays(1), zone)
    return DayData(
        date, from, to, activity,
        hr = Queries.heartRatePoints(db, from, to, activity),
        stages = nights.values.flatMap { it.stages }.associate { (t, s) -> t / 60 * 60 to s },
        night = nights[date],
        stress = db.stress(from, to),
        spo2 = db.values(HealthDb.SPO2, from, to),
        hrv = db.values(HealthDb.HRV, from, to),
        temperature = db.values(HealthDb.TEMPERATURE, from, to),
        breath = db.values(HealthDb.RESPIRATION, from, to),
        battery = db.values(HealthDb.BATTERY, from - 2 * 86400, to),
        pai = db.pai(from - 6 * 86400, to),
        workouts = db.workouts().filter { it.start < to && it.end > from },
        resting = db.values(HealthDb.RESTING_HR, from - 86400, to).lastOrNull()?.value,
        weekNights = Queries.nightSummaries(db, date.minusDays(6), date, zone),
        weekTotals = Queries.dayTotals(db, date.minusDays(6), date, zone, intArrayOf(100, 120, 140, 160, 180)),
    )
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
private fun clock(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(clockFormat)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DataScreen(nav: NavHostController) {
    val app = LocalContext.current.app
    val state by app.repo.state.collectAsStateWithLifecycle()
    var dateEpoch by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var savedPage by rememberSaveable { mutableIntStateOf(0) }
    val date = LocalDate.ofEpochDay(dateEpoch)
    val pager = rememberPagerState(initialPage = savedPage) { DataPage.entries.size }
    LaunchedEffect(pager.currentPage) { savedPage = pager.currentPage }
    val scope = rememberCoroutineScope()
    val data by dbValue(date, state.dataVersion) { loadDay(it, date) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Daten") },
                actions = {
                    if (date != LocalDate.now()) {
                        IconButton(onClick = { dateEpoch = LocalDate.now().toEpochDay() }) { Icon(Icons.Default.Today, contentDescription = "Heute") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                RecordingBanner(nav)
                DateNavigator(Period.DAY, date) { dateEpoch = it.toEpochDay() }
            }
            ScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 8.dp) {
                DataPage.entries.forEachIndexed { i, p ->
                    Tab(selected = pager.currentPage == i, onClick = { scope.launch { pager.animateScrollToPage(i) } }, text = { Text(p.title) })
                }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { index ->
                val page = DataPage.entries[index]
                val d = data?.takeIf { it.date == date }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (d == null) {
                        Text("Lade …", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        when (page) {
                            DataPage.ACTIVITY -> ActivityPage(d)
                            DataPage.HEART -> HeartPage(d, nav)
                            DataPage.SLEEP -> SleepPage(d, nav)
                            DataPage.SLEEP_WEEK -> SleepWeekPage(d) {
                                dateEpoch = it.toEpochDay()
                                scope.launch { pager.animateScrollToPage(DataPage.SLEEP.ordinal) }
                            }
                            DataPage.STEPS -> StepsPage(d)
                            DataPage.STEPS_WEEK -> StepsWeekPage(d)
                            DataPage.STRESS -> StressPage(d)
                            DataPage.SPO2 -> ValuePage(d, d.spo2, "SpO₂", "%", Palette.Spo2, 1.0, minY = 80f, maxY = 100f)
                            DataPage.HRV -> ValuePage(d, d.hrv, "HRV", "ms", Palette.Hrv, 1.0)
                            DataPage.TEMPERATURE -> ValuePage(d, d.temperature, "Hauttemperatur", "°C", Palette.Temperature, 0.01, decimals = 1)
                            DataPage.BREATH -> ValuePage(d, d.breath, "Atemfrequenz", "/min", Palette.Breath, 1.0)
                            DataPage.PAI -> PaiPage(d)
                            DataPage.BATTERY -> BatteryPage(d)
                        }
                        page.metric?.let { key ->
                            OutlinedButton(onClick = { nav.navigate(Routes.metric(key)) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Wochen, Monate und Jahre ansehen")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Zwei Finger zum Zoomen" and a button back to the whole day. */
@Composable
private fun ZoomHint(zoom: TimeZoom) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (zoom.zoomed) "${clock(zoom.start)} – ${clock(zoom.end)}" else "Finger halten: Werte · zwei Finger oder Doppeltipp: zoomen",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (zoom.zoomed) TextButton(onClick = { zoom.reset() }) { Text("Ganzer Tag") }
    }
}

@Composable
private fun NoData(text: String) {
    Panel { Text(text, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun ActivityPage(d: DayData) {
    val counts = remember(d) {
        val c = HashMap<DayState, Int>()
        d.activity.forEach { s -> c.merge(dayState(listOf(s), d.stages), 1) { a, b -> a + b } }
        c
    }
    val steps = d.activity.sumOf { it.steps }
    val sleep = (counts[DayState.SLEEP_LIGHT] ?: 0) + (counts[DayState.SLEEP_DEEP] ?: 0) + (counts[DayState.SLEEP_REM] ?: 0)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DayClock(d.activity, d.stages, d.from, Modifier.size(170.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(steps.fmt(), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Schritte", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("Aktiv", formatMinutes(counts[DayState.ACTIVE] ?: 0), color = Palette.Steps)
                Metric("Leicht aktiv", formatMinutes(counts[DayState.LIGHT] ?: 0))
                Metric("Schlaf", formatMinutes(sleep), color = Palette.Sleep)
                Metric("Nicht getragen", formatMinutes(counts[DayState.NOT_WORN] ?: 0))
            }
        }
        Legend(dayStateLegend())
    }
    Panel {
        Text("Tagesverlauf", style = MaterialTheme.typography.titleMedium)
        if (d.activity.isEmpty()) {
            Text("Keine Daten an diesem Tag.", style = MaterialTheme.typography.bodySmall)
        } else {
            val zoom = rememberTimeZoom(d.from, d.to)
            ActivityChart(d.activity, d.hr, d.stages, zoom)
            ZoomHint(zoom)
            Legend(listOf("Bewegung" to Palette.Steps, "Schlaf" to Palette.Light, "Puls" to Palette.Heart))
        }
    }
}

@Composable
private fun HeartPage(d: DayData, nav: NavHostController) {
    val app = LocalContext.current.app
    val state by app.repo.state.collectAsStateWithLifecycle()
    val profile = app.prefs.profile
    val rest = profile.zoneRest(d.resting)
    val startRecording = rememberRecordStarter(nav)
    if (d.date == LocalDate.now()) {
        val fresh = state.heartRate != null && System.currentTimeMillis() - state.heartRateAt < 60_000
        Panel(color = Palette.Heart.copy(alpha = 0.13f), onClick = startRecording) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Favorite, contentDescription = null, tint = Palette.Heart)
                Spacer(Modifier.width(8.dp))
                Text(if (fresh) "${state.heartRate}" else "–", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Palette.Heart)
                Spacer(Modifier.width(6.dp))
                Text("bpm", modifier = Modifier.weight(1f))
                Icon(Icons.Default.FiberManualRecord, contentDescription = null, tint = Palette.Heart, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (state.workout != null) "Aufzeichnung läuft" else "Tippen zum Aufzeichnen", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (d.hr.isEmpty()) {
        NoData("Keine Pulswerte an diesem Tag.")
        return
    }
    val lower = IntArray(5) { HeartRateZones.lowerBpm(it, profile.maxHeartRate, rest) }
    val minP = d.hr.minBy { it.second }
    val maxP = d.hr.maxBy { it.second }
    Panel {
        Text("Puls", style = MaterialTheme.typography.titleMedium)
        val zoom = rememberTimeZoom(d.from, d.to)
        LineChart(
            listOf(Series(d.hr, Palette.Heart, fill = true, width = 2f)), zoom.start, zoom.end,
            modifier = Modifier.fillMaxWidth().height(240.dp), zoom = zoom, unit = "bpm",
            areas = (0..4).map { i -> Triple(lower[i].toFloat(), if (i < 4) lower[i + 1].toFloat() else 260f, Palette.zones[i]) },
            markers = listOf(Triple(minP.first, minP.second, "${minP.second.toInt()}"), Triple(maxP.first, maxP.second, "${maxP.second.toInt()}")),
            strip = minuteStrip(d.activity, d.stages),
        )
        ZoomHint(zoom)
    }
    Panel {
        Row {
            Metric("Ruhe", d.resting?.toString() ?: "–", Modifier.weight(1f))
            Metric("Ø", "${d.hr.map { it.second }.average().roundToInt()}", Modifier.weight(1f))
            Metric("Min · ${clock(minP.first)}", "${minP.second.toInt()}", Modifier.weight(1f))
            Metric("Max · ${clock(maxP.first)}", "${maxP.second.toInt()}", Modifier.weight(1f), color = Palette.Heart)
        }
        val zones = IntArray(5)
        d.activity.filter { it.heartRate > 0 }.forEach { s -> HeartRateZones.zoneOf(s.heartRate, profile.maxHeartRate, rest).takeIf { it >= 0 }?.let { zones[it]++ } }
        if (zones.sum() > 0) {
            StackedBar(zones.mapIndexed { i, v -> v.toFloat() to Palette.zones[i] })
            Legend((0..4).filter { zones[it] > 0 }.map { "Z${it + 1} ${formatMinutes(zones[it])}" to Palette.zones[it] })
        }
    }
    WorkoutList(d.workouts, nav)
}

@Composable
private fun WorkoutList(workouts: List<Workout>, nav: NavHostController) {
    if (workouts.isEmpty()) return
    val sections = de.waschbeken.heliolink.ui.rememberSections()
    val open = sections.isOpen("data-workouts", default = true)
    de.waschbeken.heliolink.ui.CollapsibleHeader(
        "Aufzeichnungen und Trainings", open, count = workouts.size,
        summary = workouts.joinToString(", ") { "${it.sport} ${clock(it.start)}" },
    ) { sections.toggle("data-workouts", default = true) }
    if (open) workouts.sortedByDescending { it.start }.forEach { w ->
        Panel(onClick = { nav.navigate(Routes.workout(w.id)) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (w.sport == RECORDING_SPORT) Icons.Default.FiberManualRecord else Icons.Default.Favorite, contentDescription = null, tint = Palette.Heart)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${w.sport} · ${clock(w.start)}", style = MaterialTheme.typography.titleSmall)
                    Text("${formatDuration(w.end - w.start)} · Ø ${w.avgHr} · max. ${w.maxHr} bpm", style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}

@Composable
private fun SleepPage(d: DayData, nav: NavHostController) {
    val n = d.night
    if (n == null) {
        NoData("Für die Nacht zu diesem Tag liegen keine Schlafdaten vor.")
        OutlinedButton(onClick = { nav.navigate(Routes.sleepEdit(d.date.toEpochDay())) }, modifier = Modifier.fillMaxWidth()) { Text("Schlaf selbst eintragen") }
        return
    }
    Panel {
        Text("Nacht ${clock(n.start)} – ${clock(n.end)}", style = MaterialTheme.typography.titleMedium)
        Hypnogram(n.stages)
        Row {
            Metric("Schlaf", formatMinutes(n.asleepMinutes), Modifier.weight(1f))
            Metric("Tief", formatMinutes(n.minutes(SleepStage.DEEP)), Modifier.weight(1f), color = Palette.Deep)
            Metric("REM", formatMinutes(n.minutes(SleepStage.REM)), Modifier.weight(1f), color = Palette.Rem)
            Metric("Score", "${n.score}", Modifier.weight(1f))
        }
        Text(
            when {
                n.edited -> "Von dir korrigiert"
                n.estimated -> "Phasen geschätzt aus Puls und Bewegung"
                else -> "Phasen vom Strap"
            } + (n.avgHeartRate?.let { " · Puls Ø $it, min. ${n.minHeartRate ?: "–"}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { nav.navigate(Routes.sleepEdit(d.date.toEpochDay())) }, modifier = Modifier.weight(1f)) { Text("Bearbeiten") }
        OutlinedButton(onClick = { nav.navigateToTab(Routes.SLEEP) }, modifier = Modifier.weight(1f)) { Text("Schlafansicht") }
    }
}

@Composable
private fun StepsPage(d: DayData) {
    val zone = ZoneId.systemDefault()
    val hourly = FloatArray(24)
    d.activity.forEach { s -> hourly[Instant.ofEpochSecond(s.timestamp).atZone(zone).hour] += s.steps.toFloat() }
    val steps = d.activity.sumOf { it.steps }
    val profile = LocalContext.current.app.prefs.profile
    Panel {
        Text("Schritte pro Stunde", style = MaterialTheme.typography.titleMedium)
        BarChart(hourly.toList(), Palette.Steps, labels = List(24) { if (it % 3 == 0) "$it" else "" }, highlight = hourly.indices.maxByOrNull { hourly[it] }?.takeIf { hourly[it] > 0 })
    }
    Panel {
        Row {
            Metric("Schritte", steps.fmt(), Modifier.weight(1f), color = Palette.Steps)
            Metric("Ziel", "${steps * 100 / profile.stepGoal.coerceAtLeast(1)} %", Modifier.weight(1f))
            Metric("Strecke", "%.1f km".format(steps * profile.heightCm * 0.414 / 100_000), Modifier.weight(1f))
            Metric("Aktiv", formatMinutes(d.activity.count { it.steps >= 30 }), Modifier.weight(1f))
        }
    }
}

@Composable
private fun StressPage(d: DayData) {
    if (d.stress.isEmpty()) {
        NoData("Keine Stresswerte an diesem Tag.")
        return
    }
    val values = d.stress.map { it.stress }
    Panel {
        Text("Stress", style = MaterialTheme.typography.titleMedium)
        val zoom = rememberTimeZoom(d.from, d.to)
        LineChart(
            listOf(Series(d.stress.map { it.timestamp to it.stress.toFloat() }, Palette.Stress, fill = true)), zoom.start, zoom.end,
            modifier = Modifier.fillMaxWidth().height(200.dp), zoom = zoom, minY = 0f, maxY = 100f, gapSeconds = 1800,
            areas = listOf(Triple(0f, 40f, stressColor(20)), Triple(40f, 60f, stressColor(50)), Triple(60f, 80f, stressColor(70)), Triple(80f, 100f, stressColor(90))),
        )
        ZoomHint(zoom)
    }
    Panel {
        val parts = listOf(0 until 40, 40 until 60, 60 until 80, 80..100).map { r -> values.count { it in r } }
        StackedBar(parts.mapIndexed { i, v -> v.toFloat() to stressColor(listOf(20, 50, 70, 90)[i]) })
        Row {
            Metric("Ø", "${values.average().roundToInt()}", Modifier.weight(1f))
            Metric("Max", "${values.max()}", Modifier.weight(1f))
            Metric("Entspannt", "${parts[0] * 100 / values.size} %", Modifier.weight(1f), color = stressColor(20))
            Metric("Hoch", "${parts[3] * 100 / values.size} %", Modifier.weight(1f), color = stressColor(90))
        }
    }
}

@Composable
private fun ValuePage(
    d: DayData,
    values: List<ValueSample>,
    title: String,
    unit: String,
    color: Color,
    scale: Double,
    decimals: Int = 0,
    minY: Float? = null,
    maxY: Float? = null,
) {
    if (values.isEmpty()) {
        NoData("Keine Werte für $title an diesem Tag.")
        return
    }
    fun f(v: Double) = "%.${decimals}f".format(v)
    val scaled = values.map { it.value * scale }
    Panel {
        Text(title, style = MaterialTheme.typography.titleMedium)
        val zoom = rememberTimeZoom(d.from, d.to)
        LineChart(
            listOf(Series(values.map { it.timestamp to (it.value * scale).toFloat() }, color, fill = true)), zoom.start, zoom.end,
            modifier = Modifier.fillMaxWidth().height(200.dp), zoom = zoom, unit = unit, gapSeconds = 3 * 3600, minY = minY, maxY = maxY,
            format = { f(it.toDouble()) },
        )
        ZoomHint(zoom)
    }
    Panel {
        Row {
            Metric("Ø", "${f(scaled.average())} $unit", Modifier.weight(1f))
            Metric("Min", f(scaled.min()), Modifier.weight(1f))
            Metric("Max", f(scaled.max()), Modifier.weight(1f))
            Metric("Werte", "${values.size}", Modifier.weight(1f))
        }
    }
}

@Composable
private fun PaiPage(d: DayData) {
    val zone = ZoneId.systemDefault()
    val latest = d.pai.lastOrNull()
    if (latest == null) {
        NoData("Keine PAI-Werte.")
        return
    }
    val byDay = d.pai.associateBy { Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }
    val days = (6 downTo 0).map { d.date.minusDays(it.toLong()) }
    Panel {
        Row {
            Metric("PAI (7 Tage)", "${latest.total.toInt()}", Modifier.weight(1f), color = Palette.Pai)
            Metric("Heute verdient", "%.1f".format(latest.today), Modifier.weight(1f))
        }
        BarChart(days.map { byDay[it]?.total ?: 0f }, Palette.Pai, labels = days.map { it.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.GERMANY).take(2) }, goal = 100f)
        Text("Minuten mit erhöhtem Puls: leicht ${latest.lowMinutes} · mittel ${latest.moderateMinutes} · hoch ${latest.highMinutes}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BatteryPage(d: DayData) {
    if (d.battery.isEmpty()) {
        NoData("Noch kein Akkuverlauf gespeichert.")
        return
    }
    Panel {
        Text("Akku (3 Tage)", style = MaterialTheme.typography.titleMedium)
        val zoom = rememberTimeZoom(d.from - 2 * 86400, d.to, minSpan = 3600)
        LineChart(
            listOf(Series(d.battery.map { it.timestamp to it.value.toFloat() }, Palette.Battery, fill = true)), zoom.start, zoom.end,
            modifier = Modifier.fillMaxWidth().height(200.dp), zoom = zoom, unit = "%", gapSeconds = 12 * 3600, minY = 0f, maxY = 100f,
        )
    }
    Panel {
        Row {
            Metric("Aktuell", "${d.battery.last().value} %", Modifier.weight(1f), color = Palette.Battery)
            Metric("Reicht noch", BatteryForecast.daysLeft(d.battery)?.let { "%.1f Tage".format(it) } ?: "–", Modifier.weight(1f))
        }
    }
}

private val weekDay = DateTimeFormatter.ofPattern("EEE d. MMM", java.util.Locale.GERMANY)

@Composable
private fun SleepWeekPage(d: DayData, onOpen: (LocalDate) -> Unit) {
    val days = (6 downTo 0).map { d.date.minusDays(it.toLong()) }
    val goal = LocalContext.current.app.prefs.profile.sleepGoalMinutes
    val nights = days.mapNotNull { d.weekNights[it] }
    Panel {
        Text("Schlaf der letzten 7 Nächte", style = MaterialTheme.typography.titleMedium)
        de.waschbeken.heliolink.ui.StackedBarChart(
            days.map { day ->
                val n = d.weekNights[day]
                if (n == null) emptyList() else listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE).map { n.minutes(it) / 60f to Palette.stage(it) }
            },
            days.map { it.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.GERMANY).take(2) },
            modifier = Modifier.fillMaxWidth().height(200.dp),
            goal = goal / 60f,
            format = { "%.1f h".format(it) },
            tooltip = { i ->
                val n = d.weekNights[days[i]]
                if (n == null) listOf(days[i].format(weekDay), "keine Nacht")
                else listOf(
                    days[i].format(weekDay) + " · Score ${n.score}",
                    "${clock(n.start)} – ${clock(n.end)}",
                    "Schlaf ${formatMinutes(n.asleepMinutes)}",
                    "Tief ${formatMinutes(n.deep)} · REM ${formatMinutes(n.rem)}",
                    "Leicht ${formatMinutes(n.light)} · Wach ${formatMinutes(n.awake)}",
                )
            },
        )
        Legend(listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE).map { de.waschbeken.heliolink.ui.stageName(it) to Palette.stage(it) })
    }
    if (nights.isNotEmpty()) {
        Panel {
            Row {
                Metric("Ø Schlaf", formatMinutes(nights.sumOf { it.asleepMinutes } / nights.size), Modifier.weight(1f), color = Palette.Sleep)
                Metric("Ø Tief", formatMinutes(nights.sumOf { it.deep } / nights.size), Modifier.weight(1f), color = Palette.Deep)
                Metric("Ø REM", formatMinutes(nights.sumOf { it.rem } / nights.size), Modifier.weight(1f), color = Palette.Rem)
                Metric("Ø Score", "${nights.sumOf { it.score } / nights.size}", Modifier.weight(1f))
            }
        }
        val sections = de.waschbeken.heliolink.ui.rememberSections()
        val open = sections.isOpen("data-week-nights")
        de.waschbeken.heliolink.ui.CollapsibleHeader("Nächte", open, count = nights.size, summary = "Tippen zum Aufklappen") { sections.toggle("data-week-nights") }
        if (open) nights.sortedByDescending { it.date }.forEach { n ->
            Panel(onClick = { onOpen(n.date) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(n.date.format(weekDay), style = MaterialTheme.typography.titleSmall)
                        Text("${clock(n.start)} – ${clock(n.end)} · Tief ${formatMinutes(n.deep)} · REM ${formatMinutes(n.rem)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(formatMinutes(n.asleepMinutes), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

@Composable
private fun StepsWeekPage(d: DayData) {
    val days = (6 downTo 0).map { d.date.minusDays(it.toLong()) }
    val profile = LocalContext.current.app.prefs.profile
    val steps = days.map { d.weekTotals[it]?.steps ?: 0 }
    Panel {
        Text("Schritte der letzten 7 Tage", style = MaterialTheme.typography.titleMedium)
        BarChart(
            steps.map { it.toFloat() }, Palette.Steps,
            modifier = Modifier.fillMaxWidth().height(200.dp),
            labels = days.map { it.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.GERMANY).take(2) },
            goal = profile.stepGoal.toFloat(),
            tooltip = { i ->
                val t = d.weekTotals[days[i]]
                days[i].format(weekDay) + ": ${steps[i].fmt()} Schritte" + (t?.let { " · ${formatMinutes(it.activeMinutes)} aktiv" } ?: "")
            },
        )
    }
    Panel {
        Row {
            Metric("Gesamt", steps.sum().fmt(), Modifier.weight(1f), color = Palette.Steps)
            Metric("Ø Tag", (steps.sum() / 7).fmt(), Modifier.weight(1f))
            Metric("Ziel erreicht", "${steps.count { it >= profile.stepGoal }} Tage", Modifier.weight(1f))
            Metric("Strecke", "%.0f km".format(steps.sum() * profile.heightCm * 0.414 / 100_000), Modifier.weight(1f))
        }
    }
}
