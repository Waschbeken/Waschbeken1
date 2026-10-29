# HelioLink

Eine schlanke Android-App für das **Amazfit Helio Strap** – ohne Zepp-Cloud, ohne Konto,
alle Daten bleiben auf dem Handy. Inspiriert von [Gadgetbridge](https://gadgetbridge.org),
aber ganz auf einen Strap ohne Display zugeschnitten.

## Was die App kann

| Bereich | Funktionen |
|---|---|
| **Heute** | Begrüßung, Erholungswert (aus Schlaf, Ruhepuls, HRV und Stress), Kacheln für Schritte, Schlaf, Ruhepuls, Stress, SpO₂, HRV, PAI, Hauttemperatur, Atemfrequenz und Akku – jede mit Mini-Verlauf, antippen öffnet die Detailansicht. Live-Puls mit Zone, Ziel-Serie in Tagen |
| **Daten** | Eigener Tab wie in Gadgetbridge: ein Datum für alle Seiten, wischen zwischen Aktivität, Puls, Schlaf, Schritte, Stress, SpO₂, HRV, Temperatur, Atmung, PAI und Akku. Tagesuhr (24 h) mit Aktivität, Schlaf und „nicht getragen“, Aktivitätsdiagramm mit Balken und Pulslinie. Seiten „Schlaf-Woche“ und „Schritte-Woche“. Alle Zeitdiagramme lassen sich mit zwei Fingern zoomen und verschieben, Doppeltipp zoomt hinein oder zurück; weit hineingezoomt stehen die Werte an den Punkten. **Finger halten** zeigt Zeit und Wert – im Schlaf, im Aktivitätsdiagramm und auf der Tagesuhr die Phase mit Beginn, Ende und Dauer |
| **Schnell eintragen** | Auf „Heute“ mit einem Tipp Koffein, Nikotin, Alkohol, Essen, Medikament … notieren (Knöpfe frei änderbar, Karte zuklappbar). Die Einträge erscheinen als Markierung in den Tagesdiagrammen und zählen automatisch für die folgende Nacht in „Was deinem Schlaf guttut“ |
| **Frühwarnung** | Weichen nachts mehrere Werte zugleich von deinem Normalwert ab (Hauttemperatur und Ruhepuls hoch, HRV niedrig, Atmung schneller), zeigt „Heute“ eine Karte und morgens kommt eine Benachrichtigung – oft einen Tag bevor man sich krank fühlt. Vergleich mit den letzten 30 Nächten, Hinweis auf Einträge wie Alkohol oder Sport, keine Diagnose |
| **Im Körper** | Aus den Schnell-Einträgen: wie viel Koffein und Nikotin gerade noch im Körper ist, wie viel zur üblichen Schlafenszeit und ab wann es abgebaut ist |
| **Wirkung auf den Puls** | Was Koffein, Nikotin, Alkohol … mit deinem Ruhepuls machen: die zwei Stunden nach jedem Eintrag im Vergleich zur selben Uhrzeit an Tagen ohne – nur ruhige Minuten, ab 3 Einträgen |
| **HRV-Status** | Schnitt der letzten 7 Tage gegen deinen üblichen Bereich: ausgeglichen, niedrig, sehr niedrig oder ungewöhnlich hoch, mit Verlauf (braucht Nacht-HRV aus dem Import oder Morgenmessungen) |
| **Sauerstoff-Abfälle** | Pro Nacht: Abfälle des SpO₂ um mindestens 3 %, pro Stunde bewertet (Hinweis auf Atemaussetzer), niedrigster Wert und Minuten unter 90 % |
| **Herzrhythmus** | Während der Puls live läuft und du ruhig bist, prüft HelioLink je 64 Herzschläge auf einen unregelmäßigen Rhythmus (RMSSD, Wendepunkte, Entropie); wiederholte Treffer an mehreren Tagen ergeben einen Hinweis – keine Diagnose |
| **Beste Einschlafzeit** | Aus den letzten 90 Nächten: in welcher Stunde deine besten Nächte beginnen |
| **Helfer** | Auf „Heute“: **Atemübung** (6 Atemzüge pro Minute mit Kreis, optional Takt am Arm, Puls und HRV vorher/nachher als Eintrag gespeichert), **HRV messen** (1 Minute in Ruhe, z. B. morgens – liefert den täglichen Wert für den HRV-Status und prüft den Herzrhythmus, Erinnerung am Morgen), **Fokus-Timer** (z. B. 25/5 × 4: der Strap vibriert zu Fokus und Pause, läuft im Hintergrund weiter) |
| **Am Arm vibrieren** | Anrufe (3× lang) und Nachrichten gewählter Apps (2× kurz) lassen den Strap vibrieren – nicht im Schlaf und nicht bei „Bitte nicht stören“. Nur die sendende App wird angesehen, nie der Inhalt |
| **Stress-Momente** | Nach längerem hohem Stress ohne Sport fragt HelioLink per Benachrichtigung „Was war los?“ (höchstens 3× am Tag, nicht nachts); die Stressseite zeigt deine häufigsten Auslöser |
| **Pulsaufzeichnung** | Auf den aktuellen Puls tippen, Dauer wählen (1 min bis 2 h oder bis zum Stoppen): jede Sekunde wird aufgezeichnet, automatisch beendet und gespeichert – mit Live-Ansicht und zoombarer Auswertung. Dazu „Was machst du gerade?“, „Hast du etwas genommen?“ und eine Notiz |
| **Details** | Jede Messgröße als Tag, Woche, Monat, 3 Monate, 6 Monate, 1 Jahr oder 3 Jahre (ab 3 Monaten pro Woche, ab 1 Jahr pro Monat): Kurven, Balken, Min/Max, Trend und Vergleich zum eigenen Normalwert |
| **Puls** | Aktueller Puls groß, Verlauf mit farbigen Zonenflächen und markiertem Tiefst-/Höchstwert, Ruhepuls im Vergleich zu deinem Schnitt, Puls im Schlaf, Zeit in Zonen mit bpm-Bereichen, Stundenverlauf, Verteilung, Trainings des Tages. Über längere Zeit: Ruhepuls-Trend, Spanne, intensive Minuten, Rekorde |
| **Schlaf** | Hypnogramm (Wach/REM/Leicht/Tief), Score, Phasen in Minuten und Prozent, Einschlaf- und Aufwachzeit, Wachphasen, Puls/SpO₂/HRV/Atmung/Temperatur in der Nacht. Nickerchen am Tag. Woche und Monat mit gestapelten Balken, Regelmäßigkeit, Schlafdefizit und Liste der Nächte |
| **Nachtverlauf** | Hauttemperatur, Atmung, Puls, SpO₂ und HRV über die Nacht mit Schlafphasen-Leiste, gemeinsam zoombar |
| **Schlaf korrigieren** | Stift oben rechts oder Hypnogramm antippen: Einschlaf-/Aufwachzeit ändern, Phase eines Abschnitts oder eines Zeitraums ändern, Schlaf selbst eintragen, „Das war kein Schlaf“. Morgens fragt die App „Stimmt deine Nacht?“. Aus deinen Korrekturen schlägt sie eine Kalibrierung für alle Nächte vor |
| **Schlaf-Tagebuch** | Pro Nacht Sport, Koffein, Nikotin, Alkohol, Stress … markieren (Auswahl aufklappbar, zugeklappt steht das Gewählte in einer Zeile); Woche/Monat zeigt, wie sich das auf Score und Schlafdauer auswirkt |
| **Belastung & Erholung** | Trainingsbelastung (TRIMP aus dem Puls) pro Tag, die letzten 7 Tage gegen die 4 Wochen davor (wenig / im grünen Bereich / hoch / zu viel), Erholungszeit in Stunden, Fitness-Schätzung (VO₂max) mit Bewertung nach Alter |
| **Intervalle** | Training mit Intervallen (Vorlagen oder eigene): der Strap vibriert 2× kurz zum Start, 1× lang zur Pause, 3× am Ende; großes BELASTUNG/PAUSE mit Runde und Restzeit |
| **Puls-Erholung** | Nach einem Training misst HelioLink noch eine Minute weiter und speichert, wie stark der Puls gefallen ist |
| **Training** | Verlauf nach Monaten, erkannte Aktivitäten – alles auf- und zuklappbar. Training starten mit Sportart, sekündlicher Puls, Zone in Farbe, Kalorien, Zeit in Zonen. Automatisch erkannte Spaziergänge und Läufe, aktive Minuten der Woche, Verlauf mit Detailansicht und **TCX**-Export (Strava, Garmin & Co.) |
| **Wecker** | Bis zu 10 Vibrationswecker auf dem Strap mit **Smart Wake** (bis 30 min früher im Leichtschlaf), Wochentage, Name, Countdown, Schlafenszeit-Hinweis, Schnellwecker (Powernap, „jetzt + Schlafziel“). Änderungen ohne Verbindung werden später übertragen |
| **Widget & Kachel** | Puls, Schritte, Schlaf und Akku auf dem Startbildschirm; Schnelleinstellungs-Kachel „Strap finden“ |
| **Einstellungen** | Getrennt nach **Strap** und **App** (siehe unten). „Nach Updates suchen“ sitzt direkt auf der Übersicht |

**Datenschutz:** Gesundheitsdaten verlassen das Handy nie – kein Konto, keine Cloud, keine Analyse. Die
Android-Cloud-Sicherung ist ausgeschlossen, Sicherungen lassen sich mit Passwort verschlüsseln
(PBKDF2 + AES-GCM, `core/BackupCrypto.kt`), die App lässt sich sperren. Die einzige Verbindung ins
Internet ist die Update-Suche bei GitHub; sie lässt sich abschalten.

Im Hintergrund hält ein Dienst die Verbindung, startet nach einem Neustart des Handys von selbst,
verbindet sich automatisch neu und lädt den Verlauf regelmäßig vom Strap. Stürzt die App ab, zeigt
sie beim nächsten Start einen Bericht zum Kopieren.

## Einstellungen

**Strap**

| Kategorie | Inhalt |
|---|---|
| **Gerät & Verbindung** | Status, Akku, Firmware (Update über die Zepp-App: HelioLink pausiert, Zepp öffnet, danach wieder verbinden), Seriennummer, „Strap vibrieren lassen“, Live-Puls, Einstellungen neu laden, neu verbinden, Auth-Key, Strap entfernen |
| **Messungen** | Puls-Messintervall (Intelligent, jede Minute … 30 min, aus), bei Aktivität häufiger messen, Puls an andere Geräte senden, Pulswarnungen hoch/niedrig, Schlaf mit hoher Genauigkeit, Atmungsqualität, Stress + Entspannungs-Erinnerung, SpO₂ ganztägig + Warnung |
| **Bewegung & Ziele** | Bewegungserinnerung mit Zeitraum und Pause, Schrittziel auf dem Strap, Vibration bei erreichtem Ziel, automatische Trainingserkennung mit Empfindlichkeit |
| **Wecker** | führt direkt zum Wecker-Tab |

**App**

| Kategorie | Inhalt |
|---|---|
| **Darstellung** | Hell/Dunkel/System, Akzentfarbe (9 Farben), Material You, Schriftgröße |
| **Startseite** | Kacheln auf „Heute“ ein- und ausblenden |
| **Benachrichtigungen** | Akku-Warnung, Schlafenszeit-Erinnerung (nächster Wecker minus Schlafziel), Morgenbericht, Wochenbericht am Montag, Puls in der Benachrichtigung, Sync-Fehler – ein Tipp öffnet die passende Seite |
| **Puls-Aufzeichnung** | Puls öfter als einmal pro Minute: jede Sekunde, alle 5/10/30 s (Live-Werte, solange verbunden) |
| **Schlaf** | Schlafziel, „Stimmt deine Nacht?“, kurze Wachphasen ignorieren, Wachzeit innerhalb einer Nacht, kürzester Schlaf (Nickerchen), Einschlafen später / Aufwachen früher werten, Phasen immer schätzen, Korrekturen verwalten |
| **Profil** | Alter, Geschlecht, Gewicht, Größe, Schritt- und Schlafziel |
| **Training & Zonen** | Max. Puls (mit Vorschlag, wenn ein Training höher ging), Ruhepuls, Zonen nach % HFmax oder Karvonen, Standard-Sportart, Strap vibriert über/unter einer Pulsgrenze |
| **Synchronisierung** | Automatischer Abruf, Zeitraum beim ersten Abruf, Verlauf neu laden, Schlafphasen-Suche mit Stand und Zurücksetzen |
| **App-Updates** | Automatisch prüfen, Vorabversionen, **Shizuku** (Status, Erlaubnis, Installieren ohne Nachfrage, im Hintergrund installieren), Update-Quelle |
| **Datenschutz** | Was das Handy verlässt (nur die Update-Suche bei GitHub, mit Liste der letzten Verbindungen), App-Sperre mit Fingerabdruck/Gesicht/PIN (sofort oder nach 1–30 min im Hintergrund, leere Vorschau in „Letzte Apps“), Sicherungen mit Passwort verschlüsseln. Die Android-Cloud-Sicherung (Google, Xiaomi) ist für HelioLink ausgeschaltet |
| **Daten & Sicherung** | Automatische Sicherung (täglich, alle 3 Tage oder wöchentlich und vor jedem Update) nach Downloads/HelioLink oder in einen gewählten Ordner, die letzten 3–30 bleiben; Export als Zip mit CSV (speichern oder teilen, Zeitraum wählbar, inkl. `schlaf_naechte.csv` und `schlaf_phasen.csv` mit deinen Korrekturen), Sicherung erstellen/wiederherstellen, Gadgetbridge-Import, Protokoll anzeigen/kopieren |

Strap-Einstellungen werden direkt auf dem Strap gespeichert. Die App prüft danach, ob der Strap sie
übernommen hat, und zeigt sonst „Vom Strap nicht übernommen“ an.

## Einrichtung auf GitHub (einmalig)

### 1. Signatur-Schlüssel einrichten
Damit sich die App später selbst aktualisieren kann, muss jede Version mit demselben Schlüssel
signiert sein. Der Schlüssel ist mit einem Passwort geschützt, das nur als GitHub-Secret existiert.

1. Auf GitHub: **Settings → Secrets and variables → Actions → New repository secret**
   - Name: `SIGNING_PASSWORD`
   - Wert: ein langes, zufälliges Passwort (mind. 12 Zeichen). Gut aufheben, z. B. im Passwort-Manager.
2. **Actions → „Signatur einrichten“ → Run workflow** (Branch `main`).

Der Workflow erzeugt den Schlüssel und legt ihn passwortgeschützt als `signing/release.p12` ins Repo.
Danach baut „Build“ automatisch die erste Version **1.0.0** und veröffentlicht sie unter **Releases**.

> Den Schlüssel nie neu erzeugen oder das Passwort ändern. Sonst lassen sich neue Versionen nicht mehr
> über die installierte App installieren (dann App einmal deinstallieren und neu installieren).

Bis der Schlüssel eingerichtet ist, werden Updates mit dem Test-Schlüssel signiert (so wie 1.0.0),
damit die installierte App sie annimmt. Das erste Update mit dem neuen Schlüssel erkennt die App selbst
und führt durch den einmaligen Wechsel: Sicherung speichern → neue Version speichern → deinstallieren →
installieren → Sicherung wiederherstellen. Es gehen keine Daten verloren.

### 2. Von Gadgetbridge umziehen
In Gadgetbridge **Einstellungen → Daten exportieren** (Zip). In HelioLink bei der Einrichtung
**„Gadgetbridge-Export auswählen“** (oder später *Einstellungen → App → Daten & Sicherung → Aus Gadgetbridge importieren*).
HelioLink übernimmt Auth-Key, MAC-Adresse und den ganzen Verlauf: Puls und Schritte pro Minute,
Schlafphasen, Stress, SpO₂, Ruhepuls, HRV, PAI, Hauttemperatur, Atemfrequenz und Wecker. Danach in Gadgetbridge den Strap trennen.

## Installation

Unter **Releases** liegt die neueste `heliolink-X.Y.Z.apk`. Auf dem Handy herunterladen und öffnen
(„Installation aus unbekannten Quellen“ einmal erlauben).

Ohne Release: Jeder Push baut die App, die APK liegt im Lauf „Build“ unter *Artifacts*.
Selbst bauen: Android SDK installieren, dann `./gradlew :app:assembleRelease`.

## Updates

**Automatisch:** Sobald sich die App auf dem Standard-Branch (`main`) ändert, baut der Workflow
„Build“ nach grünen Tests die nächste Version (1.0.0 → 1.0.1) und veröffentlicht sie als Release.
Die allererste Version wird automatisch 1.0.0. Die App prüft im Hintergrund alle paar Stunden und
meldet sich mit „HelioLink 1.0.1 ist da“. Ein Tipp darauf öffnet das Update, die Installation
bestätigst du einmal. Deine Daten und der Auth-Key bleiben erhalten.

- Doku-Änderungen (`*.md`) lösen kein Update aus.
- Ausschalten: im Repo unter **Settings → Secrets and variables → Actions → Variables** die
  Variable `AUTO_RELEASE` mit dem Wert `false` anlegen.
- Größere Versionssprünge (z. B. `1.1.0`) über **Actions → „Release“ → Run workflow**. Danach
  zählen die automatischen Updates von dort weiter.
- Manuell suchen: in der App **Einstellungen → Nach Updates suchen**.

Alle Releases sind mit dem Schlüssel aus `signing/release.p12` signiert, deshalb lassen sie sich
immer über die installierte App installieren. Das Repository ist öffentlich, die App braucht für
Updates also kein Token.

## Schlafphasen

Der Helio Strap speichert Schlafphasen nicht in den Minutenwerten, sondern als eigenen Datensatz
pro Nacht (Leicht, Tief, REM, Wach und ein Score). HelioLink liest ihn aus dem Gadgetbridge-Export
und sucht nach dem ersten Abruf, unter welchem Code der Strap ihn liefert – auch über mehrere
Verbindungen hinweg (*Einstellungen → App → Synchronisierung*). Solange er nicht gefunden ist,
schätzt die App die Phasen aus Puls, Pulsschwankung und Bewegung. Das Modell wurde an echten Nächten
des Straps trainiert und trifft die Phase des Straps in rund 70 % der Minuten; geschätzte Nächte sind
in der Schlafansicht markiert.

## Updates mit Shizuku

Mit [Shizuku](https://shizuku.rikka.app) installiert HelioLink Updates ohne den Android-Dialog:

1. Shizuku installieren und starten (Wireless-Debugging oder Root).
2. In HelioLink *Einstellungen → App-Updates → Shizuku-Status* antippen und HelioLink erlauben.
3. „Updates über Shizuku installieren“ einschalten; optional „Im Hintergrund installieren“.

Ist Shizuku nicht bereit, nimmt die App wie gewohnt den Android-Installer.

## Einrichtung: der Auth-Key

Der Strap spricht nur mit Apps, die seinen geheimen 16-Byte-Schlüssel kennen. Den bekommt man so:

1. Strap einmal mit der offiziellen **Zepp-App** koppeln (und das Firmware-Update machen).
2. Key aus dem Zepp-Konto auslesen, z. B. mit
   [huami-token](https://codeberg.org/argrento/huami-token) (`python huami_token.py --method amazfit --email … --password … --bt_keys`)
   oder wie im [Gadgetbridge-Wiki](https://gadgetbridge.org/basics/pairing/huami-xiaomi-server/) beschrieben.
3. In der Zepp-App den Strap **nicht** entkoppeln (sonst wird der Key ungültig), aber Bluetooth
   für Zepp deaktivieren bzw. die App beenden – es darf immer nur eine App verbunden sein.
4. In HelioLink den Key (32 Hex-Zeichen) eingeben, nach dem Strap suchen, „Verbinden“.

## Aufbau

```
core/   Plattformunabhängiges Zepp-OS-Protokoll in Kotlin (JVM), mit Unit-Tests
        ├─ Ecdh163       ECDH auf NIST B-163 für den Schlüsselaustausch
        ├─ Chunked       Paketierung, AES-Verschlüsselung, CRC
        ├─ StrapProtocol Anmeldung, Akku, Puls, Schritte, Einstellungen, Verlaufsabruf
        ├─ HealthConfig  Gesundheits-Einstellungen des Straps
        ├─ Alarms        Wecker auf dem Strap
        ├─ SleepRecord   Schlafdatensatz des Straps
        ├─ SleepStager   Schätzung der Schlafphasen (Entscheidungsbaum)
        ├─ Insights      Tageswerte, Erholung, Aktivitätserkennung, Akkuprognose
        └─ Analysis      Schlaf, Pulszonen, Kalorien, HRV, TCX
app/    Android-App (Jetpack Compose)
        ├─ ble/          GATT-Verbindung mit Warteschlange und Wiederverbindung
        ├─ service/      Vordergrunddienst, Sync, Training-Aufzeichnung
        ├─ data/         SQLite-Datenbank, Einstellungen, Sicherung, Import
        ├─ update/       Updates aus GitHub-Releases, Shizuku
        ├─ widget/       Startbildschirm-Widget
        └─ ui/           Heute, Details, Schlaf, Training, Wecker, Einstellungen
```

Die Protokoll-Tests laufen ohne Android-SDK: `./gradlew :core:test`. Sie simulieren einen
kompletten Strap (Schlüsselaustausch, verschlüsselte Nachrichten, Datenabruf) und prüfen die
Kryptografie gegen BouncyCastle.

## Hinweise

- Das Protokoll wurde anhand des freien Gadgetbridge-Projekts (Zepp-OS-Unterstützung) nachvollzogen
  und eigenständig in Kotlin implementiert.
- Kein offizielles Produkt von Zepp Health/Amazfit. Werte sind keine medizinischen Messungen.
- Lizenz: GPL-3.0, siehe [LICENSE](LICENSE).
