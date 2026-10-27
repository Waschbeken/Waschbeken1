# Akte Stracke

Webseite für ein Schulprojekt in Geschichte/GK über **Bernd Stracke**, Sänger der Leipziger Punkbands
Wutanfall, H.A.U. und L’Attentat. Er wurde 1983–1986 von der Stasi überwacht, zweimal verhaftet und 1986
in den Westen freigekauft.

Die Seite folgt den fünf Leitfragen des Projekts:

1. Akteure und biografischer Kontext
2. Gesellschaftliche Umstände des Widerstands
3. Wogegen haben sich die Personen gewendet?
4. Ziele, Motive und Werte
5. Handlungsspielräume und Reaktionen

Danach folgen eine Quellenkritik („Ein Abend, drei Daten“), ein Urteil („War das Widerstand?“),
Fragen für das Zeitzeugengespräch und das Quellenverzeichnis.

Die Gestaltung orientiert sich an den Booklets von Wutanfall und L’Attentat: schwarze Seiten,
rote Farbspritzer, Schreibmaschinenschrift und ausgeschnittene Buchstaben.

## Ansehen

Kein Build-Schritt nötig: `index.html` im Browser öffnen. Für die Ausstellung am besten im Vollbild (F11).

Online stellen mit GitHub Pages: *Settings → Pages → Branch auswählen → Save*.

## Interaktive Teile

- **Szene / Akte:** zeigt dieselben Personen einmal als Freunde, einmal so, wie die Stasi sie führte.
- **Lied „Leipzig in Trümmern“:** Kassette mit Abspielknopf. Daneben erscheint der Text Zeile für Zeile,
  darunter unsere Deutung zur jeweiligen Stelle. Ein Klick auf eine Zeile springt dorthin.
- **Zeitleiste:** Stracke & Freunde und Staat & Stasi nebeneinander, filterbar.
- **Aktensprache / Klartext:** übersetzt den Übersichtsbogen der OPK „Stern“.
- **Abwägung:** Besucherinnen und Besucher wählen Argumente und sehen, wohin ihr Urteil zeigt.
- **Bilder:** Dokumente lassen sich per Klick vergrößern.

## Vor der Ausstellung anpassen

- Im Fußbereich von `index.html` den Platzhalter `[Namen, Klasse]` ersetzen.
- **Das Lied einfügen:** die Aufnahme als `audio/leipzig-in-truemmern.mp3` speichern.
  Solange sie fehlt, läuft der Text ohne Ton mit.
- **Text genau auf den Gesang legen:** `index.html#zeitmarken` im Browser öffnen, das Lied abspielen
  und bei jeder neuen Zeile `T` drücken. Die Zahlen aus dem Kasten in `index.html` bei
  `data-zeiten=""` einfügen. Ohne Zeitmarken verteilt die Seite die Zeilen gleichmäßig über das Lied.
  Mit `data-vorspiel` und `data-nachspiel` (Sekunden) lässt sich einstellen, wie lange vor dem
  Gesang und nach der letzten Zeile nur Musik läuft.

## Dateien

| Pfad          | Inhalt                                                |
|---------------|-------------------------------------------------------|
| `index.html`  | Texte aller Kapitel, Quellen- und Bildnachweise        |
| `css/style.css` | Gestaltung (Farben und Schriften oben in `:root`)   |
| `js/main.js`  | Umschalter, Zeitleisten-Filter, Abwägung, Bildbetrachter |
| `bilder/`     | Ausschnitte aus Booklets und Unterrichtsmaterial      |
| `bilder/deko/` | Selbst erzeugte rote Farbspritzer                    |
| `audio/`      | Hierhin gehört die Aufnahme des Liedes (siehe `audio/LIESMICH.txt`) |

## Hinweis zu Bildern und Quellen

Alle Abbildungen stammen aus den im Quellenverzeichnis genannten Booklets und Unterrichtsmaterialien und
werden ausschließlich für schulische Zwecke gezeigt. Die Rechte liegen bei den jeweiligen Urhebern.
