package de.waschbeken.heliolink.ui.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.waschbeken.heliolink.core.SleepSession
import de.waschbeken.heliolink.core.SleepStage
import de.waschbeken.heliolink.ui.Palette
import de.waschbeken.heliolink.ui.Panel
import de.waschbeken.heliolink.ui.shortMinutes
import de.waschbeken.heliolink.ui.stageName

/** The share of the night adults usually spend in each stage, in percent. */
private val usual = mapOf(
    SleepStage.DEEP to (13 to 23),
    SleepStage.LIGHT to (45 to 55),
    SleepStage.REM to (20 to 25),
    SleepStage.AWAKE to (0 to 10),
)

private val inRange = Color(0xFF46A758)
private val outOfRange = Color(0xFFF59E0B)

/**
 * The night's stages one below the other: how long, which share, and a bar against the usual
 * range (lightly shaded), so it shows at a glance where the night was short of deep sleep or long
 * on REM.
 */
@Composable
internal fun StageShares(n: SleepSession) {
    val total = n.durationMinutes.coerceAtLeast(1)
    val order = listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE)
    val scale = maxOf(60, order.maxOf { n.minutes(it) * 100 / total } + 5)
    Panel {
        Text("So verteilt sich die Nacht", style = MaterialTheme.typography.titleMedium)
        order.forEach { s ->
            val minutes = n.minutes(s)
            val pct = minutes * 100 / total
            val (lo, hi) = usual.getValue(s)
            val status = when {
                pct < lo -> "wenig ·"
                pct > hi -> "viel ·"
                else -> "✓"
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(Palette.stage(s), RoundedCornerShape(3.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text(stageName(s), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${shortMinutes(minutes)} · $pct %", style = MaterialTheme.typography.bodyMedium)
                }
                ShareBar(pct, lo, hi, scale, Palette.stage(s))
                Text(
                    "$status üblich " + if (lo == 0) "bis $hi %" else "$lo–$hi %",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (pct in lo..hi) inRange else outOfRange,
                )
            }
        }
    }
}

/** A track from 0 to [scale] percent: the usual range shaded, the night's share as a bar on top. */
@Composable
private fun ShareBar(pct: Int, lo: Int, hi: Int, scale: Int, color: Color) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val edge = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        fun x(p: Int) = p.toFloat() / scale * size.width
        drawRoundRect(track, cornerRadius = r)
        // the usual range, marked at both ends
        drawRect(color.copy(alpha = 0.28f), Offset(x(lo), 0f), Size(x(hi) - x(lo), size.height))
        drawLine(edge, Offset(x(lo), 0f), Offset(x(lo), size.height), 1.5f)
        drawLine(edge, Offset(x(hi), 0f), Offset(x(hi), size.height), 1.5f)
        if (pct > 0) drawRoundRect(color, Offset(0f, size.height * 0.2f), Size(x(pct).coerceAtLeast(size.height), size.height * 0.6f), r)
    }
}
