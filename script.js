/* WASCHBECKEN – ein bisschen Krach per JavaScript. */
(() => {
  'use strict';

  const rand = (min, max) => Math.random() * (max - min) + min;

  // Zufälliger Index, aber nie zweimal hintereinander derselbe.
  const pickIndex = (list, previous) => {
    let i;
    do {
      i = Math.floor(Math.random() * list.length);
    } while (list.length > 1 && i === previous);
    return i;
  };

  /* ---------- Erpresserbrief-Schrift ---------- */

  const FONTS = [
    '"Anton", Impact, sans-serif',
    '"Black Ops One", Impact, sans-serif',
    '"Permanent Marker", cursive',
    '"Special Elite", "Courier New", monospace',
    '"Bungee", Impact, sans-serif',
    '"Abril Fatface", Georgia, serif',
    '"Rubik Dirt", Impact, sans-serif',
    'Georgia, "Times New Roman", serif',
  ];

  // [Hintergrund, Schriftfarbe]
  const SWATCHES = [
    ['#f2eee3', '#111'],
    ['#ff2e88', '#111'],
    ['#ffe600', '#111'],
    ['#2a2a2a', '#f2eee3'],
    ['#fffdf7', '#e10600'],
    ['#e10600', '#fffdf7'],
    ['#a8a69e', '#111'],
  ];

  function cutOut(el) {
    // "|" in data-text markiert, wo das Wort auf dem Handy umbrechen darf.
    const text = el.dataset.text || el.textContent.trim();
    el.dataset.text = text;
    el.textContent = '';

    // Screenreader lesen das Wort am Stück, nicht Buchstabe für Buchstabe.
    const label = document.createElement('span');
    label.className = 'sr-only';
    label.textContent = text.replaceAll('|', '');
    el.append(label);

    let font = -1;
    let swatch = -1;
    const cut = () => rand(0, 7).toFixed(1);

    for (const ch of text) {
      const span = document.createElement('span');
      span.setAttribute('aria-hidden', 'true');

      if (ch === ' ' || ch === '|') {
        span.className = ch === ' ' ? 'ransom__space' : 'ransom__break';
        el.append(span);
        continue;
      }

      font = pickIndex(FONTS, font);
      swatch = pickIndex(SWATCHES, swatch);
      const [bg, fg] = SWATCHES[swatch];

      span.className = 'ransom__l';
      span.textContent = ch;
      span.style.fontFamily = FONTS[font];
      span.style.backgroundColor = bg;
      span.style.color = fg;
      span.style.fontSize = `${rand(0.82, 1.12).toFixed(2)}em`;
      span.style.setProperty('--r', `${rand(-9, 9).toFixed(1)}deg`);
      span.style.setProperty('--y', `${rand(-0.08, 0.08).toFixed(2)}em`);
      // Schief ausgeschnitten
      span.style.clipPath =
        `polygon(${cut()}% ${cut()}%, ${100 - cut()}% ${cut()}%, ` +
        `${100 - cut()}% ${100 - cut()}%, ${cut()}% ${100 - cut()}%)`;

      el.append(span);
    }
  }

  document.querySelectorAll('[data-ransom]').forEach((el) => {
    cutOut(el);
    // Klick würfelt die Buchstaben neu.
    el.addEventListener('click', () => cutOut(el));
  });

  /* ---------- Aufkleber im Hero ---------- */

  const hero = document.querySelector('.hero');
  const STICKERS = ['NO!', 'DIY', 'OI!', 'PUNK', 'ZACK!', 'LAUT', 'ANTI', 'RUMMS', 'NEIN', 'KRACH', '★', 'Ⓐ'];
  const STICKER_COLORS = [
    ['#ffe600', '#111'],
    ['#ff2e88', '#111'],
    ['#f2eee3', '#111'],
    ['#e10600', '#fffdf7'],
    ['#fffdf7', '#e10600'],
  ];
  const MAX_STICKERS = 24;
  let lastSticker = -1;

  if (hero) {
    hero.addEventListener('click', (event) => {
      if (event.target.closest('a, button')) return;

      const box = hero.getBoundingClientRect();
      const sticker = document.createElement('span');
      const [bg, fg] = STICKER_COLORS[Math.floor(Math.random() * STICKER_COLORS.length)];

      lastSticker = pickIndex(STICKERS, lastSticker);
      sticker.className = 'sticker';
      sticker.setAttribute('aria-hidden', 'true');
      sticker.textContent = STICKERS[lastSticker];
      sticker.style.left = `${event.clientX - box.left}px`;
      sticker.style.top = `${event.clientY - box.top}px`;
      sticker.style.backgroundColor = bg;
      sticker.style.color = fg;
      sticker.style.setProperty('--r', `${Math.round(rand(-25, 25))}deg`);
      hero.append(sticker);

      const stickers = hero.querySelectorAll('.sticker');
      if (stickers.length > MAX_STICKERS) stickers[0].remove();
    });
  }

  /* ---------- Pogo-Modus ---------- */

  const pogo = document.getElementById('pogo');

  if (pogo) {
    pogo.addEventListener('click', () => {
      const on = document.body.classList.toggle('pogo');
      pogo.setAttribute('aria-pressed', String(on));
    });
  }

  /* ---------- Kontaktformular ---------- */
  // Kein Server nötig: das Formular baut eine E-Mail im Mailprogramm zusammen.
  // Die Adresse kommt aus dem Mail-Link im Kontaktbereich (index.html).

  const form = document.getElementById('contact-form');
  const status = document.getElementById('form-status');
  const mailLink = document.getElementById('contact-mail');

  if (form && status && mailLink) {
    form.addEventListener('submit', (event) => {
      event.preventDefault();

      const data = new FormData(form);
      const name = String(data.get('name')).trim();
      const email = String(data.get('email')).trim();
      const message = String(data.get('message')).trim();
      const address = mailLink.href.replace(/^mailto:/, '');

      const subject = `Nachricht von ${name}`;
      const body = `${message}\n\n– ${name} (${email})`;

      window.location.href =
        `mailto:${address}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;

      status.textContent = `Dein Mailprogramm sollte jetzt aufgehen. Falls nicht: schreib direkt an ${address}.`;
    });
  }
})();
