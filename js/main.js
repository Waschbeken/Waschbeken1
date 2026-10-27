/* Akte Stracke – kleine Helfer für die Ausstellungsseite. */
(function () {
  'use strict';

  /* ---------- Navigation: aktives Kapitel und Lesefortschritt ---------- */

  var links = document.querySelectorAll('.nav a');
  var balken = document.querySelector('.fortschritt');
  var abschnitte = [];

  links.forEach(function (link) {
    var ziel = document.getElementById(link.getAttribute('href').slice(1));
    if (ziel) abschnitte.push({ link: link, ziel: ziel });
  });

  function markieren() {
    var mitte = window.innerHeight * 0.35;
    var aktiv = null;
    abschnitte.forEach(function (a) {
      if (a.ziel.getBoundingClientRect().top <= mitte) aktiv = a;
    });
    links.forEach(function (l) { l.classList.remove('aktiv'); });
    if (aktiv) {
      aktiv.link.classList.add('aktiv');
      // Aktiven Link in der schmalen Navigation sichtbar halten
      var nav = aktiv.link.parentElement;
      var links_ = aktiv.link.offsetLeft;
      if (links_ < nav.scrollLeft || links_ > nav.scrollLeft + nav.clientWidth - 80) {
        nav.scrollLeft = links_ - 20;
      }
    }
    if (balken) {
      var max = document.documentElement.scrollHeight - window.innerHeight;
      balken.style.width = (max > 0 ? (window.scrollY / max) * 100 : 0) + '%';
    }
  }

  var wartet = false;
  window.addEventListener('scroll', function () {
    if (wartet) return;
    wartet = true;
    window.requestAnimationFrame(function () {
      markieren();
      wartet = false;
    });
  }, { passive: true });
  markieren();

  /* ---------- Umschalter-Gruppen (gegenseitig ausschließend) ---------- */

  function gruppe(knoepfe, beiWahl) {
    knoepfe.forEach(function (knopf) {
      knopf.addEventListener('click', function () {
        knoepfe.forEach(function (k) { k.setAttribute('aria-pressed', String(k === knopf)); });
        beiWahl(knopf);
      });
    });
  }

  /* Personen: Szene / Akte */
  var personen = document.querySelector('.personen');
  if (personen) {
    gruppe(document.querySelectorAll('[data-ansicht]:not(.personen)'), function (knopf) {
      var ansicht = knopf.getAttribute('data-ansicht');
      personen.setAttribute('data-ansicht', ansicht);
      personen.querySelectorAll('.person').forEach(function (p) {
        p.querySelector('.szene').hidden = ansicht !== 'szene';
        p.querySelector('.akt').hidden = ansicht !== 'akte';
        var rolle = p.querySelector('.rolle');
        rolle.textContent = rolle.getAttribute('data-' + ansicht);
      });
    });
  }

  /* Zeitleiste filtern */
  var zeitleiste = document.querySelector('.zeitleiste');
  if (zeitleiste) {
    gruppe(document.querySelectorAll('button[data-filter]'), function (knopf) {
      var filter = knopf.getAttribute('data-filter');
      zeitleiste.setAttribute('data-filter', filter);
      zeitleiste.querySelectorAll('.ereignis').forEach(function (e) {
        var hatS = !!e.querySelector('.s');
        var hatSt = !!e.querySelector('.st');
        e.hidden = (filter === 's' && !hatS) || (filter === 'st' && !hatSt);
      });
    });
  }

  /* Aktensprache / Klartext */
  var uebersetzer = document.querySelector('.uebersetzer');
  if (uebersetzer) {
    var fach = uebersetzer.querySelectorAll('.fach');
    fach.forEach(function (f) { f.setAttribute('data-akte', f.textContent); });
    gruppe(uebersetzer.querySelectorAll('[data-modus]:not(.uebersetzer)'), function (knopf) {
      var modus = knopf.getAttribute('data-modus');
      uebersetzer.setAttribute('data-modus', modus);
      fach.forEach(function (f) {
        f.textContent = f.getAttribute(modus === 'klartext' ? 'data-klartext' : 'data-akte');
      });
    });
  }

  /* ---------- Abwägung: War das Widerstand? ---------- */

  var waage = document.querySelector('.waage');
  if (waage) {
    var kaestchen = waage.querySelectorAll('input[type="checkbox"]');
    var nadel = waage.querySelector('.anzeige-nadel');
    var text = waage.querySelector('.anzeige-text');

    var stufen = [
      { bis: -2, titel: 'Nonkonformität', satz: 'Für dich überwiegt: Er wollte anders leben, ein politischer Gegner des Staates war er nicht.' },
      { bis: -1, titel: 'Verweigerung und Protest', satz: 'Für dich war es Widerspruch, aber kein Widerstand im engeren Sinn.' },
      { bis: 0, titel: 'Unentschieden', satz: 'Beide Seiten wiegen gleich schwer. Genau das macht den Fall so spannend.' },
      { bis: 2, titel: 'Protest mit politischem Ziel', satz: 'Für dich hat er sich bewusst gegen den Staat gestellt, an der Grenze zur Opposition.' },
      { bis: 3, titel: 'Widerstand', satz: 'Für dich überwiegt: Er hat sich der SED-Diktatur bewusst entgegengestellt.' }
    ];

    function auswerten() {
      var summe = 0;
      var anzahl = 0;
      kaestchen.forEach(function (k) {
        if (k.checked) {
          summe += Number(k.value);
          anzahl += 1;
        }
      });
      nadel.style.left = (50 + summe * 15) + '%';
      if (anzahl === 0) {
        text.textContent = 'Wähle Argumente aus, die dich überzeugen.';
        return;
      }
      var stufe = stufen.filter(function (s) { return summe <= s.bis; })[0];
      text.innerHTML = '';
      var b = document.createElement('b');
      b.textContent = stufe.titel + ': ';
      text.appendChild(b);
      text.appendChild(document.createTextNode(stufe.satz));
    }

    kaestchen.forEach(function (k) { k.addEventListener('change', auswerten); });
    auswerten();
  }

  /* ---------- Bildbetrachter ---------- */

  var dialog = document.querySelector('.lupe-dialog');
  if (dialog && typeof dialog.showModal === 'function') {
    var gross = dialog.querySelector('img');
    var titel = dialog.querySelector('#lupe-titel');

    document.querySelectorAll('a[data-lupe]').forEach(function (link) {
      link.addEventListener('click', function (event) {
        event.preventDefault();
        var vorschau = link.querySelector('img');
        var unterschrift = link.closest('figure').querySelector('figcaption');
        gross.src = link.getAttribute('href');
        gross.alt = vorschau ? vorschau.alt : '';
        titel.textContent = unterschrift ? unterschrift.textContent : '';
        dialog.showModal();
      });
    });

    dialog.querySelector('[data-schliessen]').addEventListener('click', function () {
      dialog.close();
    });

    // Klick auf den abgedunkelten Hintergrund schließt ebenfalls
    dialog.addEventListener('click', function (event) {
      if (event.target === dialog) dialog.close();
    });
  }

  /* ---------- Lied: Kassette mit mitlaufendem Text und Deutung ---------- */
  // Die Tonaufnahme gehört nach audio/leipzig-in-truemmern.mp3. Fehlt sie, läuft der Text ohne Ton mit.
  // Zeitmarken stehen in data-zeiten am .song (Sekunden, eine pro Zeile, mit Komma getrennt).
  // Ohne Zeitmarken werden die Zeilen nach ihrer Länge über das Lied verteilt.
  // Mit #zeitmarken am Ende der Adresse erscheint ein Werkzeug, um die Marken beim Hören zu setzen.

  var song = document.querySelector('.song');
  if (song) {
    var audio = song.querySelector('audio');
    var knopf = song.querySelector('.abspielen');
    var symbol = knopf.querySelector('.symbol');
    var beschriftung = knopf.querySelector('.beschriftung');
    var spur = song.querySelector('.spur');
    var anzeigeJetzt = song.querySelector('.jetzt');
    var anzeigeDauer = song.querySelector('.dauer');
    var tonHinweis = song.querySelector('.ton-hinweis');
    var kasten = song.querySelector('.kasten');
    var fenster = song.querySelector('.kasten-zeilen');
    var zaehler = song.querySelector('.zaehler');
    var deutung = song.querySelector('.deutung');
    var deutungText = song.querySelector('.deutung-text');
    var startText = deutungText.innerHTML;
    var zeilen = Array.prototype.slice.call(song.querySelectorAll('.lz'));
    var leer = song.querySelector('.kasten-leer');
    var textzeilen = zeilen.filter(function (z) { return !z.classList.contains('musik'); }).length;
    var deutungen = {};
    song.querySelectorAll('.deutungen li').forEach(function (li) {
      deutungen[li.getAttribute('data-g')] = li.innerHTML;
    });

    var SEKUNDEN_PRO_ZEILE = 3.6; // Tempo, wenn der Text ohne Ton mitläuft
    var ruhig = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    var ohneTon = false;
    var dauer = 0;
    var zeiten = [];
    var laeuft = false;
    var virtuellPos = 0;
    var virtuellStart = 0;
    var aktuell = -2;
    var aktuelleGruppe = null;

    song.classList.add('bereit');

    var format = function (s) {
      if (!isFinite(s)) return '–:––';
      s = Math.max(0, Math.floor(s));
      return Math.floor(s / 60) + ':' + ('0' + (s % 60)).slice(-2);
    };

    var zeitenBerechnen = function () {
      var fest = (song.getAttribute('data-zeiten') || '').split(',')
        .map(parseFloat)
        .filter(function (n) { return !isNaN(n); });
      if (!ohneTon && fest.length === zeilen.length) {
        zeiten = fest;
        return;
      }
      var vor = ohneTon ? 0.5 : parseFloat(song.getAttribute('data-vorspiel')) || 0;
      var nach = ohneTon ? 2 : parseFloat(song.getAttribute('data-nachspiel')) || 0;
      var gewichte = zeilen.map(function (z) { return z.textContent.length + 15; });
      var summe = gewichte.reduce(function (a, b) { return a + b; }, 0);
      var platz = Math.max(1, dauer - vor - nach);
      var t = vor;
      zeiten = gewichte.map(function (g) {
        var anfang = t;
        t += platz * g / summe;
        return anfang;
      });
    };

    var position = function () {
      if (!ohneTon) return audio.currentTime || 0;
      return laeuft ? virtuellPos + (performance.now() - virtuellStart) / 1000 : virtuellPos;
    };

    var zeigen = function () {
      var t = position();
      var index = -1;
      for (var k = 0; k < zeiten.length; k++) {
        if (zeiten[k] <= t) index = k;
      }
      if (index !== aktuell) {
        zeilen.forEach(function (z, k) {
          z.classList.toggle('kommt', k > index);
          z.classList.toggle('war', k < index);
          z.classList.toggle('aktuell', k === index);
        });
        kasten.classList.toggle('leer', index < 0);
        var gesungen = zeilen.slice(0, index + 1).filter(function (z) { return !z.classList.contains('musik'); }).length;
        if (index < 0) zaehler.textContent = '';
        else if (zeilen[index].classList.contains('musik')) zaehler.textContent = '♪ Musik';
        else zaehler.textContent = 'Zeile ' + gesungen + ' von ' + textzeilen;
        if (index < 0) {
          deutungText.innerHTML = startText;
          aktuelleGruppe = null;
        } else {
          var zeile = zeilen[index];
          // Nur im Kasten scrollen, nicht die ganze Seite
          fenster.scrollTo({ top: Math.max(0, zeile.offsetTop - fenster.clientHeight * 0.45), behavior: ruhig ? 'auto' : 'smooth' });
          var gruppe = zeile.getAttribute('data-g');
          if (gruppe !== aktuelleGruppe) {
            aktuelleGruppe = gruppe;
            deutungText.innerHTML = deutungen[gruppe] || '';
            deutung.classList.remove('wechsel');
            void deutung.offsetWidth; // Animation neu starten
            deutung.classList.add('wechsel');
          }
        }
        aktuell = index;
      }
      // Vor der ersten Zeile: zeigen, dass das Lied schon läuft
      if (leer) leer.textContent = leer.getAttribute(index < 0 && t > 0.3 && !ohneTon ? 'data-intro' : 'data-start') || leer.textContent;
      anzeigeJetzt.textContent = format(t);
      if (dauer > 0) spur.value = String(Math.round(Math.min(t / dauer, 1) * 1000));
    };

    var knopfZustand = function (an) {
      laeuft = an;
      song.classList.toggle('spielt', an);
      knopf.setAttribute('aria-pressed', String(an));
      symbol.textContent = an ? '❚❚' : '▶';
      beschriftung.textContent = an ? 'Pause' : (ohneTon ? 'Text abspielen' : 'Abspielen');
    };

    var schleife = function () {
      zeigen();
      if (ohneTon && laeuft && position() >= dauer) {
        virtuellPos = dauer;
        knopfZustand(false);
        zeigen();
        return;
      }
      if (laeuft) window.requestAnimationFrame(schleife);
    };

    var setzen = function (t) {
      t = Math.min(Math.max(0, t), dauer || 0);
      if (ohneTon) {
        virtuellPos = t;
        virtuellStart = performance.now();
      } else if (dauer > 0) {
        audio.currentTime = t;
      }
      zeigen();
    };

    var aufOhneTonUmstellen = function () {
      if (ohneTon) return;
      ohneTon = true;
      try { audio.pause(); } catch (e) { /* nichts zu tun */ }
      dauer = zeilen.length * SEKUNDEN_PRO_ZEILE + 2.5;
      zeitenBerechnen();
      anzeigeDauer.textContent = format(dauer);
      tonHinweis.hidden = false;
      knopfZustand(false);
      zeigen();
    };

    var abspielen = function () {
      if (dauer > 0 && position() >= dauer - 0.05) setzen(0);
      if (ohneTon) {
        virtuellStart = performance.now();
        knopfZustand(true);
        schleife();
        return;
      }
      var versuch = audio.play();
      if (versuch && versuch.catch) {
        versuch.catch(function (fehler) {
          if (audio.error || (fehler && fehler.name === 'NotSupportedError')) {
            aufOhneTonUmstellen();
            abspielen();
          }
        });
      }
    };

    var anhalten = function () {
      if (ohneTon) {
        virtuellPos = position();
        knopfZustand(false);
      } else {
        audio.pause();
      }
    };

    audio.addEventListener('loadedmetadata', function () {
      if (ohneTon) return;
      dauer = audio.duration;
      zeitenBerechnen();
      anzeigeDauer.textContent = format(dauer);
      zeigen();
    });
    audio.addEventListener('error', aufOhneTonUmstellen);
    audio.addEventListener('play', function () {
      if (ohneTon) return;
      knopfZustand(true);
      schleife();
    });
    audio.addEventListener('pause', function () {
      if (ohneTon) return;
      knopfZustand(false);
      zeigen();
    });

    // Der Ladefehler kann schon passiert sein, bevor dieses Skript läuft
    if (audio.error || audio.networkState === 3) aufOhneTonUmstellen();
    else if (audio.readyState >= 1) audio.dispatchEvent(new Event('loadedmetadata'));

    knopf.addEventListener('click', function () {
      if (laeuft) anhalten();
      else abspielen();
    });

    spur.addEventListener('input', function () {
      setzen(Number(spur.value) / 1000 * dauer);
    });

    zeilen.forEach(function (z, k) {
      z.querySelector('button').addEventListener('click', function () {
        setzen(zeiten[k] + 0.01);
      });
    });

    song.querySelector('.alles').addEventListener('click', function () {
      anhalten();
      setzen(dauer);
    });

    knopfZustand(false);
    zeigen();

    if (window.location.hash === '#zeitmarken') {
      var werkzeug = document.createElement('div');
      werkzeug.className = 'zeitmarken';
      werkzeug.innerHTML =
        '<p><b>Zeitmarken setzen:</b> Spiel das Lied ab und drück bei jeder neuen Zeile auf den Knopf oder die Taste T. ' +
        'Danach den Text unten in index.html bei <code>data-zeiten=""</code> einfügen.</p>' +
        '<button type="button" data-marke>Neue Zeile beginnt (T)</button>' +
        '<button type="button" data-neu>Von vorn</button>' +
        '<p class="zm-stand"></p><textarea readonly aria-label="Zeitmarken"></textarea>';
      song.querySelector('.spieler').appendChild(werkzeug);
      var marken = [];
      var stand = werkzeug.querySelector('.zm-stand');
      var ausgabe = werkzeug.querySelector('textarea');
      var standZeigen = function () {
        stand.textContent = marken.length + ' von ' + zeilen.length + ' Zeilen';
        ausgabe.value = marken.join(',');
      };
      var marke = function () {
        if (marken.length < zeilen.length) marken.push(position().toFixed(1));
        standZeigen();
      };
      werkzeug.querySelector('[data-marke]').addEventListener('click', marke);
      werkzeug.querySelector('[data-neu]').addEventListener('click', function () {
        marken = [];
        standZeigen();
        setzen(0);
      });
      document.addEventListener('keydown', function (event) {
        if ((event.key === 't' || event.key === 'T') && !event.target.closest('input, textarea')) {
          event.preventDefault();
          marke();
        }
      });
      standZeigen();
    }
  }
})();
