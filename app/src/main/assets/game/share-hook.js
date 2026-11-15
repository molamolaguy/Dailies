/*
 * Dailies — share/copy capture, injected into game pages at document start (GameActivity).
 *
 * Observes the ways a game puts its result on the clipboard or share sheet and forwards the text to
 * the app over the origin-scoped `DailesShare` channel. Every wrapper calls the original API and
 * returns its result unchanged, so the game behaves exactly as before; failures here are swallowed.
 *
 * With Settings → Find Results Automatically on, it also finds a finished game's result without the
 * player pressing Share: when the page shows an end screen ("Next puzzle in 05:12:44", "You solved
 * it") with a visible Share / Copy button, that button is pressed once in probe mode, where the text
 * is captured but nothing is copied, shared or opened.
 *
 * Everything lives in this one closure: the page can't switch probe mode on or read the per-player
 * key (filled in by the app below) that marks the messages as Dailies' own.
 */
(function () {
  'use strict';
  if (window.__dailesShareHook) return;
  try { Object.defineProperty(window, '__dailesShareHook', { value: true }); } catch (e) { window.__dailesShareHook = true; }
  var channel = window.DailesShare;
  if (!channel || typeof channel.postMessage !== 'function') return;
  var NONCE = '__DAILIES_NONCE__';
  var FIND_RESULTS = '__DAILIES_FIND__' === 'true';
  // Taken now, before the page's own scripts run, so they can't be swapped out to read the key.
  var post = Function.prototype.call.bind(channel.postMessage, channel);
  var stringify = JSON.stringify;
  var quote = function (s) { return stringify(String(s)); };

  var last = '';
  var sentAny = false;
  // While Dailies quietly presses the game's own Share / Copy button to read a finished result,
  // the text is captured but nothing is copied, shared or opened.
  var probing = false;
  var probeTimer = null;
  function setProbing(on) {
    probing = Boolean(on);
    if (probeTimer) { clearTimeout(probeTimer); probeTimer = null; }
    // Games build the text right away or after a short animation; probe mode ends after that.
    if (probing) probeTimer = setTimeout(function () { probing = false; probeTimer = null; }, 1500);
  }

  // Wrapped functions report themselves as native code, so pages that check doesn't see a difference.
  var nativeToString = Function.prototype.toString;
  var masked = typeof WeakMap === 'function' ? new WeakMap() : null;
  function mask(wrapper, original) {
    try { if (masked) masked.set(wrapper, original); } catch (e) {}
    return wrapper;
  }
  try {
    if (masked) {
      var patchedToString = function toString() {
        var o = masked.get(this);
        return nativeToString.call(o || this);
      };
      masked.set(patchedToString, nativeToString);
      Function.prototype.toString = patchedToString;
    }
  } catch (e) {}
  function send(via, text) {
    try {
      var t = text == null ? '' : String(text);
      if (!t.trim() || t === last) return;
      last = t;
      sentAny = true;
      post('{"via":' + quote(probing ? 'probe' : via) + ',"n":' + quote(NONCE) + ',"text":' + quote(t.slice(0, 8000)) + '}');
      // Got it: anything the game does after this is real again.
      if (probing) setTimeout(function () { setProbing(false); }, 0);
    } catch (e) {}
  }

  function selectedText() {
    try {
      var a = document.activeElement;
      if (a && (a.tagName === 'TEXTAREA' || (a.tagName === 'INPUT' && /^(text|search|url|)$/i.test(a.type || ''))) &&
          typeof a.selectionStart === 'number') {
        var s = a.value.substring(a.selectionStart, a.selectionEnd);
        return s || a.value;
      }
      return String(window.getSelection ? window.getSelection() : '');
    } catch (e) {
      return '';
    }
  }

  // 1) Async Clipboard API: navigator.clipboard.writeText / write (captured even if the browser
  //    later rejects the write for lack of a user gesture).
  try {
    var cp = window.Clipboard && window.Clipboard.prototype;
    if (cp && typeof cp.writeText === 'function') {
      var origWriteText = cp.writeText;
      cp.writeText = mask(function writeText(data) {
        send('clipboard', data);
        if (probing) return Promise.resolve();
        return origWriteText.apply(this, arguments);
      }, origWriteText);
    }
    if (cp && typeof cp.write === 'function') {
      var origWrite = cp.write;
      cp.write = mask(function write(items) {
        try {
          Array.prototype.forEach.call(items || [], function (item) {
            if (item && item.types && Array.prototype.indexOf.call(item.types, 'text/plain') >= 0) {
              item.getType('text/plain').then(function (b) { return b.text(); }).then(function (t) { send('clipboard', t); }, function () {});
            }
          });
        } catch (e) {}
        if (probing) return Promise.resolve();
        return origWrite.apply(this, arguments);
      }, origWrite);
    }
  } catch (e) {}

  // 2) Legacy document.execCommand('copy') (usually on a hidden, selected <textarea>).
  try {
    var origExec = Document.prototype.execCommand;
    Document.prototype.execCommand = mask(function execCommand(cmd) {
      var isCopy = false;
      try { isCopy = String(cmd).toLowerCase() === 'copy'; if (isCopy) send('execCommand', selectedText()); } catch (e) {}
      if (probing && isCopy) return true;
      return origExec.apply(this, arguments);
    }, origExec);
  } catch (e) {}

  // 2b) clipboardData.setData('text/plain', …) inside a copy handler (seen by some games that
  //     cancel the event, which the bubbling listener below would then miss).
  try {
    var dt = window.DataTransfer && window.DataTransfer.prototype;
    if (dt && typeof dt.setData === 'function') {
      var origSetData = dt.setData;
      dt.setData = mask(function setData(format, data) {
        try { if (/^text(\/plain)?$/i.test(String(format))) send('setData', data); } catch (e) {}
        return origSetData.apply(this, arguments);
      }, origSetData);
    }
  } catch (e) {}

  // 3) Copy events: listen on window in the bubbling phase so the page's own handler has already
  //    put its text on the event's clipboardData.
  try {
    window.addEventListener('copy', function (e) {
      try {
        var d = e.clipboardData && e.clipboardData.getData('text/plain');
        send('copy', d || selectedText());
      } catch (x) {}
    }, false);
  } catch (e) {}

  // 4) Web Share API, only if the WebView provides it (never added, so games that fall back to
  //    copying keep doing so).
  try {
    if (typeof navigator.share === 'function') {
      var origShare = navigator.share;
      navigator.share = mask(function share(data) {
        try {
          send('share', [data && data.title, data && data.text, data && data.url].filter(Boolean).join('\n'));
        } catch (e) {}
        if (probing) return Promise.resolve();
        return origShare.apply(this, arguments);
      }, origShare);
    }
  } catch (e) {}

  // 5) A Share button that opens a window (a "share on …" page) opens nothing while probing. Its
  //    address carries the result (1.8.3): read it, same rules as ShareLinkText.kt.
  var SHARE_LINKS = [
    [/^(twitter\.com|x\.com|mobile\.twitter\.com)\/(intent\/(tweet|post)|share|compose\/(tweet|post))/i, ['text', 'url']],
    [/^(wa\.me|api\.whatsapp\.com|web\.whatsapp\.com)\//i, ['text']],
    [/^t\.me\/share/i, ['text', 'url']],
    [/^(m\.)?facebook\.com\/(sharer|share|dialog\/share)/i, ['quote', 'text']],
    [/^(old\.)?reddit\.com\/submit/i, ['title', 'text']],
    [/^bsky\.app\/intent\/compose/i, ['text']],
    [/^threads\.(net|com)\/intent\/post/i, ['text']]
  ];
  function queryParam(q, key, plusIsSpace) {
    var parts = String(q || '').split('&');
    for (var i = 0; i < parts.length; i++) {
      var eq = parts[i].indexOf('=');
      if (eq > 0 && parts[i].slice(0, eq).toLowerCase() === key) {
        var v = parts[i].slice(eq + 1);
        if (plusIsSpace) v = v.replace(/\+/g, ' ');
        try { v = decodeURIComponent(v); } catch (e) {}
        return v;
      }
    }
    return '';
  }
  function shareLinkText(link) {
    try {
      var s = String(link || '');
      var scheme = (s.split(':')[0] || '').toLowerCase();
      var q = s.split('?').slice(1).join('?').split('#')[0];
      if (/^(sms|smsto|mms|mmsto|mailto)$/.test(scheme)) return queryParam(q, 'body', false);
      if (scheme === 'whatsapp' || scheme === 'tg') return queryParam(q, 'text', true);
      if (scheme !== 'http' && scheme !== 'https') return '';
      var u = new URL(s, location.href);
      var where = u.hostname.replace(/^www\./i, '') + u.pathname;
      for (var i = 0; i < SHARE_LINKS.length; i++) {
        if (!SHARE_LINKS[i][0].test(where)) continue;
        var raw = u.search.slice(1);
        return SHARE_LINKS[i][1].map(function (k) { return queryParam(raw, k, true); }).filter(Boolean)
          .filter(function (v, j, a) { return a.indexOf(v) === j; }).join('\n');
      }
    } catch (e) {}
    return '';
  }
  try {
    var origOpen = window.open;
    if (typeof origOpen === 'function') {
      window.open = mask(function open(url) {
        try { var t = shareLinkText(url); if (t) send('link', t); } catch (e) {}
        if (probing) return null;
        return origOpen.apply(this, arguments);
      }, origOpen);
    }
  } catch (e) {}
  // A Share link the page's own button follows (<a href="https://x.com/intent/post?text=…">).
  try {
    window.addEventListener('click', function (e) {
      try {
        var a = e.target && e.target.closest && e.target.closest('a[href]');
        if (!a) return;
        var t = shareLinkText(a.getAttribute('href'));
        if (t) send('link', t);
      } catch (x) {}
    }, true);
  } catch (e) {}

  if (!FIND_RESULTS) return;
  // ---- Finding a finished game's result --------------------------------------------------------

  // Only in the game's own page (or a frame of the same site), never in an ad frame.
  try {
    if (window.top !== window) {
      var topHost = window.top.location.hostname; // throws for another site's frame
      if (!topHost) return;
    }
  } catch (e) { return; }

  var MAX_PRESSES = 3;
  var presses = 0;
  var tried = typeof WeakSet === 'function' ? new WeakSet() : null;
  var lastInputAt = 0;
  var timers = [];

  // Words a game's end screen shows (a result, the answer, when the next puzzle comes). Not words
  // that also sit on a game's screen while playing ("Statistics", "Streak", "Score").
  var FINISHED_RE = /(next (puzzle|game|word|challenge|dive|round|grid|board|level) (in|at|tomorrow|available)|next [a-z]+ (in|at|tomorrow)|new [a-z]+ (in|at)|come back tomorrow|see you tomorrow|you('ve| have)? (won|solved|got it|found it|did it|completed|finished|crushed)|puzzle (complete|solved|done)|(solved|completed)!|well done|great job|congratulations|splendid|magnificent|phew|nice work|flawless|game over|out of (guesses|lives|tries)|better luck|the answer was|final score|you scored)/i;
  // The same in Spanish, French, German, Portuguese, Italian and Dutch (1.8.3).
  var FINISHED_INTL_RE = /(próxim[oa] (palabra|juego|reto|partida|palavra|jogo|desafio) (en|em)|vuelve mañana|volte amanhã|¡?lo (lograste|conseguiste)|¡?(has|ha) ganado|enhorabuena|felicidades|parabéns|você (acertou|venceu|conseguiu)|la respuesta era|a resposta era|prochain(e)? (mot|jeu|défi|partie) dans|reviens demain|revenez demain|bravo ?!|(tu as|vous avez) (gagné|trouvé|réussi)|félicitations|la réponse était|nächste[sr]? (wort|rätsel|spiel|runde) in|komm morgen wieder|(du hast|sie haben) (gewonnen|es geschafft|gelöst)|glückwunsch|gut gemacht|die lösung war|prossim[oa] (parola|gioco|sfida) tra|torna domani|(hai|avete) (vinto|indovinato|risolto)|complimenti|la risposta era|volgende? (woord|puzzel|spel) over|kom morgen terug|gefeliciteerd|goed gedaan|het antwoord was)/i;
  // A countdown to the next puzzle ("Next puzzle in 05:12:44"), not a game's own timer.
  var COUNTDOWN_RE = /(next|new|until|tomorrow|available|refresh|resets?)[^\n]{0,40}?[0-2]?[0-9]:[0-5][0-9]:[0-5][0-9]/i;
  var BUTTON_RE = /(^|[^a-zà-ÿ])(share|copy|compartir|copiar|partager|copier|teilen|kopieren|condividi|copia|compartilhar|delen|kopiëren|kopieer)([^a-zà-ÿ]|$)/i;
  // "Copy result", "Share my score", "Copy results 🦐": a button that only an end screen has, so it
  // counts as finished on its own (Krillion shows no other end-screen wording).
  var RESULT_BUTTON_RE = /((share|copy)[ \t]*(my|your|the|today'?s)?[ \t]*(results?|scores?|stats|grid|dive)|(compartir|copiar|partager|copier|teilen|kopieren|condividi|copia|compartilhar|delen)[ \t]+(mis |tus |los |mes |les |meine |i |os |de )?(resultados?|résultats?|ergebnis(se)?|risultati|resultaten|puntuación|score|pontuação))/i;
  // A button that opens a finished game's results / stats, where the Share button is (1.8.3).
  var RESULTS_OPEN_RE = /^((see|view|show|open|check)[ \t]+(my[ \t]+|your[ \t]+|the[ \t]+)?)?(results?|stats|statistics|score|summary|scorecard)( ?[>›→])?$|^(ver|voir|mostrar|afficher|zeige|mostra|bekijk)[ \t]+(los |les |die |i |de )?(resultados?|résultats?|ergebnisse|risultati|resultaten|estadísticas|statistiques|statistiken|statistiche)$|^(resultados?|résultats?|ergebnisse|risultati|resultaten|estadísticas|statistiques|statistiken)$/i;
  // Buttons, and the clickable divs / spans some games use instead.
  var CLICKABLE = 'button, [role="button"], a, [onclick], div[class*="button" i], div[class*="btn" i], span[class*="btn" i], div[tabindex="0"]';
  var AVOID_RE = /(link|invite|friend|twitter|(^|[^a-z])x([^a-z]|$)|facebook|whatsapp|messenger|telegram|sms|text message|reddit|discord|instagram|tiktok|threads|bluesky|mastodon|linkedin|email|e-mail|embed|code|url|image|picture|photo|screenshot|download|save)/i;

  // Elements and text inside web components too (open shadow roots), not only the light DOM (1.8.3).
  function deepQueryAll(selector) {
    var out = [];
    var roots = [document];
    for (var r = 0; r < roots.length && r < 60; r++) {
      try {
        var found = roots[r].querySelectorAll(selector);
        for (var i = 0; i < found.length; i++) out.push(found[i]);
        var all = roots[r].querySelectorAll('*');
        for (var j = 0; j < all.length && roots.length < 60; j++) if (all[j].shadowRoot) roots.push(all[j].shadowRoot);
      } catch (e) {}
    }
    return out;
  }
  function deepText(limit) {
    var text = (document.body && document.body.innerText) || '';
    try {
      var hosts = deepQueryAll('*');
      for (var i = 0; i < hosts.length && text.length < limit; i++) {
        if (hosts[i].shadowRoot) text += '\n' + (hosts[i].shadowRoot.textContent || '');
      }
    } catch (e) {}
    return text.slice(0, limit);
  }
  function label(el) {
    var t = (el.getAttribute('aria-label') || el.getAttribute('title') || el.innerText || el.textContent || '').trim();
    return t.replace(/\s+/g, ' ');
  }
  // On screen and really there: inside the viewport, not hidden by an ancestor, and the element
  // a tap at its centre would hit (not covered by something else, not a hidden pre-rendered dialog).
  function pressable(el) {
    try {
      if (el.disabled || el.getAttribute('aria-disabled') === 'true') return false;
      if (el.closest && el.closest('[aria-hidden="true"], [inert], [hidden]')) return false;
      var inShadow = el.getRootNode && el.getRootNode() !== document;
      if (typeof el.checkVisibility === 'function' && !el.checkVisibility({ opacityProperty: true, visibilityProperty: true })) return false;
      var r = el.getBoundingClientRect();
      if (r.width < 8 || r.height < 8) return false;
      var w = window.innerWidth || 0;
      var h = window.innerHeight || 0;
      var cx = r.left + r.width / 2;
      var cy = r.top + r.height / 2;
      if (cx < 0 || cy < 0 || cx > w || cy > h) return false;
      var cs = window.getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.display === 'none' || cs.pointerEvents === 'none' || Number(cs.opacity) <= 0.05) return false;
      var hit = document.elementFromPoint(cx, cy);
      // Inside a web component the hit is its host element: look inside its shadow root for the real one.
      if (inShadow && hit && hit.shadowRoot) {
        var root = el.getRootNode();
        for (var d = 0; hit && hit.shadowRoot && d < 6; d++) {
          var inner = hit.shadowRoot.elementFromPoint(cx, cy);
          if (!inner || inner === hit) break;
          hit = inner;
          if (hit.getRootNode && hit.getRootNode() === root) break;
        }
      }
      return Boolean(hit && (hit === el || el.contains(hit)));
    } catch (e) { return false; }
  }
  function candidates(re) {
    var out = [];
    var test = re || BUTTON_RE;
    var nodes = deepQueryAll(CLICKABLE);
    for (var i = 0; i < nodes.length && out.length < 4; i++) {
      var el = nodes[i];
      if (tried && tried.has(el)) continue;
      // A clickable wrapper around a real button: the button itself is the candidate.
      if (el.tagName !== 'BUTTON' && el.tagName !== 'A' && el.querySelector && el.querySelector('button, [role="button"]')) continue;
      if (el.tagName === 'A') {
        var href = (el.getAttribute('href') || '').trim();
        if (href && href !== '#' && href.toLowerCase().indexOf('javascript:') !== 0) continue; // a real link: never follow it
      }
      var text = label(el);
      if (!text || text.length > 40 || !test.test(text) || AVOID_RE.test(text)) continue;
      if (!pressable(el)) continue;
      out.push(el);
    }
    return out;
  }
  function looksFinished() {
    try {
      var head = deepText(20000);
      return FINISHED_RE.test(head) || FINISHED_INTL_RE.test(head) || COUNTDOWN_RE.test(head);
    } catch (e) { return false; }
  }

  // ---- A result shown on the end screen, for a game with no Share / Copy button (1.8.3) ----
  // A line that holds a score: N/M, a count of guesses / moves…, a time, or points. The app only
  // offers it (the player decides), never logs it by itself.
  var SCREEN_SCORE_RE = /(^|[^0-9a-z/])([0-9]{1,2}|x)\s*\/\s*[0-9]{1,2}([^0-9/:]|$)|\b[0-9]{1,3}\s+(guesses|guess|tries|try|attempts?|moves?|mistakes?|hints?|strokes?|clicks?|steps?|lives)\b|\b(time|in|took)\b[^\n]{0,12}?\b[0-9]{1,2}:[0-5][0-9](:[0-5][0-9])?\b|\b[0-9][0-9,.]{0,8}\s*(points|pts)\b|\bscore[:\s]+[0-9][0-9,.]{0,8}\b/i;
  var SCREEN_CONTEXT_RE = /(solved|completed|finished|got it|found it|you won|your (score|time|result)|result|score|time|took|guessed|in \d)/i;
  function screenResultLine() {
    try {
      var lines = deepText(20000).split('\n');
      for (var i = 0; i < lines.length; i++) {
        var l = lines[i].replace(/\s+/g, ' ').trim();
        if (l.length < 3 || l.length > 80 || !SCREEN_SCORE_RE.test(l)) continue;
        // A bare "3/6" or "1:23" takes its label from the line before ("Your time" / "1:23").
        if (!SCREEN_CONTEXT_RE.test(l) && i > 0) {
          var prev = lines[i - 1].replace(/\s+/g, ' ').trim();
          if (prev && prev.length <= 40 && SCREEN_CONTEXT_RE.test(prev)) return prev.replace(/[:\s]+$/, '') + ': ' + l;
          if (!/^([0-9x]{1,2}\s*\/\s*[0-9]{1,2})$/i.test(l)) continue;
        }
        return l;
      }
    } catch (e) {}
    return '';
  }
  var screenSent = false;
  function offerScreenResult() {
    if (sentAny || screenSent || document.hidden || !looksFinished()) return;
    var line = screenResultLine();
    if (!line) return;
    screenSent = true;
    try { post('{"via":"screen","n":' + quote(NONCE) + ',"text":' + quote(line.slice(0, 120)) + '}'); } catch (e) {}
  }
  function press(el) {
    if (tried) tried.add(el);
    presses += 1;
    // Tells the app a press is coming, so it ignores windows, other apps and downloads it may start.
    try { post('{"kind":"press","n":' + quote(NONCE) + '}'); } catch (e) {}
    setProbing(true);
    try { el.click(); } catch (e) {}
  }
  var openedResults = false;
  var screenTimer = null;
  function scan() {
    // Stop once a result went out (found, or shared by the player) or after a few presses.
    if (sentAny || document.hidden) return;
    var list = presses < MAX_PRESSES ? candidates() : [];
    if (list.length) {
      var strong = list.filter(function (el) { return RESULT_BUTTON_RE.test(label(el)); });
      if (strong.length) { press(strong[0]); return; }
      if (!looksFinished()) return;
      press(list[0]);
      return;
    }
    // No Share / Copy button on screen. A finished game may keep it behind "See results" / "Stats"
    // (1.8.3): press that once, then look again. Never during play: only on an end screen.
    if (!looksFinished()) return;
    if (!openedResults && presses < MAX_PRESSES) {
      var opener = candidates(RESULTS_OPEN_RE);
      if (opener.length) {
        openedResults = true;
        press(opener[0]);
        // The results view opens with an animation; its Share button comes after.
        setTimeout(function () { setProbing(false); }, 400);
        timers.push(setTimeout(scan, 900), setTimeout(scan, 2200), setTimeout(scan, 4000));
        return;
      }
    }
    // Nothing to press: offer the result the end screen shows, a little later (a Share button or the
    // player's own share may still come).
    if (!screenTimer) screenTimer = setTimeout(function () { screenTimer = null; offerScreenResult(); }, 3500);
  }
  function schedule(delays) {
    timers.forEach(clearTimeout);
    timers = delays.map(function (d) { return setTimeout(scan, d); });
  }

  // A game that's already finished shows its end screen soon after loading.
  function onReady() { schedule([2500, 6000]); }
  if (document.readyState === 'complete') onReady(); else window.addEventListener('load', onReady);
  // After each move: the end screen often animates in a few seconds after the last guess.
  function onInput() {
    lastInputAt = Date.now();
    schedule([1500, 4000, 8000]);
  }
  // A real touch or key ends probe mode at once (so the player's own tap copies / shares normally),
  // and a button the player pressed themselves is never pressed again.
  window.addEventListener('pointerdown', function (e) {
    setProbing(false);
    try {
      var b = e.target && e.target.closest && e.target.closest(CLICKABLE);
      if (b && tried) tried.add(b);
    } catch (x) {}
  }, true);
  window.addEventListener('keydown', function () { setProbing(false); }, true);
  window.addEventListener('pointerup', onInput, true);
  window.addEventListener('keyup', onInput, true);
  // Late-appearing end screens (only shortly after the player did something).
  try {
    var pending = null;
    new MutationObserver(function () {
      if (sentAny || Date.now() - lastInputAt > 20000 || pending) return;
      pending = setTimeout(function () { pending = null; scan(); }, 900);
    }).observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) {}
})();
