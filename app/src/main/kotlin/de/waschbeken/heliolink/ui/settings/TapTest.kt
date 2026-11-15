package de.waschbeken.heliolink.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.waschbeken.heliolink.BuildConfig
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.TapProbe
import kotlinx.coroutines.delay

/** The steps of the test: what to do and whether the strap is being tapped. */
private val steps = listOf(
    Triple("Nicht berühren", "Lass den Strap in Ruhe – die App hört erst einmal nur zu.", false),
    Triple("3× tippen", "Tippe jetzt dreimal kräftig mit dem Finger auf den Strap.", true),
    Triple("Doppelt tippen", "Tippe jetzt zweimal schnell hintereinander, kurz warten, noch einmal zweimal.", true),
)
private const val STEP_SECONDS = 10

/**
 * Finds out whether the strap reports being tapped (see [TapProbe]): ten seconds without touching
 * it, then ten seconds of tapping three times and ten of double taps, while the app records
 * everything the strap sends. The result can be copied or shared as a report.
 */
@Composable
internal fun TapTestDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val controller = app.repo.controller
    val trace by app.repo.trace.collectAsState()
    val state by app.repo.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    // -1 = not started, 0..2 = running step, 3 = done
    var step by remember { mutableIntStateOf(-1) }
    var left by remember { mutableIntStateOf(STEP_SECONDS) }
    var stepStart by remember { mutableLongStateOf(0L) }
    val phases = remember { mutableStateListOf<TapProbe.Phase>() }
    var report by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(step) {
        if (step !in steps.indices) return@LaunchedEffect
        stepStart = System.currentTimeMillis()
        for (s in STEP_SECONDS downTo 1) {
            left = s
            delay(1_000)
        }
        val (name, _, tapping) = steps[step]
        phases += TapProbe.Phase(name, stepStart, System.currentTimeMillis(), tapping)
        if (step == steps.lastIndex) {
            // a moment for the last messages to arrive
            delay(500)
            controller?.traceTaps(false)
            val messages = app.repo.trace.value
            val result = TapProbe.analyse(messages, phases)
            report = TapProbe.report(
                result, phases,
                "HelioLink ${BuildConfig.VERSION_NAME} · Tipp-Test" + (state.firmware?.let { " · Firmware $it" } ?: "") +
                    "\nKontrolle (Akku-Abfrage): " + if (TapProbe.controlArrived(messages)) "angekommen" else "nicht angekommen",
            )
            step = steps.size
        } else {
            step++
        }
    }
    // Leaving early: switch the extra channels off again.
    DisposableEffect(Unit) {
        onDispose { if (step in steps.indices) controller?.traceTaps(false) }
    }

    val result = remember(report) { if (report == null) null else TapProbe.analyse(trace, phases.toList()) }
    AlertDialog(
        onDismissRequest = { if (step !in steps.indices) onDismiss() },
        title = { Text("Tipp-Test") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    step < 0 -> {
                        Text("Prüft, ob dein Strap meldet, wenn du auf ihn tippst. Dauert 30 Sekunden; leg das Handy dafür vor dich hin.")
                        Text(
                            steps.joinToString("\n") { (name, _, _) -> "• $STEP_SECONDS s $name" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    step in steps.indices -> {
                        val (name, what, _) = steps[step]
                        Text("Schritt ${step + 1} von ${steps.size}: $name", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(what)
                        Text("noch $left s", style = MaterialTheme.typography.headlineMedium)
                        LinearProgressIndicator(progress = { (STEP_SECONDS - left) / STEP_SECONDS.toFloat() }, modifier = Modifier.fillMaxWidth())
                        Text("${trace.size} Nachrichten vom Strap bisher", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    result != null -> {
                        val recorded = TapProbe.controlArrived(trace)
                        if (result.reactions.isEmpty() && recorded) {
                            Text("Keine Reaktion", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Die Aufzeichnung lief – die Kontroll-Abfrage kam an. Beim Tippen kam aber nichts vom Strap: Er meldet Antippen nicht.")
                        } else if (result.reactions.isEmpty()) {
                            Text("Nichts empfangen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Auch die Kontroll-Abfrage kam nicht an, das Ergebnis ist also nicht sicher. Prüfe, ob der Strap verbunden ist, und starte den Test noch einmal.")
                        } else {
                            Text("Der Strap hat reagiert", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Nur beim Tippen kam: ${result.reactions.joinToString(", ")}. Schick mir den Bericht – damit lässt sich „3× tippen = Eintrag“ bauen.")
                        }
                        Text(
                            report.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(report.orEmpty())) }) { Text("Kopieren") }
                            TextButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report.orEmpty())
                                context.startActivity(Intent.createChooser(send, "Bericht teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) { Text("Teilen") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                step < 0 -> TextButton(
                    enabled = state.connected && controller != null,
                    onClick = {
                        controller?.traceTaps(true)
                        step = 0
                    },
                ) { Text(if (state.connected) "Starten" else "Strap nicht verbunden") }
                step >= steps.size -> TextButton(onClick = onDismiss) { Text("Fertig") }
            }
        },
        dismissButton = {
            if (step < steps.size) {
                TextButton(onClick = {
                    if (step in steps.indices) controller?.traceTaps(false)
                    onDismiss()
                }) { Text("Abbrechen") }
            }
        },
    )
}
