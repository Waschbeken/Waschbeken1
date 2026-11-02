package de.waschbeken.heliolink.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import de.waschbeken.heliolink.BuildConfig
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.Changelog

/** The release notes shipped with this version (assets/changelog.md). */
fun loadChangelog(context: Context): List<Changelog.Entry> = try {
    context.assets.open("changelog.md").bufferedReader().use { Changelog.parse(it.readText()) }
} catch (e: Exception) {
    emptyList()
}

private val currentVersion get() = BuildConfig.VERSION_NAME.substringBefore("-")

@Composable
private fun EntryItems(entry: Changelog.Entry) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entry.items.forEach { item ->
            Row {
                Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                Text(item, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * After an update: what changed since the version the user saw last. Shown once; a fresh install
 * (no strap set up yet) does not get it.
 */
@Composable
fun WhatsNewDialog() {
    val context = LocalContext.current
    val prefs = context.app.prefs
    val entries = remember {
        val last = prefs.lastSeenVersion
        when {
            last == currentVersion -> emptyList()
            last.isBlank() && !prefs.isConfigured -> emptyList()
            else -> Changelog.since(loadChangelog(context), last.ifBlank { null }, currentVersion)
        }
    }
    var open by remember { mutableStateOf(entries.isNotEmpty()) }
    // Nothing to show (fresh install, same version): just remember the version.
    LaunchedEffect(Unit) { if (entries.isEmpty() && prefs.lastSeenVersion != currentVersion) prefs.lastSeenVersion = currentVersion }
    if (!open) return
    fun close() {
        prefs.lastSeenVersion = currentVersion
        open = false
    }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text("Neu in HelioLink $currentVersion") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                entries.forEach { e ->
                    if (entries.size > 1) Text("Version ${e.version}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    EntryItems(e)
                }
            }
        },
        confirmButton = { TextButton(onClick = ::close) { Text("Super") } },
    )
}

/** Mehr → Was ist neu: every version with its changes. */
@Composable
fun WhatsNewScreen(nav: NavHostController) {
    val context = LocalContext.current
    val entries = remember { loadChangelog(context) }
    Scaffold(topBar = { BackTopBar("Was ist neu", onBack = { nav.popBackStack() }, subtitle = "Installiert: $currentVersion") }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (entries.isEmpty()) item { Text("Keine Versionshinweise gefunden.") }
            items(entries, key = { it.version }) { e ->
                Panel {
                    Text("Version ${e.version}", style = MaterialTheme.typography.titleMedium)
                    EntryItems(e)
                }
            }
        }
    }
}
