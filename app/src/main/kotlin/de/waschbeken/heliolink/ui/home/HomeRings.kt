package de.waschbeken.heliolink.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.ProgressRing
import de.waschbeken.heliolink.ui.fmt
import de.waschbeken.heliolink.ui.formatHours
import de.waschbeken.heliolink.ui.metrics.PaiRing
import de.waschbeken.heliolink.ui.metrics.paiGain
import de.waschbeken.heliolink.ui.scoreColor
import java.time.Instant
import java.time.ZoneId

/** One ring of the row on top of "Heute". */
private class Ring(
    val label: String,
    val value: String,
    val caption: String,
    val onClick: () -> Unit,
    val draw: @Composable (Modifier) -> Unit,
)

/**
 * The day at a glance like Gadgetbridge's dashboard: recovery, sleep, steps and PAI as rings with
 * the value in the middle. Sleep, steps and PAI follow the tiles the user hid in the settings.
 * Tapping the recovery ring shows what its points are made of.
 */
@Composable
internal fun RingsCard(
    d: HomeData?,
    steps: Int,
    stepGoal: Int,
    sleepGoalMinutes: Int,
    hidden: Set<String>,
    detailsOpen: Boolean,
    onToggleDetails: () -> Unit,
    openSleep: () -> Unit,
    openMetric: (String) -> Unit,
) {
    val rings = ArrayList<Ring>()
    val readiness = d?.readiness
    // "Mittel – lockerer Tag" is too long under a ring; the first word says it.
    rings += Ring("Erholung", readiness?.score?.toString() ?: "–", readiness?.label?.substringBefore(" –") ?: "", onToggleDetails) { m ->
        ProgressRing((readiness?.score ?: 0) / 100f, readiness?.let { scoreColor(it.score) } ?: Palette.Hrv, m)
    }
    if (Tile.SLEEP.name !in hidden) {
        val n = d?.night
        rings += Ring(
            "Schlaf",
            n?.let { formatHours(it.asleepMinutes).removeSuffix(" h") } ?: "–",
            n?.let { "Score ${it.score}" } ?: "",
            openSleep,
        ) { m -> ProgressRing((n?.asleepMinutes ?: 0) / sleepGoalMinutes.coerceAtLeast(1).toFloat(), Palette.Sleep, m) }
    }
    if (Tile.STEPS.name !in hidden) {
        val goal = stepGoal.coerceAtLeast(1)
        rings += Ring("Schritte", if (d == null) "–" else steps.fmt(), if (d == null) "" else "${steps * 100 / goal} %", { openMetric("steps") }) { m ->
            ProgressRing(steps / goal.toFloat(), Palette.Steps, m)
        }
    }
    if (Tile.PAI.name !in hidden) {
        val pai = d?.pai
        // A value from an earlier day still gives the total, but nothing was added today yet.
        val today = pai?.takeIf { Instant.ofEpochSecond(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate() == d?.date }?.today ?: 0f
        rings += Ring("PAI", pai?.total?.toInt()?.toString() ?: "–", pai?.let { "+${paiGain(today)} heute" } ?: "", { openMetric("pai") }) { m ->
            PaiRing(pai?.total ?: 0f, today, m)
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = 6.dp
                val size: Dp = ((maxWidth - gap * (rings.size - 1)) / rings.size).coerceAtMost(96.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    rings.forEach { RingItem(it, size) }
                }
            }
            if (detailsOpen && readiness != null) {
                Text(
                    "Erholung aus: " + readiness.parts.joinToString(" · ") { "${it.first} ${it.second}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun RingItem(ring: Ring, size: Dp) {
    Column(
        Modifier.width(size).clip(RoundedCornerShape(14.dp)).clickable(onClick = ring.onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(size).padding(3.dp), contentAlignment = Alignment.Center) {
            ring.draw(Modifier.size(size - 6.dp))
            Text(
                ring.value,
                fontSize = when {
                    ring.value.length > 5 -> 13.sp
                    ring.value.length > 3 -> 16.sp
                    else -> 21.sp
                },
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        Text(ring.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Text(
            ring.caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A value of the day in one compact line: icon, value with a short extra, the name below. */
@Composable
internal fun MiniTile(
    label: String,
    value: String,
    modifier: Modifier,
    icon: ImageVector,
    tint: Color,
    detail: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(modifier.clip(shape).clickable(onClick = onClick), shape = shape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).background(tint.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row {
                    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.alignByBaseline())
                    if (detail != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                }
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
