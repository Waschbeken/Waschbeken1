package de.waschbeken.heliolink.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.waschbeken.heliolink.core.HeartRateRecovery
import de.waschbeken.heliolink.core.IntervalPlan
import de.waschbeken.heliolink.core.TrainingLoad
import de.waschbeken.heliolink.core.Vo2Max
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.data.UserProfile
import de.waschbeken.heliolink.ui.BarChart
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.StatTile
import de.waschbeken.heliolink.ui.formatDuration
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Load, recovery and fitness estimate for the training page. */
class TrainingSummary(
    val balance: TrainingLoad.Balance?,
    val recoveryHours: Double,
    /** Load per day of the last 14 days (oldest first). */
    val daily: List<Pair<LocalDate, Double>>,
    val vo2: Double?,
    val restingHr: Int,
    val maxHr: Int,
    val maxFromProfile: Boolean,
)

fun loadTrainingSummary(db: HealthDb, profile: UserProfile): TrainingSummary {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val now = System.currentTimeMillis() / 1000
    val start = today.minusDays(34).atStartOfDay(zone).toEpochSecond()
    val strapResting = db.values(HealthDb.RESTING_HR, start, now + 60).map { it.value }.filter { it in 30..110 }.sorted()
    val rest = profile.restingHrManual.takeIf { it > 0 } ?: strapResting.takeIf { it.size >= 3 }?.let { it[it.size / 2] } ?: 60
    val max = profile.maxHeartRate
    // Heart rate per minute; workouts (every second) replace the strap's minute values.
    val minutes = HashMap<Long, Int>()
    for (s in db.activity(start, now + 60)) if (s.heartRate in 30..230 && !s.notWorn) minutes[s.timestamp / 60] = s.heartRate
    for (w in db.workouts().filter { it.end > start }) {
        db.workoutHr(w.id).groupBy { it.timestamp / 60 }.forEach { (m, list) -> minutes[m] = list.map { it.bpm }.average().roundToInt() }
    }
    val points = minutes.map { (m, hr) -> m * 60 to hr }
    val daily = HashMap<LocalDate, Double>()
    for ((t, hr) in points) {
        val d = Instant.ofEpochSecond(t).atZone(zone).toLocalDate()
        daily[d] = (daily[d] ?: 0.0) + TrainingLoad.minute(hr, rest, max, profile.male)
    }
    val sessions = TrainingLoad.sessions(points.filter { it.first > now - 80 * 3600 }, rest, max, profile.male)
    return TrainingSummary(
        balance = TrainingLoad.balance(daily, today),
        recoveryHours = TrainingLoad.recoveryHours(sessions, now),
        daily = (13 downTo 0).map { today.minusDays(it.toLong()).let { d -> d to (daily[d] ?: 0.0) } },
        vo2 = Vo2Max.estimate(max, rest),
        restingHr = rest,
        maxHr = max,
        maxFromProfile = profile.maxHrOverride > 0,
    )
}

@Composable
fun TrainingSummaryPanel(t: TrainingSummary, profile: UserProfile) {
    Panel {
        Text("Belastung & Erholung", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val h = t.recoveryHours.roundToInt()
            StatTile("Erholung", if (h <= 0) "erholt" else "noch $h h", Modifier.weight(1f), tint = if (h > 24) Palette.Stress else Palette.Hrv)
            StatTile("Belastung 7 T.", t.balance?.status?.label ?: "–", Modifier.weight(1f), tint = when (t.balance?.status) {
                TrainingLoad.Status.OVERLOAD -> MaterialTheme.colorScheme.error
                TrainingLoad.Status.HIGH -> Palette.Stress
                TrainingLoad.Status.OPTIMAL -> Palette.Hrv
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            })
        }
        BarChart(
            t.daily.map { it.second.toFloat() }, Palette.Heart,
            labels = t.daily.map { it.first.dayOfMonth.toString() },
            format = { it.roundToInt().toString() },
            tooltip = { i -> "${t.daily[i].first.dayOfMonth}.${t.daily[i].first.monthValue}.: Belastung ${t.daily[i].second.roundToInt()}" },
        )
        Text(
            t.balance?.let { b -> "Letzte 7 Tage Ø ${b.acute.roundToInt()} pro Tag, die 4 Wochen davor Ø ${b.chronic.roundToInt()}. ${b.status.text}" }
                ?: "Belastung zählt Minuten mit deutlich erhöhtem Puls (ab 40 % der Pulsreserve). Ab zwei Wochen Daten erscheint der Vergleich.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        t.vo2?.let { v ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Fitness (VO₂max)", "${v.roundToInt()}", Modifier.weight(1f), caption = Vo2Max.rating(v, profile.age, profile.male))
                StatTile("Ruhe / Max.", "${t.restingHr} / ${t.maxHr}", Modifier.weight(1f), caption = "bpm")
            }
            Text(
                "Grobe Schätzung aus Ruhepuls und Maximalpuls (15,3 × Max ÷ Ruhe). " +
                    if (t.maxFromProfile) "Sinkt dein Ruhepuls, steigt der Wert." else "Der Maximalpuls ist aus dem Alter geschätzt – trag ihn unter Training & Zonen ein, wenn du ihn kennst.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Interval choice in the start card. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IntervalChooser(
    plan: IntervalPlan,
    presets: List<IntervalPlan> = IntervalPlan.PRESETS,
    showHint: Boolean = true,
    onChange: (IntervalPlan) -> Unit,
) {
    var custom by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (presets + listOf(plan).filter { it !in presets }).forEach { p ->
            FilterChip(selected = p == plan, onClick = { onChange(p) }, label = { Text(p.label) })
        }
        AssistChip(onClick = { custom = true }, label = { Text("Eigene …") })
    }
    Text(
        "Gesamt ${formatDuration(plan.totalSeconds.toLong())}." +
            if (showHint) " Der Strap vibriert: 2× kurz = los, 1× lang = Pause, 3× = fertig." else "",
        style = MaterialTheme.typography.bodySmall,
    )
    if (custom) {
        var work by remember { mutableStateOf(plan.workSeconds.toString()) }
        var rest by remember { mutableStateOf(plan.restSeconds.toString()) }
        var rounds by remember { mutableStateOf(plan.rounds.toString()) }
        val p = IntervalPlan.of(work.toIntOrNull() ?: 0, rest.toIntOrNull() ?: -1, rounds.toIntOrNull() ?: 0)
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text("Eigene Intervalle") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val setWork: (String) -> Unit = { work = it }
                    val setRest: (String) -> Unit = { rest = it }
                    val setRounds: (String) -> Unit = { rounds = it }
                    listOf(
                        Triple("Belastung (Sekunden)", work, setWork),
                        Triple("Pause (Sekunden)", rest, setRest),
                        Triple("Runden", rounds, setRounds),
                    ).forEach { (label, value, set) ->
                        OutlinedTextField(
                            value, { set(it.filter { c -> c.isDigit() }.take(4)) }, label = { Text(label) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(p?.let { "Gesamt ${formatDuration(it.totalSeconds.toLong())}" } ?: "Belastung 5–1800 s, Pause 0–1800 s, 1–50 Runden", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { p?.let(onChange); custom = false }, enabled = p != null) { Text("Übernehmen") } },
            dismissButton = { TextButton(onClick = { custom = false }) { Text("Abbrechen") } },
        )
    }
}

/** Big effort/rest display during interval training. */
@Composable
fun IntervalPhase(plan: IntervalPlan, elapsed: Long, workLabel: String = "BELASTUNG") {
    val phase = plan.phaseAt(elapsed)
    val color = when {
        phase == null -> MaterialTheme.colorScheme.primary
        phase.work -> Palette.Heart
        else -> Color(0xFF30A46C)
    }
    Column {
        Text(
            when {
                phase == null -> "FERTIG"
                phase.work -> workLabel
                else -> "PAUSE"
            },
            fontSize = 32.sp, fontWeight = FontWeight.Black, color = color,
        )
        if (phase != null) {
            Text(
                "Runde ${phase.round} von ${plan.rounds} · noch ${phase.remaining / 60}:%02d".format(phase.remaining % 60),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** Shown for a minute after a workout while the heart rate recovery is measured. */
@Composable
fun CooldownCard(until: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(until) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1_000)
        }
    }
    val left = (until - now).coerceAtLeast(0)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Puls-Erholung wird gemessen", style = MaterialTheme.typography.titleMedium)
            Text(
                if (left > 0) "Bleib ruhig stehen oder geh langsam – noch $left s." else "Gleich fertig …",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Wie stark der Puls in der ersten Minute fällt, zeigt, wie fit dein Herz-Kreislauf-System ist.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Text for a measured heart rate recovery, e.g. "−27 bpm in 1 min · gut". */
fun recoveryText(drop: Int) = "−$drop bpm in 1 min · ${HeartRateRecovery.rating(drop)}"
