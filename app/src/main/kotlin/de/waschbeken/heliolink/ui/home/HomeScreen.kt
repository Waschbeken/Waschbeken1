package de.waschbeken.heliolink.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.ble.LinkState
import de.waschbeken.heliolink.core.DetectedActivity
import de.waschbeken.heliolink.core.HeartRateZones
import de.waschbeken.heliolink.core.SleepStage
import de.waschbeken.heliolink.service.StrapUiState
import de.waschbeken.heliolink.ui.CountPill
import de.waschbeken.heliolink.ui.DayChart
import de.waschbeken.heliolink.ui.Legend
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.ProgressRing
import de.waschbeken.heliolink.ui.Routes
import de.waschbeken.heliolink.ui.SectionHeader
import de.waschbeken.heliolink.ui.Sparkline
import de.waschbeken.heliolink.ui.StackedBar
import de.waschbeken.heliolink.ui.StatTile
import de.waschbeken.heliolink.ui.dbValue
import de.waschbeken.heliolink.ui.fmt
import de.waschbeken.heliolink.ui.formatDuration
import de.waschbeken.heliolink.ui.formatHours
import de.waschbeken.heliolink.ui.formatMinutes
import de.waschbeken.heliolink.ui.navigateToTab
import de.waschbeken.heliolink.ui.scoreColor
import de.waschbeken.heliolink.ui.stressColor
import de.waschbeken.heliolink.ui.stressLabel
import de.waschbeken.heliolink.update.UpdateState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date

/** Tiles of the dashboard; the user can hide them in the settings. */
enum class Tile(val title: String) {
    STEPS("Schritte"), SLEEP("Schlaf"), HEART("Ruhepuls"), STRESS("Stress"), SPO2("SpO₂"), HRV("HRV"),
    PAI("PAI"), TEMPERATURE("Hauttemperatur"), BREATH("Atemfrequenz"), BATTERY("Akku"),
}

private fun greeting(): String {
    val h = LocalTime.now().hour
    return when {
        h < 5 -> "Gute Nacht"
        h < 11 -> "Guten Morgen"
        h < 18 -> "Hallo"
        else -> "Guten Abend"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.app
    val state by app.repo.state.collectAsStateWithLifecycle()
    val update by app.updater.state.collectAsStateWithLifecycle()
    val tick by app.prefs.changes.collectAsStateWithLifecycle()
    val profile = remember(tick) { app.prefs.profile }
    val hidden = remember(tick) { app.prefs.hiddenTiles }
    val order = remember(tick) { app.prefs.tileOrder }
    val data by dbValue(state.dataVersion, tick, state.heartRateAt / 300_000) { HomeData.load(it, profile.stepGoal) }
    val controller = app.repo.controller

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(greeting(), style = MaterialTheme.typography.titleLarge)
                        Text(statusLine(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    data?.streak?.takeIf { it >= 2 }?.let { StreakBadge(it) }
                    if (state.syncing) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.5.dp)
                    } else {
                        IconButton(onClick = { controller?.syncNow() }, enabled = state.connected) {
                            Icon(Icons.Default.Sync, contentDescription = "Synchronisieren")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (update is UpdateState.Available || update is UpdateState.KeyChanged) {
                item {
                    Card(
                        onClick = { app.updater.showDialog.value = true },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text("Eine neue Version von HelioLink ist da", modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    }
                }
            }
            if (state.syncing) {
                item {
                    Column {
                        Text(state.syncText ?: "Synchronisiere …", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
            }
            if (state.link == LinkState.AUTH_FAILED || state.link == LinkState.DISCONNECTED) {
                item { ConnectionCard(state) }
            }
            state.maxHrSuggestion?.takeIf { it > profile.maxHeartRate }?.let { hr ->
                item {
                    Panel(color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text("Neuer Höchstpuls: $hr bpm", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Im Training warst du über deinem eingestellten Maximalpuls (${profile.maxHeartRate}). " +
                                "Übernimm den Wert, damit Zonen und Kalorien stimmen.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row {
                            FilledTonalButton(onClick = {
                                app.prefs.profile = app.prefs.profile.copy(maxHrOverride = hr)
                                app.repo.update { it.copy(maxHrSuggestion = null) }
                            }) { Text("Übernehmen") }
                            TextButton(onClick = { app.repo.update { it.copy(maxHrSuggestion = null) } }) { Text("Nein danke") }
                        }
                    }
                }
            }
            val d = data
            item { HeroCard(state, d, profile.stepGoal) { nav.navigateToTab(Routes.SLEEP) } }
            item { LiveHeartCard(state, profile.maxHeartRate, d?.resting?.let { profile.zoneRest(it) }) { controller?.setLiveHeartRate(it) } }
            if (d != null) {
                item { SectionHeader("Heute im Überblick") }
                item { TileGrid(d, state, profile.stepGoal, hidden, order, nav) }
                item {
                    Panel(onClick = { nav.navigate(Routes.metric("heart")) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Tagesverlauf", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                        val zone = ZoneId.systemDefault()
                        val from = d.date.atStartOfDay(zone).toEpochSecond()
                        val to = minOf(d.date.plusDays(1).atStartOfDay(zone).toEpochSecond(), System.currentTimeMillis() / 1000 + 1800)
                        if (d.day.isEmpty()) {
                            Text("Noch keine Daten für heute – nach dem nächsten Abruf erscheinen sie hier.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            val stages = d.night?.stages?.toMap().orEmpty()
                            DayChart(d.day, from, to, stages)
                            Legend(listOf("Bewegung" to Palette.Steps, "Schlaf" to Palette.Light, "Puls" to Palette.Heart))
                        }
                    }
                }
                if (d.activities.isNotEmpty() || d.workouts.isNotEmpty()) {
                    item { SectionHeader("Aktivitäten heute") }
                    d.workouts.forEach { w ->
                        item {
                            ActivityRow(
                                Icons.Default.Favorite, w.sport,
                                "${formatDuration(w.end - w.start)} · Ø ${w.avgHr} bpm · ${w.calories} kcal",
                                Instant.ofEpochSecond(w.start),
                            ) { nav.navigate(Routes.workout(w.id)) }
                        }
                    }
                    d.activities.forEach { a ->
                        item {
                            ActivityRow(
                                if (a.kind == DetectedActivity.Kind.RUN) Icons.AutoMirrored.Filled.DirectionsRun else Icons.AutoMirrored.Filled.DirectionsWalk,
                                "${a.kind.label} (erkannt)",
                                "${formatMinutes(a.minutes)} · ${a.steps.fmt()} Schritte" + (a.avgHeartRate?.let { " · Ø $it bpm" } ?: ""),
                                Instant.ofEpochSecond(a.start),
                            ) { nav.navigate(Routes.metric("steps")) }
                        }
                    }
                }
                item { AlarmCard(d) { nav.navigateToTab(Routes.ALARMS) } }
            }
            item {
                val last = if (state.lastSync > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.lastSync)) else "nie"
                Text(
                    "Zuletzt synchronisiert: $last",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

private fun statusLine(s: StrapUiState): String {
    val link = when (s.link) {
        LinkState.READY -> "verbunden"
        LinkState.CONNECTING -> "verbinde …"
        LinkState.AUTHENTICATING -> "Anmeldung …"
        LinkState.AUTH_FAILED -> "Auth-Key abgelehnt"
        LinkState.DISCONNECTED -> "nicht verbunden"
    }
    val battery = s.battery?.let { " · Akku $it %" + if (s.charging) " ⚡" else "" }.orEmpty()
    return "Helio Strap · $link$battery"
}

@Composable
private fun StreakBadge(days: Int) {
    Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Whatshot, contentDescription = null, tint = Color(0xFFF76B15), modifier = Modifier.size(20.dp))
        Text("$days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ConnectionCard(state: StrapUiState) {
    val controller = LocalContext.current.app.repo.controller
    Panel(color = MaterialTheme.colorScheme.errorContainer) {
        Text(
            if (state.link == LinkState.AUTH_FAILED) "Der Strap hat den Auth-Key abgelehnt" else "Strap nicht verbunden",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            state.linkDetail ?: if (state.link == LinkState.AUTH_FAILED) "Prüfe den Auth-Key unter Einstellungen → Strap."
            else "Ist der Strap in der Nähe und keine andere App (Zepp, Gadgetbridge) verbunden?",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        FilledTonalButton(onClick = { controller?.reconnect() }) { Text("Neu verbinden") }
    }
}

/** Big card on top: recovery when known, else steps. */
@Composable
private fun HeroCard(state: StrapUiState, d: HomeData?, stepGoal: Int, openSleep: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), onClick = openSleep) {
        Column(Modifier.padding(20.dp)) {
            val readiness = d?.readiness
            if (readiness != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                        ProgressRing(readiness.score / 100f, scoreColor(readiness.score), Modifier.size(84.dp))
                        Text("${readiness.score}", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Erholung", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(readiness.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            readiness.parts.joinToString(" · ") { "${it.first} ${it.second}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            } else {
                Text("Erholung", style = MaterialTheme.typography.labelLarge)
                Text("Sobald Schlaf- und Pulsdaten da sind, siehst du hier, wie erholt du bist.", style = MaterialTheme.typography.bodyMedium)
            }
            d?.night?.let { n ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Letzte Nacht ${formatHours(n.asleepMinutes)} · Score ${n.score}" + if (n.estimated) " (geschätzt)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(6.dp))
                StackedBar(SleepStage.entries.map { n.minutes(it).toFloat() to Palette.stage(it) }, Modifier.fillMaxWidth().height(8.dp))
            }
        }
    }
}

@Composable
private fun LiveHeartCard(state: StrapUiState, maxHr: Int, rest: Int?, onToggle: (Boolean) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            now = System.currentTimeMillis()
        }
    }
    val fresh = state.heartRate != null && now - state.heartRateAt < 30_000
    val bpm = state.heartRate.takeIf { fresh }
    val zone = bpm?.let { HeartRateZones.zoneOf(it, maxHr, rest) } ?: -1
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Favorite, contentDescription = null, tint = Palette.Heart)
            Spacer(Modifier.width(8.dp))
            Text("Puls live", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = state.liveHeartRate, onCheckedChange = onToggle, enabled = state.connected)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(bpm?.toString() ?: "–", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = if (zone >= 0) Palette.zones[zone] else Palette.Heart)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.padding(bottom = 10.dp)) {
                Text("bpm", style = MaterialTheme.typography.bodyMedium)
                if (zone >= 0) Text("Zone ${zone + 1} · ${HeartRateZones.names[zone]}", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.weight(1f))
            state.hrv?.takeIf { fresh }?.let {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 10.dp)) {
                    Text("${it.toInt()} ms", style = MaterialTheme.typography.titleMedium)
                    Text("HRV live", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (zone >= 0) StackedBar((0..4).map { (if (it == zone) 1f else 0.15f) to Palette.zones[it] }, Modifier.fillMaxWidth().height(6.dp))
        when {
            state.recordingHr -> Text("Puls-Aufzeichnung läuft", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            !state.liveHeartRate && !fresh -> Text(
                "Einschalten, um den Puls in Echtzeit zu sehen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TileGrid(d: HomeData, state: StrapUiState, stepGoal: Int, hidden: Set<String>, order: List<String>, nav: NavHostController) {
    val added = ArrayList<Pair<Tile, @Composable (Modifier) -> Unit>>()
    fun add(tile: Tile, content: @Composable (Modifier) -> Unit) {
        if (tile.name !in hidden) added += tile to content
    }
    val todaySteps = (state.steps?.toInt() ?: d.week.lastOrNull()?.steps ?: 0)
    add(Tile.STEPS) { m ->
        StatTile(
            "Schritte", todaySteps.fmt(), m,
            caption = "${todaySteps * 100 / stepGoal.coerceAtLeast(1)} % von ${stepGoal.fmt()}",
            icon = Icons.AutoMirrored.Filled.DirectionsWalk, tint = Palette.Steps,
            onClick = { nav.navigate(Routes.metric("steps")) },
        ) {
            Spacer(Modifier.height(6.dp))
            StackedBar(listOf((todaySteps.toFloat() / stepGoal).coerceAtMost(1f) to Palette.Steps, (1f - todaySteps.toFloat() / stepGoal).coerceAtLeast(0f) to Color.Transparent), Modifier.fillMaxWidth().height(6.dp))
        }
    }
    add(Tile.SLEEP) { m ->
        val n = d.night
        StatTile(
            "Schlaf", n?.let { formatHours(it.asleepMinutes) } ?: "–", m,
            caption = n?.let {
                "Score ${it.score}" + when {
                    it.edited -> " · korrigiert"
                    it.estimated -> " · geschätzt"
                    else -> " · Tief ${formatMinutes(it.minutes(SleepStage.DEEP))}"
                }
            } ?: "keine Daten",
            icon = Icons.Default.Bedtime, tint = Palette.Sleep,
            onClick = { nav.navigateToTab(Routes.SLEEP) },
        )
    }
    add(Tile.HEART) { m ->
        val trend = if (d.resting != null && d.restingBaseline != null) d.resting - d.restingBaseline else null
        StatTile(
            "Ruhepuls", d.resting?.let { "$it bpm" } ?: "–", m,
            caption = trend?.let { "%+.0f zum Schnitt".format(it) } ?: "Herzfrequenz",
            icon = Icons.Default.MonitorHeart, tint = Palette.Heart,
            onClick = { nav.navigate(Routes.metric("heart")) },
        ) {
            val values = d.hrPoints.takeLast(240).map { it.second }
            if (values.size > 2) Sparkline(values, Palette.Heart)
        }
    }
    add(Tile.STRESS) { m ->
        val avg = d.stress.map { it.stress }.takeIf { it.isNotEmpty() }?.average()?.toInt()
        StatTile(
            "Stress", avg?.toString() ?: "–", m,
            caption = avg?.let { stressLabel(it) } ?: "keine Daten",
            icon = Icons.Default.Psychology, tint = avg?.let { stressColor(it) } ?: Palette.Stress,
            onClick = { nav.navigate(Routes.metric("stress")) },
        )
    }
    add(Tile.SPO2) { m ->
        val v = d.spo2.map { it.value }
        StatTile(
            "SpO₂", if (v.isEmpty()) "–" else "${v.average().toInt()} %", m,
            caption = if (v.isEmpty()) "keine Messung" else "min. ${v.min()} % · ${v.size} Messungen",
            icon = Icons.Default.WaterDrop, tint = Palette.Spo2,
            onClick = { nav.navigate(Routes.metric("spo2")) },
        )
    }
    add(Tile.HRV) { m ->
        StatTile(
            "HRV nachts", d.hrvNight?.let { "${it.toInt()} ms" } ?: "–", m,
            caption = if (d.hrvNight != null && d.hrvBaseline != null) "Schnitt ${d.hrvBaseline.toInt()} ms" else "Herzfrequenzvariabilität",
            icon = Icons.Default.Favorite, tint = Palette.Hrv,
            onClick = { nav.navigate(Routes.metric("hrv")) },
        )
    }
    add(Tile.PAI) { m ->
        StatTile(
            "PAI", d.pai?.total?.toInt()?.toString() ?: "–", m,
            caption = d.pai?.let { "heute +${"%.1f".format(it.today)} · Ziel 100" } ?: "keine Daten",
            icon = Icons.Default.Bolt, tint = Palette.Pai,
            onClick = { nav.navigate(Routes.metric("pai")) },
        )
    }
    add(Tile.TEMPERATURE) { m ->
        StatTile(
            "Hauttemperatur", d.tempNight?.let { "%.1f °C".format(it) } ?: "–", m,
            caption = d.tempDeviation?.let { "%+.1f °C zur Basis".format(it) } ?: "nachts",
            icon = Icons.Default.Thermostat, tint = Palette.Temperature,
            onClick = { nav.navigate(Routes.metric("temperature")) },
        )
    }
    add(Tile.BREATH) { m ->
        StatTile(
            "Atemfrequenz", d.breathNight?.let { "%.0f /min".format(it) } ?: "–", m,
            caption = "im Schlaf",
            icon = Icons.Default.Air, tint = Palette.Breath,
            onClick = { nav.navigate(Routes.metric("respiration")) },
        )
    }
    add(Tile.BATTERY) { m ->
        StatTile(
            "Akku", state.battery?.let { "$it %" } ?: "–", m,
            caption = d.batteryDays?.let { "reicht noch ca. %.1f Tage".format(it) } ?: if (state.charging) "lädt" else "Strap",
            icon = Icons.Default.BatteryStd, tint = Palette.Battery,
            onClick = { nav.navigate(Routes.metric("battery")) },
        )
    }
    val tiles = added.sortedBy { (t, _) -> order.indexOf(t.name).let { if (it < 0) 100 + t.ordinal else it } }.map { it.second }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { it(Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ActivityRow(icon: ImageVector, title: String, subtitle: String, start: Instant, onClick: () -> Unit) {
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(start.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun AlarmCard(d: HomeData, onClick: () -> Unit) {
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Alarm, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val next = d.nextAlarm
                if (next == null) {
                    Text("Kein Wecker aktiv", style = MaterialTheme.typography.titleSmall)
                    Text("Der Strap kann dich sanft per Vibration wecken.", style = MaterialTheme.typography.bodySmall)
                } else {
                    val left = Duration.between(ZonedDateTime.now(), next)
                    Text(
                        "Wecker ${next.format(DateTimeFormatter.ofPattern("EEE HH:mm", de.waschbeken.heliolink.ui.German))}" +
                            d.nextAlarmLabel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text("in ${left.toHours()} h ${left.toMinutes() % 60} min", style = MaterialTheme.typography.bodySmall)
                }
            }
            CountPill("Wecker", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
