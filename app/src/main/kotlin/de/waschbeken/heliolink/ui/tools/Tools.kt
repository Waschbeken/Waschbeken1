package de.waschbeken.heliolink.ui.tools

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.HrvMeasurement
import de.waschbeken.heliolink.core.IntervalPlan
import de.waschbeken.heliolink.ui.BackTopBar
import de.waschbeken.heliolink.ui.LineChart
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.Routes
import de.waschbeken.heliolink.ui.Series
import de.waschbeken.heliolink.ui.StatTile
import de.waschbeken.heliolink.ui.training.IntervalChooser
import de.waschbeken.heliolink.ui.training.IntervalPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The helpers inside "Schnell eintragen": breathing and focus timer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HelperRow(nav: NavHostController) {
    val app = LocalContext.current.app
    val state by app.repo.state.collectAsStateWithLifecycle()
    state.focus?.let { (start, plan) ->
        var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
        LaunchedEffect(start) {
            while (true) {
                now = System.currentTimeMillis() / 1000
                delay(1_000)
            }
        }
        val phase = plan.phaseAt(now - start)
        val text = if (phase == null) "Fokus-Timer fertig" else {
            (if (phase.work) "Fokus" else "Pause") + " · Runde ${phase.round}/${plan.rounds} · noch " +
                "${phase.remaining / 60}:%02d".format(phase.remaining % 60)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            Triple("Atemübung", Icons.Default.Air, Routes.TOOLS_BREATHE),
            Triple("Fokus-Timer", Icons.Default.Timer, Routes.TOOLS_FOCUS),
        ).forEach { (label, icon, route) ->
            AssistChip(
                onClick = { nav.navigate(route) },
                label = { Text(label) },
                leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

/** Turns the live heart rate on while a screen is shown and back to how it was afterwards. */
@Composable
private fun KeepLiveHeartRate(active: Boolean) {
    val app = LocalContext.current.app
    DisposableEffect(active) {
        val before = app.repo.state.value.liveHeartRate
        if (active) app.repo.controller?.setLiveHeartRate(true)
        onDispose { if (active && !before) app.repo.controller?.setLiveHeartRate(false) }
    }
}

/** Collects the beat intervals the strap sends while [active]. */
@Composable
private fun rememberBeats(active: Boolean): List<Pair<Long, Int>> {
    val app = LocalContext.current.app
    val beats = remember { mutableStateListOf<Pair<Long, Int>>() }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        beats.clear()
        app.repo.beats.collect { list ->
            val now = System.currentTimeMillis() / 1000
            list.forEach { beats += now to it }
        }
    }
    return beats
}

// ------------------------------------------------------------------ breathing

/**
 * Calm breathing at 6 breaths a minute (5 s in, 5 s out) with the live pulse: heart rate and HRV at
 * the start and the end are compared and saved as quick entry "Atemübung".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BreatheScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.app
    val prefs = app.prefs
    val state by app.repo.state.collectAsStateWithLifecycle()
    var minutes by remember { mutableStateOf(prefs.breatheMinutes) }
    var start by remember { mutableLongStateOf(0L) }
    var result by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val running = start > 0
    KeepLiveHeartRate(running)
    val beats = rememberBeats(running)
    val hr = remember { mutableStateListOf<Pair<Long, Float>>() }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        hr.clear()
        var lastBuzz = -1L
        while (true) {
            now = System.currentTimeMillis()
            val s = app.repo.state.value
            if (s.heartRate != null && now - s.heartRateAt < 5_000) hr += now / 1000 to s.heartRate.toFloat()
            val elapsed = (now - start) / 1000
            // A short buzz at the start of every breath in.
            if (prefs.breatheBuzz && elapsed % 10 == 0L && elapsed != lastBuzz) {
                lastBuzz = elapsed
                app.repo.controller?.buzz(1, 250)
            }
            if (elapsed >= minutes * 60) {
                val endSec = now / 1000
                val startSec = start / 1000
                val first = hr.filter { it.first < startSec + 45 }.map { it.second }
                val last = hr.filter { it.first > endSec - 45 }.map { it.second }
                val hrvStart = HrvMeasurement.compute(beats.filter { it.first < startSec + 75 }.map { it.second })?.rmssd
                val hrvEnd = HrvMeasurement.compute(beats.filter { it.first > endSec - 75 }.map { it.second })?.rmssd
                val text = buildString {
                    if (first.isNotEmpty() && last.isNotEmpty()) append("Puls ${first.average().roundToInt()} → ${last.average().roundToInt()}")
                    if (hrvStart != null && hrvEnd != null) {
                        if (isNotEmpty()) append(", ")
                        append("HRV $hrvStart → $hrvEnd ms")
                    }
                }.ifEmpty { "$minutes min geatmet" }
                result = text
                start = 0
                withContext(Dispatchers.IO) { app.db.addEvent(startSec, "Atemübung", text) }
                app.repo.dataChanged()
                break
            }
            delay(100)
        }
    }

    Scaffold(topBar = { BackTopBar("Atemübung", onBack = { nav.popBackStack() }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!running) {
                item {
                    Panel {
                        Text("Ruhig atmen", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "6 Atemzüge pro Minute: 5 Sekunden ein, 5 Sekunden aus. Das beruhigt das Nervensystem – " +
                                "du siehst live, wie Puls und HRV reagieren.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(2, 3, 5, 10).forEach { m ->
                                FilterChip(selected = minutes == m, onClick = { minutes = m; prefs.breatheMinutes = m }, label = { Text("$m min") })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Takt am Arm (kurz vibrieren beim Einatmen)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(checked = prefs.breatheBuzz, onCheckedChange = { prefs.breatheBuzz = it })
                        }
                        Button(
                            onClick = {
                                result = null
                                start = System.currentTimeMillis()
                            },
                            enabled = state.connected,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (state.connected) "Start" else "Strap nicht verbunden") }
                    }
                }
                result?.let { r ->
                    item {
                        Panel {
                            Text("Geschafft", style = MaterialTheme.typography.titleMedium)
                            Text(r, style = MaterialTheme.typography.headlineSmall, color = Palette.Hrv)
                            Text("Gespeichert als Eintrag „Atemübung“ – so siehst du später auch die Wirkung auf den Puls.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                item {
                    val elapsedMs = now - start
                    val inPhase = (elapsedMs / 1000) % 10 < 5
                    val target = if (inPhase) 1f else 0.45f
                    val scale by animateFloatAsState(target, tween(durationMillis = 4_800), label = "breath")
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                            val color = Palette.Breath
                            Canvas(Modifier.fillMaxSize()) {
                                drawCircle(color.copy(alpha = 0.15f), radius = size.minDimension / 2)
                                drawCircle(color.copy(alpha = 0.55f), radius = size.minDimension / 2 * scale, center = Offset(size.width / 2, size.height / 2))
                            }
                            Text(if (inPhase) "Ein" else "Aus", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        val left = (minutes * 60 - elapsedMs / 1000).coerceAtLeast(0)
                        Text("noch ${left / 60}:%02d".format(left % 60), style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile("Puls", state.heartRate?.toString() ?: "–", Modifier.weight(1f), tint = Palette.Heart)
                            StatTile("HRV", state.hrv?.roundToInt()?.let { "$it ms" } ?: "–", Modifier.weight(1f), tint = Palette.Hrv)
                        }
                        if (hr.size >= 2) {
                            LineChart(
                                listOf(Series(hr.toList(), Palette.Heart, width = 2f)), hr.first().first, maxOf(hr.last().first, hr.first().first + 30),
                                modifier = Modifier.fillMaxWidth().height(140.dp), gapSeconds = 30, unit = "bpm",
                            )
                        }
                        OutlinedButton(onClick = { start = 0 }) { Text("Abbrechen") }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ focus timer

/** Focus and break times; the strap vibrates at every change, so the phone can stay away. */
@Composable
fun FocusScreen(nav: NavHostController) {
    val app = LocalContext.current.app
    val state by app.repo.state.collectAsStateWithLifecycle()
    var plan by remember { mutableStateOf(IntervalPlan.decode(app.prefs.focusPlan) ?: FOCUS_PRESETS[0]) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1_000)
        }
    }
    Scaffold(topBar = { BackTopBar("Fokus-Timer", onBack = { nav.popBackStack() }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Panel {
                    val running = state.focus
                    if (running != null) {
                        val (start, p) = running
                        IntervalPhase(p, now - start, workLabel = "FOKUS")
                        OutlinedButton(onClick = { app.repo.controller?.stopFocus() }, modifier = Modifier.fillMaxWidth()) { Text("Beenden") }
                    } else {
                        Text("Lernen oder arbeiten ohne aufs Handy zu schauen", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Der Strap vibriert: 2× kurz = Fokus, 1× lang = Pause, 3× = fertig. Der Timer läuft auch weiter, wenn du die App schließt.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        IntervalChooser(plan, presets = FOCUS_PRESETS, showHint = false) { plan = it }
                        Button(
                            onClick = { app.repo.controller?.startFocus(plan) },
                            enabled = state.connected,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (state.connected) "Start" else "Strap nicht verbunden") }
                    }
                }
            }
        }
    }
}

private val FOCUS_PRESETS = listOf(IntervalPlan(25 * 60, 5 * 60, 4), IntervalPlan(50 * 60, 10 * 60, 2), IntervalPlan(45 * 60, 15 * 60, 3), IntervalPlan(90 * 60, 20 * 60, 1))
