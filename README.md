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
| **Strap** | Messintervall des Pulses, Puls-Broadcast an Laufband/Radcomputer/Zwift, Schlaf mit hoher Genauigkeit, Stress, SpO₂, Pulswarnungen, „Strap vibrieren“, Profil, CSV-Export |

Im Hintergrund hält ein Dienst die Verbindung, verbindet sich automatisch neu und lädt den
Verlauf (Puls, Schritte, Schlaf, Stress minutengenau) regelmäßig vom Strap.

## Installation

Jeder Push baut die App per GitHub Actions. Die fertige APK liegt unter
**Actions → letzter Lauf „Build“ → Artifacts → HelioLink-apk**. `app-release.apk` installieren
(„Installation aus unbekannten Quellen“ erlauben).

Selbst bauen: Android SDK installieren, dann `./gradlew :app:assembleRelease`.

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
