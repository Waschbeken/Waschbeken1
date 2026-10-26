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
})();
