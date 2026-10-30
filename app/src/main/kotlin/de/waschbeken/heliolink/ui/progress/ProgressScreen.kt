package de.waschbeken.heliolink.ui.progress

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.Abstinence
import de.waschbeken.heliolink.core.Goal
import de.waschbeken.heliolink.core.Goals
import de.waschbeken.heliolink.core.Review
import de.waschbeken.heliolink.ui.BackTopBar
import de.waschbeken.heliolink.ui.German
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.SyncRefreshBox
import de.waschbeken.heliolink.ui.cachedDbValue
import de.waschbeken.heliolink.ui.fmt
import de.waschbeken.heliolink.ui.formatHours
import de.waschbeken.heliolink.ui.shareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlin.math.abs

private val Good = Color(0xFF30A46C)

/** "Fortschritt": own goals, days without …, a year calendar, the review and the report for the doctor. */
@Composable
fun ProgressScreen(nav: NavHostController) {
    val app = LocalContext.current.app
    val state by app.repo.state.collectAsStateWithLifecycle()
    val tick by app.prefs.changes.collectAsStateWithLifecycle()
    val prefs = app.prefs
    val goals = prefs.goals.also { tick }
    val without = prefs.withoutTags.also { tick }
    val profile = prefs.profile.also { tick }
    val today = LocalDate.now()
    val facts by cachedDbValue("progress-facts", state.dataVersion, today, without) { ProgressData.facts(it, profile, today, without) }

    var calendarMetric by rememberSaveable { mutableStateOf(CalendarMetric.SLEEP.name) }
    var year by rememberSaveable { mutableStateOf(today.year) }
    val metric = CalendarMetric.valueOf(calendarMetric)
    val calendar by cachedDbValue("progress-calendar-$year-$metric", state.dataVersion) { ProgressData.calendar(it, profile, year, metric) }

    var spanName by rememberSaveable { mutableStateOf(ReviewSpan.MONTH.name) }
    var reviewEpoch by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    val span = ReviewSpan.valueOf(spanName)
    val reviewDate = LocalDate.ofEpochDay(reviewEpoch)
    val review by cachedDbValue("progress-review-$span-$reviewDate", state.dataVersion) { ProgressData.review(it, profile, span, reviewDate) }

    Scaffold(topBar = { BackTopBar("Fortschritt", onBack = { nav.popBackStack() }, subtitle = "Ziele, Kalender und Rückblick") }) { padding ->
        SyncRefreshBox(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { GoalsPanel(goals, facts, today) { prefs.goals = it } }
                item { WithoutPanel(without, facts?.abstinence, prefs.quickTags) { prefs.withoutTags = it } }
                facts?.records?.takeIf { it.isNotEmpty() }?.let { records -> item { RecordsPanel(records) } }
                item {
                    CalendarPanel(year, metric, calendar, today, onYear = { year = it }, onMetric = { calendarMetric = it.name })
                }
                item {
                    ReviewPanel(span, reviewDate, review, today, onSpan = { spanName = it.name }, onDate = { reviewEpoch = it.toEpochDay() })
                }
                item { ReportPanel() }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ goals

/** On the start page: how the goals of this week are going. Nothing when there are no goals. */
@Composable
fun GoalsSummaryCard(dataVersion: Int, onOpen: () -> Unit) {
    val app = LocalContext.current.app
    val tick by app.prefs.changes.collectAsStateWithLifecycle()
    val goals = app.prefs.goals.also { tick }
    if (goals.isEmpty()) return
    val profile = app.prefs.profile
    val today = LocalDate.now()
    val facts by cachedDbValue("home-goals", dataVersion, today, tick) { ProgressData.facts(it, profile, today, emptyList(), weeks = 0) }
    val weeks = goals.map { Goals.week(it, facts?.days.orEmpty(), today, today) }
    Panel(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ziele diese Woche", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "${weeks.count { it.reached }} von ${goals.size} geschafft",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        goals.zip(weeks).forEach { (goal, week) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(goal.title.substringBefore(" an ").substringBefore(" jeden"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    "${week.done}/${goal.perWeek}",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (week.reached) Good else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.LinearProgressIndicator(
                progress = { (week.done.toFloat() / goal.perWeek).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = if (week.reached) Good else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun GoalsPanel(goals: List<Goal>, facts: ProgressFacts?, today: LocalDate, onChange: (List<Goal>) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ziele diese Woche", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (goals.isNotEmpty()) TextButton(onClick = { editing = !editing }) { Text(if (editing) "Fertig" else "Bearbeiten") }
        }
        if (goals.isEmpty()) {
            Text(
                "Setz dir eigene Ziele, z. B. 7 Stunden Schlaf an 5 Tagen pro Woche, 8.000 Schritte oder 3× Training. " +
                    "HelioLink hakt die Tage automatisch ab und zählt, wie viele Wochen in Folge du sie schaffst.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        goals.forEachIndexed { index, goal ->
            GoalRow(goal, facts, today, editing) { onChange(goals.filterIndexed { i, _ -> i != index }) }
        }
        AssistChip(
            onClick = { adding = true },
            label = { Text("Ziel hinzufügen") },
            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
    }
    if (adding) GoalDialog(onDismiss = { adding = false }) { onChange(goals + it) }
}

@Composable
private fun GoalRow(goal: Goal, facts: ProgressFacts?, today: LocalDate, editing: Boolean, onDelete: () -> Unit) {
    val days = facts?.days.orEmpty()
    val week = Goals.week(goal, days, today, today)
    val streak = if (facts == null) 0 else Goals.streak(goal, days, today)
    val color = when (goal.kind) {
        Goal.Kind.SLEEP, Goal.Kind.BEDTIME -> Palette.Sleep
        Goal.Kind.STEPS, Goal.Kind.ACTIVE -> Palette.Steps
        Goal.Kind.WORKOUT -> Palette.Heart
        Goal.Kind.WITHOUT -> Palette.Hrv
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(goal.title, style = MaterialTheme.typography.bodyLarge)
                val left = (goal.perWeek - week.done).coerceAtLeast(0)
                Text(
                    buildString {
                        append(if (week.reached) "Diese Woche geschafft" else "${week.done} von ${goal.perWeek} · noch $left")
                        if (streak > 0) append(" · $streak ${if (streak == 1) "Woche" else "Wochen"} in Folge")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (week.reached) Good else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (editing) IconButton(onClick = onDelete) { Icon(Icons.Default.Close, contentDescription = "Ziel löschen") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val names = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
            week.days.forEachIndexed { i, met ->
                val future = week.start.plusDays(i.toLong()).isAfter(today)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .then(
                                when {
                                    met == true -> Modifier.background(color, CircleShape)
                                    future -> Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    else -> Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (met == true) Icon(Icons.Default.Check, contentDescription = "geschafft", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Text(names[i], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalDialog(onDismiss: () -> Unit, onAdd: (Goal) -> Unit) {
    val context = LocalContext.current
    var kind by remember { mutableStateOf(Goal.Kind.SLEEP) }
    var sleep by remember { mutableIntStateOf(420) }
    var bedtime by remember { mutableIntStateOf(-30) } // minutes from midnight, evening negative
    var steps by remember { mutableIntStateOf(8000) }
    var active by remember { mutableIntStateOf(30) }
    var perWeek by remember { mutableIntStateOf(5) }
    val tags = context.app.prefs.quickTags
    var tag by remember { mutableStateOf(tags.firstOrNull { it == "Nikotin" } ?: tags.firstOrNull().orEmpty()) }
    val names = mapOf(
        Goal.Kind.SLEEP to "Schlaf", Goal.Kind.BEDTIME to "Schlafenszeit", Goal.Kind.STEPS to "Schritte",
        Goal.Kind.ACTIVE to "Aktive Minuten", Goal.Kind.WORKOUT to "Training", Goal.Kind.WITHOUT to "Ohne …",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neues Ziel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Goal.Kind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(names.getValue(k)) }) }
                }
                when (kind) {
                    Goal.Kind.SLEEP -> {
                        Text("Mindestens ${formatHours(sleep)}", style = MaterialTheme.typography.titleSmall)
                        Slider(sleep.toFloat(), { sleep = (it / 15).toInt() * 15 }, valueRange = 300f..600f, steps = 19)
                    }
                    Goal.Kind.BEDTIME -> {
                        val m = (bedtime + 1440) % 1440
                        Text("Einschlafen vor %02d:%02d Uhr".format(m / 60, m % 60), style = MaterialTheme.typography.titleSmall)
                        Slider(bedtime.toFloat(), { bedtime = (it / 15).toInt() * 15 }, valueRange = -180f..90f, steps = 17)
                    }
                    Goal.Kind.STEPS -> {
                        Text("Mindestens ${steps.fmt()} Schritte", style = MaterialTheme.typography.titleSmall)
                        Slider(steps.toFloat(), { steps = (it / 500).toInt() * 500 }, valueRange = 2000f..20000f, steps = 35)
                    }
                    Goal.Kind.ACTIVE -> {
                        Text("Mindestens $active aktive Minuten", style = MaterialTheme.typography.titleSmall)
                        Slider(active.toFloat(), { active = (it / 5).toInt() * 5 }, valueRange = 10f..120f, steps = 21)
                    }
                    Goal.Kind.WORKOUT -> Text("Ein Tag zählt, wenn du ein Training aufgezeichnet hast.", style = MaterialTheme.typography.bodySmall)
                    Goal.Kind.WITHOUT -> {
                        Text("Ein Tag zählt, wenn du nichts davon eingetragen hast.", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            tags.forEach { t -> FilterChip(selected = tag == t, onClick = { tag = t }, label = { Text(t) }) }
                        }
                    }
                }
                Text("An wie vielen Tagen pro Woche?", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..7).forEach { n -> FilterChip(selected = perWeek == n, onClick = { perWeek = n }, label = { Text(if (n == 7) "jeden Tag" else "$n") }) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = kind != Goal.Kind.WITHOUT || tag.isNotBlank(),
                onClick = {
                    val value = when (kind) {
                        Goal.Kind.SLEEP -> sleep
                        Goal.Kind.BEDTIME -> (bedtime + 1440) % 1440
                        Goal.Kind.STEPS -> steps
                        Goal.Kind.ACTIVE -> active
                        else -> 0
                    }
                    onAdd(Goal(kind, value, perWeek, if (kind == Goal.Kind.WITHOUT) tag else ""))
                    onDismiss()
                },
            ) { Text("Hinzufügen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

// ---------------------------------------------------------------------------------- days without

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WithoutPanel(tags: List<String>, results: Map<String, Abstinence.Result>?, quickTags: List<String>, onChange: (List<String>) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    Panel {
        Text("Tage ohne …", style = MaterialTheme.typography.titleMedium)
        if (tags.isEmpty()) {
            Text(
                "Zählt, wie lange dein letzter Eintrag von etwas her ist – z. B. Nikotin oder Alkohol – und was dein Rekord ist.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        tags.forEach { tag ->
            val r = results?.get(tag)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r?.let { (it.daysSince ?: it.longest).toString() } ?: "…",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.Hrv,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(end = 14.dp),
                )
                Column(Modifier.weight(1f)) {
                    val days = r?.let { it.daysSince ?: it.longest } ?: 0L
                    Text("${if (days == 1L) "Tag" else "Tage"} ohne $tag", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            r == null -> "Rechne …"
                            r.daysSince == null -> "Noch nie eingetragen"
                            r.daysSince == 0L -> "Heute eingetragen · Rekord ${r.longest} Tage · ${r.entries}× insgesamt"
                            else -> "Rekord ${r.longest} Tage · ${r.entries}× insgesamt"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onChange(tags - tag) }) { Icon(Icons.Default.Close, contentDescription = "Entfernen") }
            }
        }
        if (adding) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quickTags.filter { it !in tags }.forEach { t ->
                    AssistChip(onClick = { onChange(tags + t); adding = false }, label = { Text(t) })
                }
            }
        } else {
            AssistChip(
                onClick = { adding = true },
                label = { Text("Hinzufügen") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------- records

@Composable
private fun RecordsPanel(records: List<Record>) {
    val day = DateTimeFormatter.ofPattern("d. MMM yyyy", German)
    Panel {
        Text("Bestwerte", style = MaterialTheme.typography.titleMedium)
        Text("Deine Rekorde der letzten 12 Monate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        records.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { r ->
                    Column(Modifier.weight(1f)) {
                        Text(r.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(r.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(r.date.format(day), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

// --------------------------------------------------------------------------------------- calendar

private fun calendarText(metric: CalendarMetric, v: Float): String = when (metric) {
    CalendarMetric.SLEEP -> formatHours(v.toInt())
    CalendarMetric.SCORE -> "Score ${v.toInt()}"
    CalendarMetric.STEPS -> "${v.toInt().fmt()} Schritte"
    CalendarMetric.ACTIVE -> "${v.toInt()} aktive Minuten"
    CalendarMetric.RESTING -> "Ruhepuls ${v.toInt()} bpm"
    CalendarMetric.STRESS -> "Stress ${v.toInt()}"
}

private fun calendarColor(metric: CalendarMetric) = when (metric) {
    CalendarMetric.SLEEP, CalendarMetric.SCORE -> Palette.Sleep
    CalendarMetric.STEPS, CalendarMetric.ACTIVE -> Palette.Steps
    CalendarMetric.RESTING -> Palette.Heart
    CalendarMetric.STRESS -> Palette.Stress
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarPanel(
    year: Int,
    metric: CalendarMetric,
    values: Map<LocalDate, Float>?,
    today: LocalDate,
    onYear: (Int) -> Unit,
    onMetric: (CalendarMetric) -> Unit,
) {
    var selected by remember(year, metric) { mutableStateOf<LocalDate?>(null) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Jahreskalender", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { onYear(year - 1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Voriges Jahr") }
            Text("$year", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onYear(year + 1) }, enabled = year < today.year) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Nächstes Jahr")
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CalendarMetric.entries.forEach { m -> FilterChip(selected = metric == m, onClick = { onMetric(m) }, label = { Text(m.label) }) }
        }
        val list = values
        if (list == null) {
            Text("Lade …", style = MaterialTheme.typography.bodySmall)
            return@Panel
        }
        // Colour from the 10 % to the 90 % value of the year, so single outliers do not wash out the rest.
        val sorted = list.values.sorted()
        val lo = sorted.getOrNull(sorted.size / 10) ?: 0f
        val hi = sorted.getOrNull(sorted.size * 9 / 10) ?: 1f
        val color = calendarColor(metric)
        val empty = MaterialTheme.colorScheme.surfaceContainerHighest
        val ring = MaterialTheme.colorScheme.onSurface
        fun intensity(v: Float) = if (hi - lo < 1e-3f) 1f else ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
        (1..12).chunked(3).forEach { months ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                months.forEach { month ->
                    val first = LocalDate.of(year, month, 1)
                    Column(Modifier.weight(1f)) {
                        Text(
                            first.month.getDisplayName(TextStyle.SHORT, German),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val offset = first.dayOfWeek.value - 1
                        val length = first.lengthOfMonth()
                        Canvas(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(7f / 6f)
                                .pointerInput(year, month) {
                                    detectTapGestures { p ->
                                        val cell = size.width / 7f
                                        val index = (p.y / cell).toInt() * 7 + (p.x / cell).toInt() - offset
                                        if (index in 0 until length) selected = first.plusDays(index.toLong())
                                    }
                                },
                        ) {
                            val cell = size.width / 7f
                            val gap = cell * 0.14f
                            for (i in 0 until length) {
                                val date = first.plusDays(i.toLong())
                                if (date.isAfter(today)) break
                                val pos = i + offset
                                val tl = Offset((pos % 7) * cell, (pos / 7) * cell)
                                val v = list[date]
                                val c = if (v == null) empty else color.copy(alpha = 0.25f + 0.75f * intensity(v))
                                drawRoundRect(c, tl, Size(cell - gap, cell - gap), CornerRadius(cell * 0.25f))
                                if (date == selected || (selected == null && date == today)) {
                                    drawRoundRect(ring, tl, Size(cell - gap, cell - gap), CornerRadius(cell * 0.25f), style = Stroke(width = gap))
                                }
                            }
                        }
                    }
                }
            }
        }
        val pick = selected
        Text(
            when {
                pick != null -> pick.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", German)) + ": " +
                    (list[pick]?.let { calendarText(metric, it) } ?: "keine Daten")
                list.isEmpty() -> "Noch keine Daten in diesem Jahr."
                else -> "Ø ${calendarText(metric, list.values.average().toFloat())} an ${list.size} Tagen · " +
                    (if (metric.lowIsGood) "kräftig = höher" else "kräftig = mehr") + " · Tippe auf einen Tag"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ----------------------------------------------------------------------------------------- review

private fun spanLabel(span: ReviewSpan, date: LocalDate, today: LocalDate): String {
    val (from, to) = span.range(date)
    return when (span) {
        ReviewSpan.WEEK -> if (today in from..to) "Diese Woche" else
            "${from.format(DateTimeFormatter.ofPattern("d. MMM", German))} – ${to.format(DateTimeFormatter.ofPattern("d. MMM", German))}"
        ReviewSpan.MONTH -> date.format(DateTimeFormatter.ofPattern("MMMM yyyy", German))
        ReviewSpan.YEAR -> "${date.year}"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewPanel(
    span: ReviewSpan,
    date: LocalDate,
    data: ReviewData?,
    today: LocalDate,
    onSpan: (ReviewSpan) -> Unit,
    onDate: (LocalDate) -> Unit,
) {
    Panel {
        Text("Rückblick", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReviewSpan.entries.forEach { s -> FilterChip(selected = span == s, onClick = { onSpan(s) }, label = { Text(s.label) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDate(span.shift(date, -1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Zurück") }
            Text(spanLabel(span, date, today), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            IconButton(onClick = { onDate(span.shift(date, 1).let { if (it > today) today else it }) }, enabled = span.range(date).second < today) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Vor")
            }
        }
        if (data == null || data.from != span.range(date).first) {
            Text("Rechne …", style = MaterialTheme.typography.bodySmall)
            return@Panel
        }
        val n = data.now
        val b = data.before
        if (n.nights == 0 && n.stepsTotal == 0L && n.workouts == 0) {
            Text("Keine Daten in diesem Zeitraum.", style = MaterialTheme.typography.bodyMedium)
            return@Panel
        }
        Text(
            "Verglichen mit ${span.before} (rechts)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        fun hm(m: Int) = (m + 1440) % 1440 / 60 to (m + 1440) % 1440 % 60
        ReviewRow("Schlaf Ø", n.sleepMinutes?.let(::formatHours), Review.change(n.sleepMinutes, b.sleepMinutes, 5), " min", Review.Direction.HIGHER_IS_BETTER)
        ReviewRow("Schlaf-Score Ø", n.sleepScore?.toString(), Review.change(n.sleepScore, b.sleepScore), "", Review.Direction.HIGHER_IS_BETTER)
        ReviewRow(
            "Eingeschlafen Ø", n.bedtime?.let { hm(it).let { (h, m) -> "%02d:%02d".format(h, m) } },
            Review.change(n.bedtime, b.bedtime, 5), " min", Review.Direction.NEUTRAL,
        )
        ReviewRow("Schritte pro Tag", n.stepsPerDay?.fmt(), Review.change(n.stepsPerDay, b.stepsPerDay, 100), "", Review.Direction.HIGHER_IS_BETTER)
        ReviewRow("Aktive Minuten pro Tag", n.activeMinutes?.toString(), Review.change(n.activeMinutes, b.activeMinutes, 2), " min", Review.Direction.HIGHER_IS_BETTER)
        ReviewRow("Trainings", "${n.workouts} · ${n.workoutMinutes} min", Review.change(n.workouts, b.workouts), "", Review.Direction.HIGHER_IS_BETTER)
        ReviewRow("Ruhepuls Ø", n.restingHr?.let { "$it bpm" }, Review.change(n.restingHr, b.restingHr), " bpm", Review.Direction.LOWER_IS_BETTER)
        ReviewRow("HRV Ø", n.hrv?.let { "$it ms" }, Review.change(n.hrv, b.hrv, 2), " ms", Review.Direction.HIGHER_IS_BETTER)
        ReviewRow("Stress Ø", n.stress?.toString(), Review.change(n.stress, b.stress, 2), "", Review.Direction.LOWER_IS_BETTER)
        val day = DateTimeFormatter.ofPattern("EEE d.M.", German)
        n.bestNight?.let { (d, s) -> Text("Beste Nacht: ${d.format(day)} (Score $s)", style = MaterialTheme.typography.bodyMedium) }
        n.mostSteps?.let { (d, s) -> Text("Meiste Schritte: ${d.format(day)} (${s.fmt()})", style = MaterialTheme.typography.bodyMedium) }
        if (n.stepsTotal > 0) Text("Schritte insgesamt: ${n.stepsTotal.fmt()}", style = MaterialTheme.typography.bodyMedium)
        if (n.tags.isNotEmpty()) {
            Text(
                "Einträge: " + n.tags.take(6).joinToString(" · ") { (t, c) -> "$t $c×" },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ReviewRow(label: String, value: String?, change: Int?, unit: String, direction: Review.Direction) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value ?: "–", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 12.dp))
        val good = Review.judge(change, direction)
        Text(
            when {
                change == null -> ""
                change == 0 -> "gleich"
                else -> (if (change > 0) "+" else "−") + abs(change).let { if (unit.isEmpty() && it >= 1000) it.fmt() else it.toString() } + unit
            },
            style = MaterialTheme.typography.labelLarge,
            color = when (good) {
                true -> Good
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.End,
            modifier = Modifier.size(width = 78.dp, height = 20.dp),
        )
    }
}

// ----------------------------------------------------------------------------------------- report

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportPanel() {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var days by rememberSaveable { mutableStateOf(30) }
    var busy by remember { mutableStateOf(false) }
    Panel {
        Text("Bericht für den Arzt", style = MaterialTheme.typography.titleMedium)
        Text(
            "Ein PDF mit Schlaf, Ruhepuls, HRV, SpO₂, Hauttemperatur und Atmung als Verlauf, dazu deine Einträge mit Notizen " +
                "(Beschwerden, Medikamente). Es entsteht auf dem Handy und verlässt es nur, wenn du es teilst.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(14, 30, 90).forEach { d -> FilterChip(selected = days == d, onClick = { days = d }, label = { Text("$d Tage") }) }
        }
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    try {
                        val file = withContext(Dispatchers.IO) { HealthReport.create(context, app.db, app.prefs, days) }
                        shareFile(context, file, "application/pdf", "Bericht teilen")
                    } catch (e: Exception) {
                        de.waschbeken.heliolink.data.CrashReporter.nonFatal(context, e)
                        Toast.makeText(context, "Bericht ließ sich nicht erstellen: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        busy = false
                    }
                }
            },
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text("PDF erstellen und teilen", modifier = Modifier.padding(start = 8.dp))
        }
    }
}
