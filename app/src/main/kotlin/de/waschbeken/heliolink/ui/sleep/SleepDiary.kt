package de.waschbeken.heliolink.ui.sleep

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.data.Queries
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.dbValue
import de.waschbeken.heliolink.ui.events.CollapsibleTagPicker
import de.waschbeken.heliolink.ui.formatMinutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Diary tags for the night ending on [date]: what happened the day before. */
@Composable
fun SleepDiary(date: LocalDate) {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val day = date.toEpochDay()
    val loaded by dbValue(day) { it.sleepTags(day, day)[day].orEmpty() }
    val entries by dbValue(day) { Queries.eventsForNight(it, date, ZoneId.systemDefault()) }
    var tags by remember(day) { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(loaded) { if (tags == null && loaded != null) tags = loaded }
    val current = tags ?: loaded ?: return
    Panel {
        Text("Tagebuch", style = MaterialTheme.typography.titleMedium)
        entries?.takeIf { it.isNotEmpty() }?.let { list ->
            Text(
                "Schnell eingetragen (zählt automatisch): " + list.joinToString(", ") {
                    it.tag + " " + java.time.Instant.ofEpochSecond(it.timestamp).atZone(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        CollapsibleTagPicker(
            "sleep-diary", "Was war am Tag davor?", Queries.SLEEP_TAGS, current,
            hint = "Nach ein paar Wochen zeigt die Wochen- und Monatsansicht, was deinem Schlaf guttut.",
        ) { tag ->
            val on = tag in current
            tags = if (on) current - tag else current + tag
            scope.launch { withContext(Dispatchers.IO) { app.db.setSleepTag(day, tag, !on) } }
        }
    }
}


/** Average score of nights with and without each diary tag. */
@Composable
fun TagEffects(version: Int) {
    val effects by dbValue(version) { Queries.tagEffects(it, ZoneId.systemDefault()) }
    val list = effects.orEmpty()
    Panel {
        Text("Was deinem Schlaf guttut", style = MaterialTheme.typography.titleMedium)
        if (list.isEmpty()) {
            Text(
                "Markiere im Tagebuch der Tagesansicht ein paar Nächte (z. B. Sport, Koffein). Ab zwei Nächten mit und ohne erscheint hier der Vergleich.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        list.take(8).forEach { e ->
            val diff = e.scoreWith - e.scoreWithout
            Row {
                Text(e.tag, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    (if (diff >= 0) "+$diff" else "$diff") + " Score",
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        diff >= 3 -> Color(0xFF30A46C)
                        diff <= -3 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                "${e.nightsWith} Nächte · Ø ${formatMinutes(e.minutesWith)} statt ${formatMinutes(e.minutesWithout)} Schlaf",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
