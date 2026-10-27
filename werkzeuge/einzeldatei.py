"""Packt die ganze Webseite in eine einzige HTML-Datei.

Bilder, Schriften und das Lied werden direkt in die Datei eingebettet. Die fertige
Datei läuft per Doppelklick in jedem Browser, auch ohne Internet und ohne GitHub.

Aufruf:
    python3 werkzeuge/einzeldatei.py [ziel.html] [--artifact]

Erwartet die Tonaufnahme unter audio/leipzig-in-truemmern.mp3 (fehlt sie, läuft der
Liedtext ohne Ton mit). Die Schriften werden einmalig von Google Fonts geladen.
Mit --artifact entsteht eine Fassung ohne <html>/<head>/<body> für claude.ai.
"""
import base64
import mimetypes
import re
import sys
import urllib.request
from pathlib import Path

WURZEL = Path(__file__).resolve().parent.parent
CHROME = ('Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 '
          '(KHTML, like Gecko) Chrome/124.0 Safari/537.36')


def daten_url(pfad):
    typ = mimetypes.guess_type(str(pfad))[0] or 'application/octet-stream'
    return f'data:{typ};base64,' + base64.b64encode(pfad.read_bytes()).decode()


def laden(url):
    anfrage = urllib.request.Request(url, headers={'User-Agent': CHROME})
    with urllib.request.urlopen(anfrage, timeout=60) as antwort:
        return antwort.read()


def schriften_einbetten(css_url):
    """Holt das Google-Fonts-CSS und ersetzt die Schriftdateien durch data:-URLs.
    Behalten werden nur die Zeichensätze für Deutsch (latin, latin-ext)."""
    css = laden(css_url).decode()
    bloecke = re.findall(r'/\* ([\w-]+) \*/\s*(@font-face \{.*?\})', css, re.S)
    teile = []
    for teilmenge, block in bloecke:
        if teilmenge not in ('latin', 'latin-ext'):
            continue
        datei = re.search(r'url\((https://[^)]+)\)', block).group(1)
        inhalt = base64.b64encode(laden(datei)).decode()
        teile.append(block.replace(datei, 'data:font/woff2;base64,' + inhalt))
    return '\n'.join(teile)


def main():
    argumente = [a for a in sys.argv[1:] if not a.startswith('--')]
    artifact = '--artifact' in sys.argv
    ziel = Path(argumente[0]) if argumente else WURZEL / 'Akte-Stracke.html'

    html = (WURZEL / 'index.html').read_text(encoding='utf-8')
    css = (WURZEL / 'css/style.css').read_text(encoding='utf-8')
    js = (WURZEL / 'js/main.js').read_text(encoding='utf-8')

    # Bilder im Stylesheet
    css = re.sub(r'url\("\.\./(bilder/[^"]+)"\)',
                 lambda m: f'url("{daten_url(WURZEL / m.group(1))}")', css)

    # Bilder im HTML (src und Links zum Vergrößern); gleiche Bilder nur einmal kodieren
    cache = {}

    def bild(m):
        pfad = m.group(2)
        if pfad not in cache:
            cache[pfad] = daten_url(WURZEL / pfad)
        return f'{m.group(1)}="{cache[pfad]}"'

    html = re.sub(r'(src|href)="(bilder/[^"]+)"', bild, html)

    # Schriften
    schrift_link = re.search(r'<link rel="stylesheet" href="(https://fonts\.googleapis\.com[^"]+)">', html)
    schriften = schriften_einbetten(schrift_link.group(1).replace('&amp;', '&'))
    html = html.replace(schrift_link.group(0), '')
    html = re.sub(r'\s*<link rel="preconnect"[^>]*>', '', html)

    # Lied: als Base64 einbetten und beim Laden in eine Blob-URL umwandeln (zuverlässiges Spulen)
    ton = WURZEL / 'audio/leipzig-in-truemmern.mp3'
    ton_skript = ''
    if ton.exists():
        html = html.replace('<audio preload="metadata" src="audio/leipzig-in-truemmern.mp3"></audio>',
                            '<audio preload="metadata"></audio>')
        ton_skript = (
            '<script type="text/plain" id="ton-daten">'
            + base64.b64encode(ton.read_bytes()).decode()
            + '</script>\n<script>\n(function () {\n'
            '  try {\n'
            '    var b = atob(document.getElementById("ton-daten").textContent);\n'
            '    var bytes = new Uint8Array(b.length);\n'
            '    for (var i = 0; i < b.length; i++) bytes[i] = b.charCodeAt(i);\n'
            '    var url = URL.createObjectURL(new Blob([bytes], { type: "audio/mpeg" }));\n'
            '    document.querySelector(".song audio").src = url;\n'
            '  } catch (e) { /* ohne Ton läuft der Text trotzdem mit */ }\n'
            '})();\n</script>\n')
    else:
        print('Hinweis: audio/leipzig-in-truemmern.mp3 fehlt, die Datei wird ohne Ton gebaut.')

    # CSS und JS direkt in die Seite
    html = re.sub(r'\s*<link rel="stylesheet" href="css/style.css">',
                  '\n  <style>\n' + schriften + '\n' + css + '\n  </style>', html)
    html = re.sub(r'\s*<script src="js/main.js" defer></script>', '', html)
    html = html.replace('</body>', ton_skript + '<script>\n' + js + '\n</script>\n</body>')

    if artifact:
        kopf = re.search(r'<head>(.*?)</head>', html, re.S).group(1)
        kopf = re.sub(r'\s*<meta charset[^>]*>|\s*<meta name="viewport"[^>]*>', '', kopf)
        rumpf = re.search(r'<body>(.*)</body>', html, re.S).group(1)
        html = (kopf.strip() + '\n<meta name="claude:viewport-fit-sides" content="cover">\n'
                + rumpf.strip() + '\n')

    ziel.write_text(html, encoding='utf-8')
    print(f'{ziel}: {ziel.stat().st_size / 1e6:.1f} MB')


if __name__ == '__main__':
    main()
