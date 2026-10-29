package de.waschbeken.heliolink.ui.sleep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.ActivitySample
import de.waschbeken.heliolink.core.SleepSession
import de.waschbeken.heliolink.core.SleepStage
import de.waschbeken.heliolink.core.ValueSample
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.data.Queries
import de.waschbeken.heliolink.ui.DateNavigator
import de.waschbeken.heliolink.ui.German
import de.waschbeken.heliolink.ui.Hypnogram
import de.waschbeken.heliolink.ui.LineChart
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.Period
import de.waschbeken.heliolink.ui.PeriodSelector
import de.waschbeken.heliolink.ui.ProgressRing
import de.waschbeken.heliolink.ui.Routes
import de.waschbeken.heliolink.ui.SectionHeader
import de.waschbeken.heliolink.ui.Series
import de.waschbeken.heliolink.ui.StackedBar
import de.waschbeken.heliolink.ui.StackedBarChart
import de.waschbeken.heliolink.ui.StatTile
import de.waschbeken.heliolink.ui.dbValue
import de.waschbeken.heliolink.ui.formatHours
import de.waschbeken.heliolink.ui.formatMinutes
import de.waschbeken.heliolink.ui.describe
import de.waschbeken.heliolink.ui.scoreColor
import de.waschbeken.heliolink.ui.stageName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlin.math.abs
import kotlin.math.roundToInt

private class NightData(
    val night: SleepSession?,
    val samples: List<ActivitySample>,
    val spo2: List<ValueSample>,
    val hrv: List<ValueSample>,
    val breath: List<ValueSample>,
    val temperature: List<ValueSample>,
    val tempBaseline: Double?,
    val naps: List<SleepSession> = emptyList(),
    /** The strap's own oxygen drop summary of this night. */
    val odi: de.waschbeken.heliolink.core.OdiNight? = null,
)

private class RangeData(val nights: Map<LocalDate, Queries.NightSummary>, val period: Period, val date: LocalDate)

private fun loadNight(db: HealthDb, date: LocalDate): NightData {
    val zone = ZoneId.systemDefault()
    val (start, end) = Queries.nightWindow(date, zone)
    val night = Queries.night(db, date, zone)
    val s = night?.start?.minus(1800) ?: start
    val e = night?.end?.plus(1800) ?: end
    val temps = db.values(HealthDb.TEMPERATURE, start - 21 * 86400, start)
    val baseline = temps.filter { Instant.ofEpochSecond(it.timestamp).atZone(zone).hour in 0..5 }.map { it.value / 100.0 }
        .takeIf { it.size > 60 }?.average()
    return NightData(
        night = night,
        samples = db.activity(s, e),
        spo2 = db.values(HealthDb.SPO2, s, e),
        hrv = db.values(HealthDb.HRV, s, e),
        breath = db.values(HealthDb.RESPIRATION, s, e),
        temperature = db.values(HealthDb.TEMPERATURE, s, e),
        tempBaseline = baseline,
        naps = Queries.naps(db, date, zone, night),
        odi = date.atStartOfDay(zone).toEpochSecond().let { midnight -> db.odi(midnight - 12 * 3600, midnight + 12 * 3600).lastOrNull() },
    )
}

private val time = DateTimeFormatter.ofPattern("HH:mm")

private fun clock(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(time)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepScreen(nav: NavHostController) {
    val app = LocalContext.current.app
    val state by app.repo.state.collectAsStateWithLifecycle()
    var periodName by rememberSaveable { mutableStateOf(Period.DAY.name) }
    var dateEpoch by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    val period = Period.valueOf(periodName)
    val date = LocalDate.ofEpochDay(dateEpoch)
    val goal = app.prefs.profile.sleepGoalMinutes
    val night by de.waschbeken.heliolink.ui.cachedDbValue("sleep-night-$date-$period", state.dataVersion) { if (period == Period.DAY) loadNight(it, date) else null }
    val range by de.waschbeken.heliolink.ui.cachedDbValue("sleep-range-$date-$period", state.dataVersion) {
        val (from, to) = period.range(date)
        RangeData(Queries.nightSummaries(it, from, minOf(to, LocalDate.now()), ZoneId.systemDefault()), period, date)
    }

    val askCheck = app.prefs.sleepAskCheck && app.prefs.sleepCheckedDay != LocalDate.now().toEpochDay()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schlaf") },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.ALARMS) }) {
                        Icon(Icons.Default.Alarm, contentDescription = "Wecker")
                    }
                    if (period == Period.DAY) {
                        IconButton(onClick = { nav.navigate(Routes.sleepEdit(date.toEpochDay())) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Schlaf bearbeiten")
                        }
                    }
                    IconButton(onClick = { nav.navigate(Routes.settings("sleep")) }) {
                        Icon(Icons.Default.Tune, contentDescription = "Schlaf-Einstellungen")
                    }
                },
            )
        },
    ) { padding ->
        de.waschbeken.heliolink.ui.SyncRefreshBox(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { PeriodSelector(period, { periodName = it.name }) }
                item { DateNavigator(period, date) { dateEpoch = it.toEpochDay() } }
                if (period == Period.DAY) {
                    val n = night
                    when {
                        n == null -> item { Text("Lade …") }
                        n.night == null -> {
                            item { NoSleep { nav.navigate(Routes.sleepEdit(date.toEpochDay())) } }
                            if (n.naps.isNotEmpty()) item { Naps(n.naps) }
                            item { SleepDiary(date) }
                        }
                        else -> {
                            nightItems(
                                n, n.night, goal,
                                onEstimatedInfo = { nav.navigate(Routes.settings("sleep")) },
                                onEdit = { nav.navigate(Routes.sleepEdit(date.toEpochDay())) },
                                askCheck = askCheck && date == LocalDate.now() && !n.night.edited,
                                onConfirm = { app.prefs.sleepCheckedDay = LocalDate.now().toEpochDay() },
                            )
                            if (n.naps.isNotEmpty()) item { Naps(n.naps) }
                            item { SleepDiary(date) }
                        }
                    }
                } else {
                    range?.takeIf { it.period == period && it.date == date }?.let { r ->
                        item {
                            RangeContent(r, period, goal) { d ->
                                periodName = Period.DAY.name
                                dateEpoch = d.toEpochDay()
                            }
                        }
                    }
                    item { BedtimePanel(state.dataVersion) }
                    item { TagEffects(state.dataVersion) }
                }
            }
        }
    }
}

/** Oxygen drops during the night, a hint for breathing pauses: the strap's own summary and HelioLink's count. */
@Composable
private fun OxygenPanel(o: de.waschbeken.heliolink.core.OxygenDrops.Result?, strap: de.waschbeken.heliolink.core.OdiNight?) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sauerstoff-Abfälle", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (o != null) {
                Text(o.rating, style = MaterialTheme.typography.labelLarge, color = if (o.perHour >= 15) MaterialTheme.colorScheme.error else Palette.Spo2)
            }
        }
        if (strap != null) {
            Text(
                if (strap.validTime > 0) {
                    "Auswertung des Straps: ${strap.drops} Abfälle" + (strap.score?.let { " · Bewertung $it" } ?: "") +
                        (strap.validTime.takeIf { it in 60..86_400 }?.let { " · ${formatMinutes((it / 60).toInt())} auswertbar" } ?: "")
                } else {
                    "Der Strap konnte diese Nacht nicht auswerten (zu wenig ruhige Messzeit)."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (o == null) {
            Text("Der Strap wertet selbst aus, wie oft der Sauerstoff im Schlaf abfällt. Keine Diagnose.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Panel
        }
        Text(
            (if (strap != null) "Gezählt von HelioLink: " else "") +
                "${o.drops} Abfälle um mindestens ${de.waschbeken.heliolink.core.OxygenDrops.DROP} % · " + "%.1f".format(o.perHour) +
                " pro Stunde · niedrigster Wert ${o.lowest} %" + if (o.minutesBelow90 > 0) " · ${o.minutesBelow90} min unter 90 %" else "",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            (if (o.perHour >= 15) "Viele Abfälle können auf Atemaussetzer (Schlafapnoe) hindeuten – vor allem mit Schnarchen oder Müdigkeit am Tag. Wenn das öfter so ist, sprich ärztlich darüber. " else "") +
                "Gemessen etwa alle ${o.spacingMinutes.roundToInt().coerceAtLeast(1)} min, kurze Abfälle können fehlen" +
                (if (o.spacingMinutes > 4) " – genauer wird es mit „Atmungsqualität“ unter Einstellungen → Messungen" else "") + ". Keine Diagnose.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** When falling asleep goes along with the best nights (last 90 days). */
@Composable
private fun BedtimePanel(dataVersion: Int) {
    val zone = ZoneId.systemDefault()
    val result by dbValue(dataVersion) { db ->
        val today = LocalDate.now()
        val nights = Queries.nightSummaries(db, today.minusDays(90), today, zone).values
        nights.size to de.waschbeken.heliolink.core.BedtimeInsight.analyze(
            nights.map { Instant.ofEpochSecond(it.start).atZone(zone).toLocalTime().let { t -> t.hour * 60 + t.minute } to it.score },
        )
    }
    val (count, r) = result ?: return
    Panel {
        Text("Beste Zeit zum Einschlafen", style = MaterialTheme.typography.titleMedium)
        if (r == null) {
            Text(
                if (count < 10) "Ab 10 Nächten zeigt sich hier, zu welcher Uhrzeit deine besten Nächte beginnen." else
                    "Bisher kein klarer Unterschied – deine Nächte sind unabhängig von der Uhrzeit ähnlich gut.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Panel
        }
        fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
        Text("${hm(r.from)}–${hm(r.to)} Uhr", style = MaterialTheme.typography.headlineSmall, color = Palette.Sleep)
        Text(
            "Score Ø ${r.score.roundToInt()} statt ${r.otherScore.roundToInt()} in den übrigen Nächten (${r.nights} von ${r.total} Nächten der letzten 90 Tage).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Ein Zusammenhang, kein Beweis: Späte Nächte haben oft auch andere Gründe (Wochenende, Ausgehen).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Naps(naps: List<SleepSession>) {
    Panel {
        Text("Nickerchen", style = MaterialTheme.typography.titleMedium)
        naps.forEach { s ->
            Row {
                Text("${clock(s.start)} – ${clock(s.end)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(formatMinutes(s.asleepMinutes), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            "Kurzer Schlaf am Tag – bis 20 Minuten erfrischt, länger kann die Nacht verkürzen.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoSleep(onAdd: () -> Unit) {
    Panel {
        Text("Für diese Nacht liegen keine Schlafdaten vor.", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Trage den Strap nachts und synchronisiere morgens. Tipp: Unter Einstellungen → Messungen " +
                "„Schlaf mit hoher Genauigkeit“ einschalten.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onAdd) { Text("Schlaf selbst eintragen") }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.nightItems(
    d: NightData,
    n: SleepSession,
    goal: Int,
    onEstimatedInfo: () -> Unit,
    onEdit: () -> Unit,
    askCheck: Boolean,
    onConfirm: () -> Unit,
) {
    item {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                    ProgressRing(n.score / 100f, scoreColor(n.score), Modifier.size(92.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${n.score}", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text("Score", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.width(18.dp))
                Column {
                    Text(formatHours(n.asleepMinutes), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text("geschlafen · ${clock(n.start)} – ${clock(n.end)}", style = MaterialTheme.typography.bodyMedium)
                    val diff = n.asleepMinutes - goal
                    Text(
                        if (diff >= 0) "Ziel erreicht (+${formatMinutes(diff)})" else "${formatMinutes(-diff)} unter deinem Ziel",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (n.strapScore == null) {
                        Text(
                            n.scoreParts.joinToString(" · ") { (name, points, max) -> "$name $points/$max" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
    if (askCheck) {
        item {
            var answered by remember { mutableStateOf(false) }
            if (!answered) {
                Panel(color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text("Stimmt deine Nacht?", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Wenn Einschlafen, Aufwachen oder Wachphasen nicht passen, korrigiere sie – das macht Score und Statistik genauer.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            answered = true
                            onConfirm()
                        }) { Text("Passt") }
                        OutlinedButton(onClick = onEdit) { Text("Korrigieren") }
                    }
                }
            }
        }
    }
    if (n.edited) {
        item {
            Panel(onClick = onEdit) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Von dir korrigiert – tippen zum Bearbeiten", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (n.estimated) {
        item {
            Panel(color = MaterialTheme.colorScheme.secondaryContainer, onClick = onEstimatedInfo) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Phasen geschätzt: Für diese Nacht hat der Strap keine eigenen Schlafphasen geliefert. HelioLink schätzt sie aus Puls " +
                            "und Bewegung (trifft etwa 7 von 10 Minuten). Tippen für die Schlaf-Einstellungen, oben rechts kannst du die Nacht korrigieren.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    item {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Schlafphasen", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onEdit) { Text("Korrigieren") }
            }
            Hypnogram(n.stages)
            Text(
                "Finger halten: Phase mit Beginn, Ende und Dauer · zwei Finger oder Doppeltipp: zoomen",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item {
        Panel {
            val total = n.durationMinutes.coerceAtLeast(1)
            StackedBar(listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE).map { n.minutes(it).toFloat() to Palette.stage(it) }, Modifier.fillMaxWidth().height(12.dp))
            val hints = mapOf(
                SleepStage.DEEP to "üblich 13–23 %",
                SleepStage.LIGHT to "üblich 45–55 %",
                SleepStage.REM to "üblich 20–25 %",
                SleepStage.AWAKE to "kurze Wachphasen sind normal",
            )
            listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE).forEach { s ->
                val minutes = n.minutes(s)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).padding(0.dp)) { StackedBar(listOf(1f to Palette.stage(s)), Modifier.size(10.dp)) }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stageName(s), style = MaterialTheme.typography.bodyMedium)
                        Text(hints.getValue(s), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${formatMinutes(minutes)} · ${minutes * 100 / total} %", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    item {
        val wakeUps = n.stages.zipWithNext().count { (a, b) -> a.second != SleepStage.AWAKE && b.second == SleepStage.AWAKE }
        val onset = n.stages.indexOfFirst { it.second != SleepStage.AWAKE }.coerceAtLeast(0)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Eingeschlafen", clock(n.start + onset * 60L), Modifier.weight(1f))
            StatTile("Aufgewacht", clock(n.end), Modifier.weight(1f))
            StatTile("Wachphasen", "$wakeUps", Modifier.weight(1f))
        }
    }
    item { SectionHeader("Während der Nacht") }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Puls Ø / min", "${n.avgHeartRate ?: "–"} / ${n.minHeartRate ?: "–"}", Modifier.weight(1f), tint = Palette.Heart)
            StatTile("HRV", d.hrv.map { it.value }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()?.let { "$it ms" } ?: "–", Modifier.weight(1f))
        }
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                "SpO₂ Ø / min",
                d.spo2.map { it.value }.takeIf { it.isNotEmpty() }?.let { "${it.average().roundToInt()} / ${it.min()} %" } ?: "–",
                Modifier.weight(1f),
            )
            StatTile("Atemfrequenz", d.breath.map { it.value }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()?.let { "$it /min" } ?: "–", Modifier.weight(1f))
        }
    }
    val drops = de.waschbeken.heliolink.core.OxygenDrops.analyze(Queries.during(d.spo2, n), n.asleepMinutes)
    if (drops != null || d.odi != null) item { OxygenPanel(drops, d.odi) }
    val tempAvg = d.temperature.map { it.value / 100.0 }.takeIf { it.isNotEmpty() }?.average()
    if (tempAvg != null) {
        item {
            val dev = d.tempBaseline?.let { tempAvg - it }
            StatTile(
                "Hauttemperatur", "%.1f °C".format(tempAvg), Modifier.fillMaxWidth(),
                caption = dev?.let { "%+.1f °C zu deinem Schnitt".format(it) + if (abs(it) >= 0.5) " – auffällig" else "" },
            )
        }
    }
    item {
        NightCharts(
            n,
            heartRate = d.samples.filter { it.heartRate > 0 }.map { it.timestamp to it.heartRate.toFloat() },
            temperature = d.temperature, breath = d.breath, spo2 = d.spo2, hrv = d.hrv, tempBaseline = d.tempBaseline,
        )
    }
}

@Composable
private fun RangeContent(r: RangeData, period: Period, goal: Int, onOpen: (LocalDate) -> Unit) {
    val zone = ZoneId.systemDefault()
    val buckets = period.buckets(r.date)
    val nights = r.nights.values.toList()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            Text("Schlafdauer und Phasen" + if (period.long) " (Ø je Nacht)" else "", style = MaterialTheme.typography.titleMedium)
            StackedBarChart(
                buckets.map { b ->
                    val inB = nights.filter { it.date in b }
                    if (inB.isEmpty()) emptyList() else listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE)
                        .map { st -> inB.sumOf { it.minutes(st) } / 60f / inB.size to Palette.stage(st) }
                },
                buckets.map { it.label },
                goal = goal / 60f,
                format = { "%.1f h".format(it) },
                tooltip = { i ->
                    val b = buckets[i]
                    val inB = nights.filter { it.date in b }
                    if (inB.isEmpty()) listOf(b.describe(), "keine Nacht")
                    else listOf(
                        b.describe() + if (inB.size > 1) " (Ø ${inB.size} Nächte)" else "",
                        "Schlaf ${formatMinutes(inB.sumOf { it.asleepMinutes } / inB.size)}",
                    ) + listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE).map { st ->
                        "${stageName(st)} ${formatMinutes(inB.sumOf { it.minutes(st) } / inB.size)}"
                    } + if (inB.size == 1) listOf("${clock(inB[0].start)} – ${clock(inB[0].end)} · Score ${inB[0].score}") else emptyList()
                },
            )
        }
        if (period.long && nights.size >= 2) {
            Panel {
                Text("Score", style = MaterialTheme.typography.titleMedium)
                de.waschbeken.heliolink.ui.TrendChart(
                    de.waschbeken.heliolink.core.Buckets.average(buckets) { day -> r.nights[day]?.score?.toDouble() },
                    buckets.map { it.label }, Palette.Sleep,
                    baseline = nights.map { it.score }.average().toFloat(),
                )
            }
        }
        if (nights.isEmpty()) {
            Panel { Text("Keine Nächte in diesem Zeitraum.", style = MaterialTheme.typography.bodyMedium) }
            return@Column
        }
        val avgAsleep = nights.map { it.asleepMinutes }.average().roundToInt()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Ø Schlaf", formatHours(avgAsleep), Modifier.weight(1f), icon = Icons.Default.Bedtime, tint = Palette.Sleep)
            StatTile("Ø Score", "${nights.map { it.score }.average().roundToInt()}", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Ø Tiefschlaf", formatMinutes(nights.map { it.minutes(SleepStage.DEEP) }.average().roundToInt()), Modifier.weight(1f), tint = Palette.Deep)
            StatTile("Ø REM", formatMinutes(nights.map { it.minutes(SleepStage.REM) }.average().roundToInt()), Modifier.weight(1f), tint = Palette.Rem)
        }
        // consistency: spread of bedtimes and wake-up times
        fun minutesOfDay(epoch: Long): Int {
            val t = Instant.ofEpochSecond(epoch).atZone(zone)
            val m = t.hour * 60 + t.minute
            return if (m < 12 * 60) m + 24 * 60 else m // after midnight counts as late evening
        }
        val bed = nights.map { minutesOfDay(it.start) }
        val wake = nights.map { Instant.ofEpochSecond(it.end).atZone(zone).let { t -> t.hour * 60 + t.minute } }
        fun hm(m: Int) = "%02d:%02d".format((m / 60) % 24, m % 60)
        fun spread(v: List<Int>) = if (v.size < 2) 0 else kotlin.math.sqrt(v.map { (it - v.average()) * (it - v.average()) }.average()).roundToInt()
        Panel {
            Text("Regelmäßigkeit", style = MaterialTheme.typography.titleMedium)
            Text("Ø ins Bett: ${hm(bed.average().roundToInt())} (± ${spread(bed)} min)", style = MaterialTheme.typography.bodyMedium)
            Text("Ø aufgestanden: ${hm(wake.average().roundToInt())} (± ${spread(wake)} min)", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (spread(bed) <= 30) "Sehr regelmäßig – gut für erholsamen Schlaf." else "Feste Schlafenszeiten verbessern meist Schlafqualität und Erholung.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val debt = nights.sumOf { (goal - it.asleepMinutes).coerceAtLeast(0) }
            if (debt > 0) Text("Schlafdefizit zum Ziel: ${formatMinutes(debt)}", style = MaterialTheme.typography.bodyMedium)
        }
        if (!period.long) Text("Nächte", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (!period.long) r.nights.entries.sortedByDescending { it.key }.forEach { (d, n) ->
            Panel(onClick = { onOpen(d) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d. MMM", German)), style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${clock(n.start)} – ${clock(n.end)}" + when {
                                n.edited -> " · korrigiert"
                                n.estimated -> " · geschätzt"
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(formatHours(n.asleepMinutes), style = MaterialTheme.typography.titleSmall)
                        Text("Score ${n.score}", style = MaterialTheme.typography.labelSmall, color = scoreColor(n.score))
                    }
                }
            }
        }
    }
}
