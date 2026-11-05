package de.waschbeken.heliolink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

/** A question with its answer and, where it helps, the page that solves it. */
private class HelpItem(val question: String, val answer: String, val route: String? = null, val action: String? = null)

private val HELP = listOf(
    HelpItem(
        "In einem Diagramm fehlen Stunden oder Werte",
        "Der Strap gibt seine Daten in Paketen heraus; meist kommen sie mit dem nächsten Abruf (nach unten wischen holt sofort alles). " +
            "Unter Synchronisierung → Datenstand steht pro Datenart, bis wann die Daten da sind. Fehlt danach noch etwas, tippe dort die " +
            "Datenart an: HelioLink holt die letzten 7 Tage noch einmal. Ohne Messung gibt es natürlich keine Werte – prüfe unter " +
            "„Messungen“, ob z. B. SpO₂ oder Stress eingeschaltet sind.",
        Routes.settings("sync"), "Datenstand öffnen",
    ),
    HelpItem(
        "Diagramme bedienen",
        "Finger auf einem Diagramm halten zeigt die Werte an dieser Stelle (beim Schlaf die Phase mit Beginn und Dauer). Mit zwei " +
            "Fingern oder einem Doppeltipp zoomst du hinein; „Ganzer Tag“ bzw. „Ganze Nacht“ setzt den Zoom zurück. Das ⓘ oben " +
            "auf den Detailseiten erklärt, was ein Wert bedeutet.",
    ),
    HelpItem(
        "Warum steht auf „Heute“ morgens noch „Gestern“?",
        "Nach Mitternacht zeigt die Startseite den Vortag, bis du aufgewacht bist – sonst stünde dort nachts überall 0. Sobald nach " +
            "der Nacht ein paar wache Minuten mit dem Strap aufgezeichnet sind (oder spätestens um 12 Uhr), wechselt sie auf den neuen Tag. " +
            "Nach dem Aufstehen einmal nach unten wischen beschleunigt das.",
    ),
    HelpItem(
        "Der Strap-Akku hält kürzer als erwartet",
        "Am meisten kosten Live-Puls und Pulsaufzeichnung, „Puls an andere Geräte senden“, SpO₂ ganztägig und Puls jede Minute. " +
            "Unter Akku & Leistung stellst du mit einem Tipp ein Profil ein und siehst den Verbrauch pro Tag – auch seit dem letzten Wechsel.",
        Routes.settings("battery"), "Akku & Leistung öffnen",
    ),
    HelpItem(
        "Wie entsteht der Schlaf-Score?",
        "Liefert der Strap einen eigenen Score, zeigt HelioLink diesen – er bewertet auch Schlafenszeit und zu langen Schlaf. Darunter " +
            "steht HelioLinks Schätzung mit Teilpunkten (Dauer, Tief, REM, Ruhe). Auf der Schlafseite erklärt „Wie entsteht der Score?“ " +
            "beides mit den Zahlen deiner Nacht. Stimmen Einschlafen oder Aufwachen nicht, korrigiere die Nacht mit dem Stift.",
        Routes.SLEEP, "Schlaf öffnen",
    ),
    HelpItem(
        "Der Strap verbindet sich nicht",
        "Der Strap spricht nur mit einer App gleichzeitig: Zepp oder Gadgetbridge dürfen nicht verbunden sein. Bluetooth muss an sein " +
            "und der Strap in der Nähe. Unter Gerät & Verbindung hilft „Neu verbinden“; meldet die App „Auth-Key abgelehnt“, wurde der Strap " +
            "neu gekoppelt und braucht den neuen Schlüssel.",
        Routes.settings("device"), "Gerät & Verbindung öffnen",
    ),
    HelpItem(
        "Firmware-Update für den Strap",
        "Updates kommen nur über die Zepp-App. Unter Gerät & Verbindung pausiert „Zur Zepp-App wechseln“ HelioLink, damit Zepp den Strap " +
            "erreicht. Danach in HelioLink „Wieder verbinden“. Den Strap in Zepp nicht entkoppeln – sonst braucht er einen neuen Auth-Key. " +
            "Fehlen nach einem Update die Schlafphasen: Synchronisierung → Schlafphasen → „Suche zurücksetzen“.",
        Routes.settings("device"), "Gerät & Verbindung öffnen",
    ),
    HelpItem(
        "Updates ohne Nachfrage installieren (Shizuku)",
        "Mit Shizuku installiert HelioLink Updates selbst und öffnet sich danach wieder. Shizuku muss nach jedem Neustart des Handys " +
            "einmal gestartet werden. Ist es nicht bereit, kommt wie gewohnt der Android-Installer.",
        Routes.settings("updates"), "App-Updates öffnen",
    ),
    HelpItem(
        "Meine Daten sichern",
        "Alle Daten liegen nur auf deinem Handy. Unter Daten & Sicherung legst du eine Sicherung an (auf Wunsch mit Passwort " +
            "verschlüsselt) oder lässt sie automatisch in einen Ordner schreiben – wichtig vor einem Handywechsel.",
        Routes.settings("data"), "Daten & Sicherung öffnen",
    ),
    HelpItem(
        "Was verlässt mein Handy?",
        "Nichts von deinen Gesundheitsdaten. Die einzige Verbindung ins Internet ist die Update-Suche bei GitHub (abschaltbar). " +
            "Der Arzt-Bericht entsteht auf dem Handy und wird nur geteilt, wenn du es selbst tust.",
        Routes.settings("privacy"), "Datenschutz öffnen",
    ),
)

/** Mehr → Hilfe & Tipps: answers to what comes up most. */
@Composable
fun HelpScreen(nav: NavHostController) {
    var open by rememberSaveable { mutableStateOf(-1) }
    Scaffold(topBar = { BackTopBar("Hilfe & Tipps", onBack = { nav.popBackStack() }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(HELP) { i, item ->
                Panel(onClick = { open = if (open == i) -1 else i }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.question, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Icon(if (open == i) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                    }
                    if (open == i) {
                        Text(item.answer, style = MaterialTheme.typography.bodyMedium)
                        val route = item.route
                        if (route != null && item.action != null) {
                            androidx.compose.material3.TextButton(onClick = {
                                if (route == Routes.SLEEP) nav.navigateToTab(route) else nav.navigate(route)
                            }) { Text(item.action + " ›") }
                        }
                    }
                }
            }
        }
    }
}
