# Waschbeken1

Punk-Webseite für die (fiktive) Band **WASCHBECKEN – Punk aus dem Abfluss**.
Optik: Fotokopierer-Zine, Erpresserbrief-Buchstaben, Klebeband, gerissenes Papier, Rasterdruck.

## Ansehen

Kein Build-Schritt nötig: einfach `index.html` im Browser öffnen.

Online stellen geht z. B. mit GitHub Pages: *Settings → Pages → Branch auswählen → Save*.

## Dateien

| Datei        | Inhalt                                                        |
|--------------|---------------------------------------------------------------|
| `index.html` | Inhalt: Manifest, Album, Konzerte, Merch, Kontakt             |
| `style.css`  | Das ganze Design (Farben als Variablen oben in `:root`)       |
| `script.js`  | Erpresserbrief-Schrift, Klick-Aufkleber, Pogo-Modus, Formular |

## Spielereien

- **Titel anklicken** würfelt die Buchstaben neu.
- **Irgendwo in den oberen Bereich klicken** klebt einen Aufkleber hin.
- **Pogo-Modus** lässt alles hüpfen (wird bei „Bewegung reduzieren“ im System ausgeblendet).

## Anpassen

- **Bandname / Texte / Konzerte:** direkt in `index.html`. Für den großen Titel steuert
  `data-text="WASCH|BECKEN"`, wo das Wort auf dem Handy umbricht.
- **Farben:** Variablen `--pink`, `--yellow`, `--red` … oben in `style.css`.
- **Kontakt-Adresse:** den Mail-Link `booking@waschbecken.example` in `index.html` ändern.
  Das Formular braucht keinen Server, es öffnet das Mailprogramm mit dieser Adresse.
