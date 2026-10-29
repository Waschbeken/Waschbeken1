package de.waschbeken.heliolink.ui.body

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.BodyCheck
import de.waschbeken.heliolink.core.HrvStatus
import de.waschbeken.heliolink.core.OdiNight
import de.waschbeken.heliolink.data.HealthDb
import de.waschbeken.heliolink.data.Queries
import de.waschbeken.heliolink.ui.BarChart
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.Routes
import de.waschbeken.heliolink.ui.dbValue
import de.waschbeken.heliolink.ui.navigateToTab
import de.waschbeken.heliolink.ui.tools.HelperRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Complaints to tick on the body page; they are saved as quick entries of the day. */
val SYMPTOMS = listOf(
    "Kopfschmerzen", "Halsschmerzen", "Schnupfen", "Husten", "Fieber", "Gliederschmerzen", "Übelkeit",
    "Bauchschmerzen", "Schwindel", "Müde", "Muskelkater", "Allergie", "Krank",
)

private val FEELINGS = listOf("😫" to "Schlecht", "😕" to "Mäh", "😐" to "Okay", "🙂" to "Gut", "😄" to "Super")

/** Everything the body page shows, loaded off the main thread. */
private class BodyData(
    val check: BodyCheck.Result?,
    val hrv: HrvStatus.Result?,
    val rhythmChecked: Int,
    val rhythmIrregular: Int,
    val odi: OdiNight?,
    val feelings: Map<Long, HealthDb.Feeling>,
    /** Symptoms entered today with their entry ids. */
    val todaySymptoms: Map<String, Long>,
    /** How often each symptom was entered in the last 14 days. */
    val symptomCounts: List<Pair<String, Int>>,
    /** Average sleep score of nights after good (4–5) and bad (1–2) days, if there are enough of both. */
    val sleepByFeeling: Pair<Int, Int>?,
)

private fun load(db: HealthDb): BodyData {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val now = System.currentTimeMillis() / 1000
    val vitals = Queries.nightVitals(db, today.minusDays(60), today, zone)
    val check = BodyCheck.check(vitals.filter { it.date > today.minusDays(37) })?.takeIf { it.date == today }
    val hrv = HrvStatus.status(vitals.mapNotNull { v -> v.hrv?.let { v.date to it } }.toMap(), today)
    val rhythm = db.rhythm(now - 30 * 86_400L, now + 60)
    val midnight = today.atStartOfDay(zone).toEpochSecond()
    val odi = db.odi(midnight - 12 * 3600, midnight + 12 * 3600).lastOrNull()
    val feelings = db.feelings(today.minusDays(59).toEpochDay(), today.toEpochDay())
    val events = db.events(today.minusDays(13).atStartOfDay(zone).toEpochSecond(), now + 60)
    val todayEvents = events.filter { it.timestamp >= midnight && it.tag in SYMPTOMS }
    val counts = events.filter { it.tag in SYMPTOMS }.groupingBy { it.tag }.eachCount().toList().sortedByDescending { it.second }
    // The night after a day (date + 1) against how that day felt.
    val nights = if (feelings.size >= 6) Queries.nightSummaries(db, today.minusDays(59), today, zone) else emptyMap()
    val good = feelings.values.filter { it.score >= 4 }.mapNotNull { nights[LocalDate.ofEpochDay(it.day + 1)]?.score }
    val bad = feelings.values.filter { it.score in 1..2 }.mapNotNull { nights[LocalDate.ofEpochDay(it.day + 1)]?.score }
    return BodyData(
        check = check,
        hrv = hrv,
        rhythmChecked = rhythm.size,
        rhythmIrregular = rhythm.count { it.irregular },
        odi = odi,
        feelings = feelings,
        todaySymptoms = todayEvents.associate { it.tag to it.id },
        symptomCounts = counts,
        sleepByFeeling = if (good.size >= 3 && bad.size >= 3) good.average().roundToInt() to bad.average().roundToInt() else null,
    )
}

/** "Körper": how you feel, the nightly body check, heart and breathing at a glance, and the helpers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BodyScreen(nav: NavHostController) {
    val app = LocalContext.current.app
    val state by app.repo.state.collectAsStateWithLifecycle()
    var reload by remember { mutableIntStateOf(0) }
    val data by dbValue(state.dataVersion, reload) { load(it) }
    Scaffold(topBar = { TopAppBar(title = { Text("Körper") }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val d = data
            if (d == null) {
                item { Text("Lade …") }
                return@LazyColumn
            }
            item { FeelingCard(d) { reload++ } }
            item { CheckPanel(d.check) }
            item { Panel { Text("Helfer", style = MaterialTheme.typography.titleMedium); HelperRow(nav) } }
            item {
                Panel {
                    Text("Herz & Atmung", style = MaterialTheme.typography.titleMedium)
                    LinkRow(
                        "HRV-Status",
                        d.hrv?.let { "${it.status.label} · 7 Tage Ø ${it.weekAverage.roundToInt()} ms (üblich ${it.low.roundToInt()}–${it.high.roundToInt()})" }
                            ?: "Erscheint nach etwa zwei Wochen mit Nacht-HRV",
                    ) { nav.navigate(Routes.metric("hrv")) }
                    LinkRow(
                        "Herzrhythmus",
                        when {
                            d.rhythmChecked == 0 -> "Wird geprüft, wenn der Puls live läuft und du ruhig bist"
                            d.rhythmIrregular == 0 -> "Regelmäßig · ${d.rhythmChecked} Abschnitte in 30 Tagen geprüft"
                            else -> "${d.rhythmIrregular} von ${d.rhythmChecked} Abschnitten unregelmäßig (30 Tage)"
                        },
                    ) { nav.navigate(Routes.metric("heart")) }
                    LinkRow(
                        "Sauerstoff letzte Nacht",
                        d.odi?.let { o ->
                            if (o.validTime > 0) "${o.drops} Abfälle laut Strap" + (o.score?.let { " · Bewertung $it" } ?: "") else "Vom Strap nicht auswertbar"
                        } ?: "Noch keine Auswertung vom Strap",
                    ) { nav.navigateToTab(Routes.SLEEP) }
                }
            }
            item { HistoryPanel(d) }
        }
    }
}

@Composable
private fun LinkRow(title: String, text: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** How do you feel today: a face, complaints and a note. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeelingCard(d: BodyData, onChanged: () -> Unit) {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val today = LocalDate.now().toEpochDay()
    val saved = d.feelings[today]
    // Keyed by the day only: reloading after a save must not reset what is being typed.
    var score by remember(today) { mutableIntStateOf(saved?.score ?: 0) }
    var note by remember(today) { mutableStateOf(saved?.note.orEmpty()) }
    var dirty by remember { mutableIntStateOf(0) }
    LaunchedEffect(dirty) {
        if (dirty == 0) return@LaunchedEffect
        delay(500)
        withContext(Dispatchers.IO) { app.db.setFeeling(today, score, note.trim()) }
        app.repo.dataChanged()
    }
    Panel(color = MaterialTheme.colorScheme.primaryContainer) {
        Text("Wie geht's dir heute?", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            FEELINGS.forEachIndexed { i, (face, label) ->
                val value = i + 1
                val selected = score == value
                Surface(
                    onClick = {
                        score = if (selected) 0 else value
                        dirty++
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                ) {
                    Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(face, fontSize = 30.sp)
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        Text("Beschwerden heute", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SYMPTOMS.forEach { symptom ->
                val id = d.todaySymptoms[symptom]
                FilterChip(
                    selected = id != null,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (id != null) app.db.deleteEvent(id) else app.db.addEvent(System.currentTimeMillis() / 1000, symptom)
                            }
                            app.repo.dataChanged()
                            onChanged()
                        }
                    },
                    label = { Text(symptom) },
                )
            }
        }
        OutlinedTextField(
            note,
            { note = it.take(300); dirty++ },
            label = { Text("Notiz (optional), z. B. „Erkältung im Büro“") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Beschwerden zählen wie Schnell-Einträge: sie stehen in den Diagrammen und in „Was deinem Schlaf guttut“. " +
                "Die Frühwarnung weist darauf hin, wenn auffällige Nachtwerte dazu passen.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** The nightly body check, always visible: each value against your normal. */
@Composable
private fun CheckPanel(check: BodyCheck.Result?) {
    Panel {
        Text("Körper-Check letzte Nacht", style = MaterialTheme.typography.titleMedium)
        if (check == null) {
            Text(
                "Noch zu wenige Nächte. Ab etwa einer Woche vergleicht HelioLink jede Nacht Hauttemperatur, Ruhepuls, HRV und " +
                    "Atmung mit deinem Normalwert und warnt, wenn mehrere zugleich abweichen – oft einen Tag, bevor man sich krank fühlt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Panel
        }
        val (headline, color) = when (check.level) {
            BodyCheck.Level.NORMAL -> "Alles im üblichen Bereich" to Color(0xFF30A46C)
            BodyCheck.Level.WATCH -> "Etwas ist anders als sonst" to Palette.Stress
            BodyCheck.Level.ALERT -> "Dein Körper arbeitet mehr als sonst" to MaterialTheme.colorScheme.error
        }
        Text(headline + if (check.nights > 1) " – seit ${check.nights} Nächten" else "", style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.SemiBold)
        check.all.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.vital.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    "%.${s.vital.decimals}f ${s.vital.unit}".format(s.value),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Text(
                    s.text,
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        s.strong -> MaterialTheme.colorScheme.error
                        s.flagged -> Palette.Stress
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Text(
            when (check.level) {
                BodyCheck.Level.NORMAL -> "Abweichungen in Grau sind im normalen Schwankungsbereich."
                BodyCheck.Level.WATCH -> "Einzeln heißt das wenig – behalte es im Auge. Steigen mehrere Werte, meldet sich HelioLink deutlicher."
                BodyCheck.Level.ALERT -> "So sieht es oft aus, bevor man krank wird. Gönn dir Ruhe, trink genug und schlaf früh. Wird es nicht besser, frag ärztlich nach."
            } + " Vergleich mit deinen letzten ${check.baselineNights} Nächten · keine Diagnose.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** How you felt in the last two weeks and the most frequent complaints. */
@Composable
private fun HistoryPanel(d: BodyData) {
    val today = LocalDate.now()
    val days = (13 downTo 0).map { today.minusDays(it.toLong()) }
    Panel {
        Text("Befinden – letzte 14 Tage", style = MaterialTheme.typography.titleMedium)
        val scores = days.map { (d.feelings[it.toEpochDay()]?.score ?: 0).toFloat() }
        if (scores.all { it == 0f }) {
            Text("Noch keine Einträge. Tipp oben jeden Tag kurz an, wie es dir geht.", style = MaterialTheme.typography.bodySmall)
        } else {
            BarChart(
                scores, Palette.Hrv,
                labels = days.map { it.dayOfMonth.toString() },
                colors = scores.map { feelingColor(it.toInt()) },
                format = { v -> FEELINGS.getOrNull(v.toInt() - 1)?.first ?: "" },
                tooltip = { i ->
                    val f = d.feelings[days[i].toEpochDay()]
                    "${days[i].dayOfMonth}.${days[i].monthValue}.: " + (f?.let { (FEELINGS.getOrNull(it.score - 1)?.second ?: "–") + if (it.note.isNotBlank()) " – ${it.note}" else "" } ?: "kein Eintrag")
                },
            )
        }
        if (d.symptomCounts.isNotEmpty()) {
            Text(
                "Beschwerden: " + d.symptomCounts.joinToString(", ") { (tag, n) -> if (n > 1) "$tag ${n}×" else tag },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        d.sleepByFeeling?.let { (good, bad) ->
            Text(
                "Nach guten Tagen schläfst du mit Score Ø $good, nach schlechten mit Ø $bad.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun feelingColor(score: Int): Color = when (score) {
    1 -> MaterialTheme.colorScheme.error
    2 -> Palette.Stress
    3 -> Palette.Pai
    4 -> Palette.Hrv
    5 -> Color(0xFF30A46C)
    else -> MaterialTheme.colorScheme.surfaceContainerHighest
}
