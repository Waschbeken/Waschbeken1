package de.waschbeken.heliolink.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.data.GadgetbridgeImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lets the user pick a Gadgetbridge export (zip) and imports it, with a progress and result dialog.
 * Returns the function that opens the file picker.
 */
@Composable
fun rememberGadgetbridgeImport(onImported: (GadgetbridgeImport.Result) -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        progress = "Lese Export …"
        scope.launch {
            val app = context.app
            val outcome = withContext(Dispatchers.IO) {
                try {
                    GadgetbridgeImport(context, app.prefs, app.db).run(uri) { text -> progress = text }
                } catch (e: GadgetbridgeImport.ImportException) {
                    e
                } catch (e: Exception) {
                    Exception("Import fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            progress = null
            when (outcome) {
                is GadgetbridgeImport.Result -> {
                    app.repo.dataChanged()
                    result = outcome.summary
                    onImported(outcome)
                }
                is Exception -> result = outcome.message
            }
        }
    }

    progress?.let { text ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Gadgetbridge-Import") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
    }
    result?.let { text ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text("Gadgetbridge-Import") },
            text = {
                Text(text + "\n\nWichtig: In Gadgetbridge den Strap trennen oder Gadgetbridge beenden – es kann immer nur eine App verbunden sein.")
            },
            confirmButton = { TextButton(onClick = { result = null }) { Text("OK") } },
        )
    }

    return { launcher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")) }
}
