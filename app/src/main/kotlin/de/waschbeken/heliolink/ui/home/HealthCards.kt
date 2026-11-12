package de.waschbeken.heliolink.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.waschbeken.heliolink.core.BodyCheck
import de.waschbeken.heliolink.core.Substances
import de.waschbeken.heliolink.data.Queries
import de.waschbeken.heliolink.ui.dbValue
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.graphics.Color
import de.waschbeken.heliolink.ui.Panel

/** Early warning on the start page when last night looked unusual. */
@Composable
fun BodyCheckCard(result: BodyCheck.Result, hints: List<String>) {
    val alert = result.level == BodyCheck.Level.ALERT
    val container = if (alert) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val content = if (alert) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    // Collapsed, the card keeps its title and the values that stand out; the advice folds away.
    val sections = de.waschbeken.heliolink.ui.rememberSections()
    val open = sections.isOpen("home-bodycheck", default = true)
    Card(colors = CardDefaults.cardColors(containerColor = container, contentColor = content)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { sections.toggle("home-bodycheck", default = true) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.HealthAndSafety, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (alert) "Dein Körper arbeitet mehr als sonst" else "Letzte Nacht war etwas anders",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (open) "Zuklappen" else "Aufklappen",
                )
            }
            Text(
                result.signals.joinToString(" · ") { "${it.vital.label} ${it.text}" } +
                    if (result.nights > 1) " – seit ${result.nights} Nächten" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!open) return@Column
            Text(
                if (alert) {
                    "So sieht es oft aus, bevor man krank wird. Gönn dir heute Ruhe, trink genug und schlaf früh. " +
                        "Fühlst du dich krank oder wird es nicht besser, frag ärztlich nach."
                } else {
                    "Einzeln heißt das wenig – behalte es im Auge. Steigen morgen mehrere Werte, meldet sich HelioLink deutlicher."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (hints.isNotEmpty()) {
                Text(
                    "Gestern eingetragen: ${hints.joinToString(", ")} – das kann solche Werte auch erklären.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                "Vergleich mit deinen letzten ${result.baselineNights} Nächten · keine Diagnose",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private val hm = DateTimeFormatter.ofPattern("HH:mm")

/** "Noch etwa 6 h 30 min im Körper (bis ca. 05:40, dann unter 10 %)." or "Weitgehend abgebaut." */
private fun inBody(gone: Long?, now: Long, what: String): String {
    if (gone == null) return "Noch über 2 Tage im Körper."
    if (gone <= now) return "Weitgehend abgebaut."
    val minutes = ((gone - now) / 60).toInt()
    val left = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    return "Noch etwa $left im Körper (bis ca. ${clock(gone)}, dann $what)."
}
private fun clock(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(hm)

/** What the substances card shows, loaded off the main thread. */
private class SubstanceData(val intakes: Map<Substances.Kind, List<Substances.Intake>>, val bedtime: Long)

/**
 * Caffeine and nicotine from the quick entries: how much is still in the body now and at your usual
 * bedtime. Part of the quick entry card; shows nothing without an entry in the last day.
 */
@Composable
fun SubstancesSection(dataVersion: Int) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis() / 1000
        }
    }
    val data by de.waschbeken.heliolink.ui.cachedDbValue("substances", dataVersion, now / 900) { db ->
        val zone = ZoneId.systemDefault()
        val t = System.currentTimeMillis() / 1000
        val intakes = db.events(t - 36 * 3600, t + 60).mapNotNull { e ->
            Substances.of(e.tag)?.let { (kind, amount) -> kind to Substances.Intake(e.timestamp, amount) }
        }.groupBy({ it.first }, { it.second })
        // Usual bedtime: median start of the last two weeks' nights, otherwise 23:00.
        val today = LocalDate.now()
        val starts = if (intakes.isEmpty()) emptyList() else Queries.nightSummaries(db, today.minusDays(14), today, zone).values
            .map { Instant.ofEpochSecond(it.start).atZone(zone).toLocalTime().let { lt -> (lt.toSecondOfDay() / 60 + 12 * 60) % (24 * 60) } }
        val minutes = if (starts.size >= 3) (starts.sorted()[starts.size / 2] - 12 * 60 + 24 * 60) % (24 * 60) else 23 * 60
        var bed = today.atTime(LocalTime.of(minutes / 60, minutes % 60)).atZone(zone).toEpochSecond()
        if (bed < t - 3 * 3600) bed += 86_400
        SubstanceData(intakes, bed)
    }
    val d = data ?: return
    val recent = d.intakes.filterValues { list -> list.any { it.timestamp > now - 24 * 3600 } }
    if (recent.isEmpty()) return
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Coffee, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("Im Körper", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("Schlafenszeit ~${clock(d.bedtime)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        recent[Substances.Kind.CAFFEINE]?.let { list ->
            val kind = Substances.Kind.CAFFEINE
            // Never before the newest entry, so a fresh entry counts in full right away.
            val t = maxOf(now, list.maxOf { it.timestamp })
            val level = Substances.level(list, kind, t)
            val atBed = Substances.level(list, kind, d.bedtime)
            Text("Koffein: ca. ${level.toInt()} mg jetzt · ca. ${atBed.toInt()} mg zur Schlafenszeit", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { (level / 400.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp))
            Text(
                inBody(Substances.timeBelow(list, kind, 25.0, t), t, "unter 25 mg") +
                    if (atBed >= 50) " Zur Schlafenszeit ist noch genug da, um den Schlaf zu stören." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        recent[Substances.Kind.NICOTINE]?.let { list ->
            val kind = Substances.Kind.NICOTINE
            val last = list.maxOf { it.timestamp }
            val t = maxOf(now, last)
            val ago = (t - last) / 60
            val lastAmount = list.filter { it.timestamp == last }.sumOf { it.amount }
            val share = Substances.level(list, kind, t) / lastAmount
            Text(
                "Nikotin: zuletzt vor " + (if (ago >= 60) "${ago / 60} h ${ago % 60} min" else "$ago min") +
                    " · noch etwa ${(share * 100).toInt().coerceIn(0, 100)} % davon",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(progress = { share.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp))
            Text(
                inBody(Substances.timeBelow(list, kind, lastAmount * 0.1, t), t, "unter 10 %"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Short German HRV status for the tile. */
fun hrvCaption(d: HomeData): String = d.hrvStatus?.let { "${it.status.label} · 7 Tage ${it.weekAverage.toInt()} ms" }
    ?: if (d.hrvNight != null && d.hrvBaseline != null) "Schnitt ${d.hrvBaseline.toInt()} ms" else "Herzfrequenzvariabilität"

/** Hint when an irregular heart rhythm was measured repeatedly; "Verstanden" hides it until a new one comes. */
@Composable
fun RhythmHintCard(rows: List<de.waschbeken.heliolink.data.HealthDb.RhythmRow>, onSeen: () -> Unit, onOpen: () -> Unit) {
    val days = rows.map { Instant.ofEpochSecond(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate() }.toSet().size
    Card(onClick = onOpen, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MonitorHeart, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("Herzschlag mehrmals unregelmäßig", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "An $days Tagen wirkte dein Herzschlag in Ruhe unregelmäßig (${rows.size} Abschnitte). Das kann an der Messung " +
                    "liegen – Bewegung oder ein lockerer Sitz stören den Sensor. Kommt es öfter vor oder spürst du Herzstolpern, " +
                    "Schwindel oder Atemnot, lass es ärztlich mit einem EKG abklären. Keine Diagnose.",
                style = MaterialTheme.typography.bodySmall,
            )
            androidx.compose.material3.TextButton(onClick = onSeen) { Text("Verstanden") }
        }
    }
}


/** "Heute fällt auf": the few things that differ clearly from your normal; opens and closes. */
@Composable
fun InsightsCard(list: List<de.waschbeken.heliolink.core.DailyInsights.Insight>) {
    val sections = de.waschbeken.heliolink.ui.rememberSections()
    val open = sections.isOpen("body-insights", default = true)
    Panel {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().clickable { sections.toggle("body-insights", default = true) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Heute fällt auf", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (open) "Zuklappen" else "Aufklappen",
            )
        }
        if (!open) {
            Text(
                "${list.size} ${if (list.size == 1) "Punkt" else "Punkte"} – tippen zum Aufklappen",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Panel
        }
        list.forEach { insight ->
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.Top) {
                val color = when (insight.tone) {
                    de.waschbeken.heliolink.core.DailyInsights.Tone.GOOD -> Color(0xFF30A46C)
                    de.waschbeken.heliolink.core.DailyInsights.Tone.WATCH -> Color(0xFFF76B15)
                    de.waschbeken.heliolink.core.DailyInsights.Tone.NEUTRAL -> MaterialTheme.colorScheme.primary
                }
                androidx.compose.foundation.layout.Box(
                    Modifier.padding(top = 6.dp, end = 10.dp).size(8.dp).background(color, androidx.compose.foundation.shape.CircleShape),
                )
                Text(insight.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
