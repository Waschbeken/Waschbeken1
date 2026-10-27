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

**Am einfachsten:** die Datei `Akte-Stracke.html` per Doppelklick öffnen. Sie enthält alles
(Texte, Bilder, Schriften und das Lied) und läuft in jedem Browser, auch ohne Internet.
Für die Ausstellung im Vollbild zeigen (F11).

Die Einzeldatei liegt nicht im Repo, weil sie die urheberrechtlich geschützte Tonaufnahme enthält.
Neu bauen: Aufnahme als `audio/leipzig-in-truemmern.mp3` ablegen und `python3 werkzeuge/einzeldatei.py` ausführen.

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
- **Das Lied:** L’Attentat, „Leipzig in Trümmern (Original Version)“. Die Zeitmarken für die Zeilen
  stehen schon in `index.html` (`data-zeiten`), per Computer-Analyse aus der Aufnahme ermittelt.
- **Zeitmarken nachjustieren:** `index.html#zeitmarken` im Browser öffnen, das Lied abspielen
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
| `audio/`      | Hierhin gehört die Aufnahme des Liedes (nicht im Repo, siehe `audio/LIESMICH.txt`) |
| `werkzeuge/einzeldatei.py` | Baut `Akte-Stracke.html` mit allem eingebettet |

## Hinweis zu Bildern und Quellen

Alle Abbildungen stammen aus den im Quellenverzeichnis genannten Booklets und Unterrichtsmaterialien und
werden ausschließlich für schulische Zwecke gezeigt. Die Rechte liegen bei den jeweiligen Urhebern.
