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
| **Messungen** | Puls rund um die Uhr (Intervall), Puls an andere Geräte senden (Laufband, Radcomputer, Zwift), Schlaf mit hoher Genauigkeit, Stress, SpO₂, Puls- und SpO₂-Warnungen – wird direkt auf dem Strap gespeichert |
| **Profil & Zonen** | Alter, Geschlecht, Gewicht, Größe, max. Puls, Schrittziel, Übersicht deiner 5 Pulszonen |
| **Synchronisierung** | Automatischer Abruf (Intervall), Zeitraum beim ersten Abruf, jetzt synchronisieren, Verlauf neu laden |
| **Benachrichtigungen** | Akku-Warnung, Sync-Fehler melden, Android-Benachrichtigungseinstellungen |
| **Darstellung** | Hell/Dunkel/System, Material You |
| **App-Updates** | Automatisch prüfen, Vorabversionen, Update-Quelle |
| **Daten** | CSV-Export, Verbindungsprotokoll, alle Daten löschen |

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

Alle Versionen sind mit demselben Schlüssel (`app/debug.keystore`) signiert, deshalb lassen sie sich
immer über die installierte App installieren. Das Repository ist öffentlich, die App braucht für
Updates also kein Token.

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
