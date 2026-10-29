# HelioLink

Eine schlanke Android-App für das **Amazfit Helio Strap** – ohne Zepp-Cloud, ohne Konto,
alle Daten bleiben auf dem Handy. Inspiriert von [Gadgetbridge](https://gadgetbridge.org),
aber ganz auf einen Strap ohne Display zugeschnitten.

## Was die App kann

| Bereich | Funktionen |
|---|---|
| **Heute** | Verbindungsstatus, Akku, Live-Puls mit Pulszonen und HRV (RMSSD), Schritte mit Tagesziel und Stundenverlauf, Herzfrequenz-Tageskurve mit Ruhepuls, Stress-Verlauf |
| **Schlaf** | Hypnogramm (Wach/REM/Leicht/Tief), Schlaf-Score, Puls im Schlaf, 7-Nächte-Übersicht |
| **Training** | Puls-Training mit sekündlicher Messung, 5 Zonen, Kalorien (Keytel-Formel), Verlauf, Export als **TCX** (z. B. Strava) |
| **Einstellungen** | Nach Kategorien geordnet, jede mit kurzer Beschreibung (siehe unten). „Nach Updates suchen“ sitzt direkt ganz unten |

Im Hintergrund hält ein Dienst die Verbindung, verbindet sich automatisch neu und lädt den
Verlauf (Puls, Schritte, Schlaf, Stress minutengenau) regelmäßig vom Strap.

## Einstellungen

| Kategorie | Inhalt |
|---|---|
| **Strap** | Status, Akku, Firmware, „Strap vibrieren lassen“, neu verbinden, Auth-Key, Strap entfernen |
| **Messungen am Strap** | Puls-Messintervall (Intelligent, jede Minute … 30 min, aus), bei Aktivität häufiger messen, Puls an andere Geräte senden, Pulswarnungen hoch/niedrig, Schlaf mit hoher Genauigkeit, Atmungsqualität, Stress + Entspannungs-Erinnerung, SpO₂ ganztägig + Warnung, sichtbar bleiben wenn verbunden |
| **Puls-Aufzeichnung** | Puls öfter als einmal pro Minute: jede Sekunde, alle 5/10/30 s (Live-Werte, solange verbunden) |
| **Bewegung & Ziele** | Bewegungserinnerung mit Zeitraum und Pause, Schrittziel auf dem Strap, Vibration bei erreichtem Ziel, automatische Trainingserkennung mit Empfindlichkeit |
| **Profil** | Alter, Geschlecht, Gewicht, Größe, Schritt- und Schlafziel |
| **Training & Zonen** | Max. Puls, Ruhepuls, Zonen nach % HFmax oder Karvonen, Standard-Sportart, Strap vibriert über/unter einer Pulsgrenze |
| **Synchronisierung** | Automatischer Abruf, Zeitraum beim ersten Abruf, Schlafphasen-Abruf, Verlauf neu laden |
| **Benachrichtigungen** | Akku-Warnung, Puls in der Benachrichtigung, Sync-Fehler |
| **Darstellung** | Hell/Dunkel/System, Material You |
| **App-Updates** | Automatisch prüfen, Vorabversionen, Update-Quelle |
| **Daten & Sicherung** | Export als Zip mit CSV (speichern oder teilen, Zeitraum wählbar), Sicherung erstellen/wiederherstellen, Gadgetbridge-Import, Protokoll anzeigen/kopieren |

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
**„Gadgetbridge-Export auswählen“** (oder später *Einstellungen → Daten → Aus Gadgetbridge importieren*).
HelioLink übernimmt Auth-Key, MAC-Adresse und den ganzen Verlauf: Puls und Schritte pro Minute,
Schlafphasen, Stress, SpO₂, Ruhepuls und HRV. Danach in Gadgetbridge den Strap trennen.

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
und sucht nach dem ersten Abruf einmalig, unter welchem Code der Strap ihn liefert
(*Einstellungen → Synchronisierung → Schlafphasen-Abruf suchen*). Findet sie ihn nicht, wird der
Schlaf aus den Minutenwerten berechnet – dann ohne Phasen.

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
        └─ Analysis      Schlafphasen, Tageswerte, Pulszonen, Kalorien, HRV, TCX
app/    Android-App (Jetpack Compose)
        ├─ ble/          GATT-Verbindung mit Warteschlange und Wiederverbindung
        ├─ service/      Vordergrunddienst, Sync, Training-Aufzeichnung
        ├─ data/         SQLite-Datenbank, Einstellungen
        └─ ui/           Heute, Schlaf, Training, Strap, Einrichtung
```

Die Protokoll-Tests laufen ohne Android-SDK: `./gradlew :core:test`. Sie simulieren einen
kompletten Strap (Schlüsselaustausch, verschlüsselte Nachrichten, Datenabruf) und prüfen die
Kryptografie gegen BouncyCastle.

## Hinweise

- Das Protokoll wurde anhand des freien Gadgetbridge-Projekts (Zepp-OS-Unterstützung) nachvollzogen
  und eigenständig in Kotlin implementiert.
- Kein offizielles Produkt von Zepp Health/Amazfit. Werte sind keine medizinischen Messungen.
- Lizenz: GPL-3.0, siehe [LICENSE](LICENSE).
