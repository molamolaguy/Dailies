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
  var probeEndTimer = null;
  function setProbing(on) {
    probing = Boolean(on);
    if (probeTimer) { clearTimeout(probeTimer); probeTimer = null; }
    if (!probing && probeEndTimer) { clearTimeout(probeEndTimer); probeEndTimer = null; }
    // Games build the text right away or after a short animation; probe mode ends after that.
    if (probing) probeTimer = setTimeout(function () { probing = false; probeTimer = null; }, 1500);
  }
  var storageMarkedFinished = false;
  function resetProbeState() { sentAny = false; last = ''; storageMarkedFinished = false; }
  try {
    Object.defineProperty(window, '__dailiesResetShareProbe', {
      value: resetProbeState,
      configurable: true
    });
  } catch (e) {
    window.__dailiesResetShareProbe = resetProbeState;
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
  var ALLOW_NON_DAILY = '__DAILIES_NON_DAILY__' === 'true';
  var BARE_URL_RE = /^https?:\/\/\S+$/i;
  var GRID_PAIR_RE = /(?:[\u2B1B\u2B1C\u26AB\u26AA\u2705\u274C\u{1F7E0}-\u{1F7EB}\u{1F534}\u{1F535}]|[0-9]\uFE0F?\u20E3)\uFE0F?[ ]?(?:[\u2B1B\u2B1C\u26AB\u26AA\u2705\u274C\u{1F7E0}-\u{1F7EB}\u{1F534}\u{1F535}]|[0-9]\uFE0F?\u20E3)/u;
  var PLAUSIBLE_GLYPH_RE = /[\u2B1B\u2B1C\u26AB\u26AA\u2705\u274C\u{1F7E0}-\u{1F7EB}\u{1F534}\u{1F535}\u2B50]|[0-9]\uFE0F?\u20E3/u;
  var PLAUSIBLE_SCORE_RE = /#[ \t\u00A0]*[0-9]|(^|[^0-9/.])([0-9]{1,2}|x)[ \t\u00A0]*\/[ \t\u00A0]*[0-9]{1,2}([^0-9/]|$)|(^|[^0-9:/])[0-9]{1,2}:[0-5][0-9]|\b[0-9][0-9,.]*[ \t\u00A0]*(guesses?|tries|attempts?|mistakes?|pts|points?|km|m|meters?|metres?|ft|feet|moves?|swaps?|strokes?|shots?|steps?|words?|clues?|hints?|%|xp|depth)\b|\b(solved|completed|finished|beat|scored|score|combo|rank|i got to|i reached)\b|\(perfect\)/i;
  var NON_DAILY_TEXT_RE = /(^|[^a-z0-9_/.-])(unlimited|practice|replay|unranked|free[ \t\u00A0]+play|training[ \t\u00A0]+mode|custom[ \t\u00A0]+(puzzle|game|grid)|archive)([^a-z0-9_/.-]|$)/i;
  var INFINITY_TEXT_RE = /(?:^|[ \t\u00A0#])\u221E(?:[ \t\u00A0·:\-]|\n|$)/;
  function hasDisallowedNonDailyMarker(t) {
    if (ALLOW_NON_DAILY) return false;
    return INFINITY_TEXT_RE.test(t) || NON_DAILY_TEXT_RE.test(t);
  }
  function looksPlausibleShare(t) {
    var trimmed = t.trim();
    if (!trimmed || BARE_URL_RE.test(trimmed) || hasDisallowedNonDailyMarker(trimmed)) return false;
    if (PLAUSIBLE_GLYPH_RE.test(trimmed) || PLAUSIBLE_SCORE_RE.test(trimmed)) return true;
    return trimmed.indexOf('\n') >= 0 && /[0-9]/.test(trimmed);
  }
  function send(via, text) {
    try {
      var t = text == null ? '' : String(text);
      if (!t.trim() || t === last) return;
      last = t;
      var wasProbing = probing;
      var plausible = looksPlausibleShare(t);
      if (plausible) sentAny = true;
      post('{"via":' + quote(wasProbing ? 'probe' : via) + ',"n":' + quote(NONCE) + ',"text":' + quote(t.slice(0, 8000)) + '}');
      // Keep probing alive briefly so chained microtasks (e.g. writeText().then(() => navigator.share())) stay suppressed.
      if (wasProbing && plausible) {
        if (probeEndTimer) clearTimeout(probeEndTimer);
        probeEndTimer = setTimeout(function () { probeEndTimer = null; setProbing(false); }, 80);
      }
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
        var wasProbing = probing;
        send('clipboard', data);
        if (wasProbing) return Promise.resolve();
        return origWriteText.apply(this, arguments);
      }, origWriteText);
    }
    if (cp && typeof cp.write === 'function') {
      var origWrite = cp.write;
      cp.write = mask(function write(items) {
        var wasProbing = probing;
        try {
          Array.prototype.forEach.call(items || [], function (item) {
            if (item && item.types && Array.prototype.indexOf.call(item.types, 'text/plain') >= 0) {
              item.getType('text/plain').then(function (b) { return b.text(); }).then(function (t) { send('clipboard', t); }, function () {});
            }
          });
        } catch (e) {}
        if (wasProbing) return Promise.resolve();
        return origWrite.apply(this, arguments);
      }, origWrite);
    }
  } catch (e) {}

  // 2) Legacy document.execCommand('copy') (usually on a hidden, selected <textarea>).
  try {
    var origExec = Document.prototype.execCommand;
    Document.prototype.execCommand = mask(function execCommand(cmd) {
      var isCopy = false;
      var wasProbing = probing;
      try { isCopy = String(cmd).toLowerCase() === 'copy'; if (isCopy) send('execCommand', selectedText()); } catch (e) {}
      if (wasProbing && isCopy) return true;
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
        var wasProbing = probing;
        try { if (/^text(\/plain)?$/i.test(String(format))) send('setData', data); } catch (e) {}
        if (wasProbing) return true;
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
        var wasProbing = probing;
        try {
          send('share', [data && data.title, data && data.text, data && data.url].filter(Boolean).join('\n'));
        } catch (e) {}
        if (wasProbing) return Promise.resolve();
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
    [/^threads\.(net|com)\/intent\/post/i, ['text']],
    [/^(mastodonshare\.com|toot\.kytta\.dev)\//i, ['text']]
  ];
  function safeDecode(s) {
    try { return decodeURIComponent(s); } catch (e) {
      return s.replace(/(?:%[0-9a-fA-F]{2})+/g, function (m) {
        try { return decodeURIComponent(m); } catch (x) { return m; }
      });
    }
  }
  function queryParam(q, key, plusIsSpace) {
    var parts = String(q || '').split('&');
    for (var i = 0; i < parts.length; i++) {
      var eq = parts[i].indexOf('=');
      if (eq > 0 && parts[i].slice(0, eq).toLowerCase() === key) {
        var v = parts[i].slice(eq + 1);
        if (plusIsSpace) v = v.replace(/\+/g, ' ');
        v = safeDecode(v);
        if (v.trim()) return v;
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
        if (!u.search) return '';
        var qIdx = s.indexOf('?');
        var raw = qIdx >= 0 ? s.slice(qIdx + 1) : u.search.slice(1);
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
        var wasProbing = probing;
        try { var t = shareLinkText(url); if (t) send('link', t); } catch (e) {}
        if (wasProbing) return null;
        return origOpen.apply(this, arguments);
      }, origOpen);
    }
  } catch (e) {}
  // A Share link the page's own button follows (<a href="https://x.com/intent/post?text=…">).
  try {
    window.addEventListener('click', function (e) {
      try {
        var path = e.composedPath ? e.composedPath() : null;
        var a = null;
        if (path) {
          for (var i = 0; i < path.length; i++) {
            if (path[i] && path[i].tagName === 'A' && path[i].hasAttribute && path[i].hasAttribute('href')) { a = path[i]; break; }
          }
        }
        if (!a && e.target && e.target.closest) a = e.target.closest('a[href]');
        if (!a) return;
        var t = shareLinkText(a.getAttribute('href'));
        if (t) {
          if (probing) e.preventDefault();
          send('link', t);
        }
      } catch (x) {}
    }, true);
  } catch (e) {}

  // 6) Fallback prompt("Copy your score:", shareText) used by games when clipboard/Web Share fails.
  try {
    var origPrompt = window.prompt;
    if (typeof origPrompt === 'function') {
      window.prompt = mask(function prompt(message, defaultValue) {
        var wasProbing = probing;
        try {
          if (typeof defaultValue === 'string' && looksPlausibleShare(defaultValue)) {
            send('prompt', defaultValue);
            if (wasProbing) return null;
          }
        } catch (e) {}
        if (wasProbing) return null;
        return origPrompt.apply(this, arguments);
      }, origPrompt);
    }
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
  var POST_INPUT_WATCH_MS = 90000;
  var presses = 0;
  var tried = typeof WeakSet === 'function' ? new WeakSet() : null;
  var lastInputAt = 0;
  var timers = [];
  var mutationTimers = [];

  // Observe localStorage / sessionStorage transitions to finished/won/gameOver states after a move,
  // giving a reliable completion signal even on <canvas>/WebGL games or custom-worded end screens.
  var STORAGE_DONE_RE = /"(gameStatus|status|state|phase)"\s*:\s*"(win|won|lost|lose|fail|failed|complete|completed|solved|finished|done|ended|gameover|game_over)"|"(done|completed|isComplete|isCompleted|isFinished|finished|solved|isSolved|gameOver|isGameOver|hasWon|won)"\s*:\s*true\b/gi;
  function countDoneTokens(s) {
    if (!s || s.length > 200000) return 0;
    STORAGE_DONE_RE.lastIndex = 0;
    var c = 0;
    while (STORAGE_DONE_RE.exec(s)) c += 1;
    return c;
  }
  try {
    var sp = window.Storage && window.Storage.prototype;
    if (sp && typeof sp.setItem === 'function') {
      var origSetItem = sp.setItem;
      sp.setItem = mask(function setItem(key, value) {
        var oldVal = null;
        var active = !sentAny && lastInputAt > 0 && (Date.now() - lastInputAt <= POST_INPUT_WATCH_MS);
        if (active) {
          try { oldVal = this.getItem(key); } catch (e) {}
        }
        var res = origSetItem.apply(this, arguments);
        if (active) {
          try {
            var vStr = String(value == null ? '' : value);
            if (countDoneTokens(vStr) > countDoneTokens(oldVal == null ? '' : String(oldVal))) {
              storageMarkedFinished = true;
              mutationTimers.forEach(clearTimeout);
              mutationTimers = [
                setTimeout(scan, 900),
                setTimeout(scan, 2600),
                setTimeout(scan, 6500)
              ];
            }
          } catch (e) {}
        }
        return res;
      }, origSetItem);
    }
  } catch (e) {}

  // Words a game's end screen shows (a result, the answer, when the next puzzle comes). Matched per
  // short visible UI line (never across multi-sentence rules/FAQ prose), and never words that sit on
  // a game's screen while playing ("Statistics", "Streak", "Score").
  var FINISHED_RE = /(next (puzzle|game|word|challenge|dive|round|grid|board|level|expedition) (in|at|tomorrow|available|opens|is)|the next [a-z]+ is|next [a-z]+ (in|at|tomorrow|opens)|new [a-z]+ (in|at|opens)|come back tomorrow|see you tomorrow|you('ve| have)? (won|solved|got it|found it|did it|completed|finished|crushed)|you (win|lost|lose)!|(?:puzzle|dive|game|round|challenge|expedition|daily|level|mission)\s+(?:#\s*[0-9]+\s+)?(complete|completed|solved|finished|done)(?![ \t]+(by|when|once|if|after|with)\b)|(solved|completed)!|game over|out of (guesses|lives|tries)|better luck|the answer was|(?:^|[.!?]\s+)the mystery (country|capital|city|state|place|island|flag|word|answer|animal|person|movie|song|game) is\b|final score|you scored)/i;
  var TODAY_STAT_LABEL_RE = /^(?:today'?s|daily)\s+(?:guesses|tries|attempts|score|time|moves)$/i;
  var TODAY_STAT_VALUE_RE = /^[1-9][0-9]{0,4}(?::[0-5][0-9])?$/;
  // Standalone praise callouts ("Splendid!", "Flawless!", "Well done") only count on short heading
  // lines, never as mid-sentence adjectives in rules prose ("A flawless grid leaves three...").
  var FINISHED_CALLOUT_RE = /^(?:[^a-z0-9à-ÿ]{0,4}\s*)?(well done|great job|congratulations|congrats|splendid|magnificent|phew|nice work|flawless|bravo|enhorabuena|felicidades|parabéns|félicitations|glückwunsch|gut gemacht|complimenti|gefeliciteerd|goed gedaan)(?:[!.\s]|[^a-z0-9à-ÿ]{1,4})*$/i;
  // The same in Spanish, French, German, Portuguese, Italian and Dutch (1.8.3).
  var FINISHED_INTL_RE = /(próxim[oa] (palabra|juego|reto|partida|palavra|jogo|desafio) (en|em)|vuelve mañana|volte amanhã|¡?lo (lograste|conseguiste)|¡?(has|ha) ganado|você (acertou|venceu|conseguiu)|la respuesta era|a resposta era|prochain(e)? (mot|jeu|défi|partie) dans|reviens demain|revenez demain|(tu as|vous avez) (gagné|trouvé|réussi)|la réponse était|nächste[sr]? (wort|rätsel|spiel|runde) in|komm morgen wieder|(du hast|sie haben) (gewonnen|es geschafft|gelöst)|die lösung war|prossim[oa] (parola|gioco|sfida) tra|torna domani|(hai|avete) (vinto|indovinato|risolto)|la risposta era|volgende? (woord|puzzel|spel) over|kom morgen terug|het antwoord was)/i;
  // A countdown to the next puzzle ("Next puzzle in 05:12:44", "Expedition #53 opens in 9 h 12 m"),
  // not a game's own running clock.
  var COUNTDOWN_COLON_RE = /(next|new|until|tomorrow|available|refresh|resets?|opens?|unlocks?|starts?|begins?|returns?|back)[^\n]{0,45}?[0-2]?[0-9]:[0-5][0-9]:[0-5][0-9]/i;
  var COUNTDOWN_WORDS_RE = /(?:(next|new|tomorrow'?s)[^\n]{0,35}?\b(in|opens?\s+in|unlocks?\s+in|available\s+in)|(?:[a-z]+\s+)?#[0-9]+\s+(opens?|unlocks?|starts?|available)\s+in|\b(resets?|opens?|unlocks?)\s+in)\s+([0-9]{1,2}\s*(days?|d|hours?|hrs?|h)\b([,\s]+[0-9]{1,2}\s*(hours?|hrs?|h|minutes?|mins?|m)\b)?|[0-9]{1,2}\s*(minutes?|mins?|m)\b|under a minute)/i;
  function hasCountdown(line) {
    return COUNTDOWN_COLON_RE.test(line) || COUNTDOWN_WORDS_RE.test(line);
  }
  var BUTTON_RE = /(^|[^a-zà-ÿ])(share|copy|compartir|copiar|partager|copier|teilen|kopieren|condividi|copia|compartilhar|delen|kopiëren|kopieer)([^a-zà-ÿ]|$)/i;
  var COPY_ONLY_RE = /(^|[^a-zà-ÿ])(copy|copiar|copier|kopieren|copia|kopiëren|kopieer)([^a-zà-ÿ]|$)/i;
  // "Copy result", "Share my score", "Copy results 🦐": a button that only an end screen has, so it
  // counts as finished on its own (Krillion shows no other end-screen wording).
  var RESULT_BUTTON_RE = /((share|copy)[ \t]*(my|your|the|today'?s)?[ \t]*(results?|scores?|stats|grid|dive|text|emoji|to clipboard)|(compartir|copiar|partager|copier|teilen|kopieren|condividi|copia|compartilhar|delen)[ \t]+(mis |tus |los |mes |les |meine |i |os |de )?(resultados?|résultats?|ergebnis(se)?|risultati|resultaten|puntuación|score|pontuação))/i;
  // Icon-only share / copy buttons strictly inside an end-screen modal when aria-label/text was omitted.
  var ICON_SHARE_ATTR_RE = /(^|[^a-z])(copy[-_ ]?(result|score|btn|button|text|clipboard)?|share[-_ ]?(result|score|btn|button|card|text)?|btn[-_ ]?(copy|share))([^a-z]|$)/i;
  var ICON_COPY_ATTR_RE = /(^|[^a-z])(copy[-_ ]?(result|score|btn|button|text|clipboard)?|btn[-_ ]?copy)([^a-z]|$)/i;
  var ICON_STRONG_ATTR_RE = /(^|[^a-z])(copy|share)[-_ ]?(result|score)s?([^a-z]|$)/i;
  // A button that opens a finished game's results / stats, where the Share button is (1.8.3).
  var RESULTS_OPEN_RE = /^((see|view|show|open|check)[ \t]+(my[ \t]+|your[ \t]+|the[ \t]+)?)?(results?|stats|statistics|score|summary|scorecard)( ?[>›→])?$|^(ver|voir|mostrar|afficher|zeige|mostra|bekijk)[ \t]+(los |les |die |i |de )?(resultados?|résultats?|ergebnisse|risultati|resultaten|estadísticas|statistiques|statistiken|statistiche)$|^(resultados?|résultats?|ergebnisse|risultati|resultaten|estadísticas|statistiques|statistiken)$/i;
  // Buttons, and the clickable divs / spans some games use instead.
  var CLICKABLE = 'button, [role="button"], a, [onclick], div[class*="button" i], div[class*="btn" i], span[class*="btn" i], div[tabindex="0"]';
  var AVOID_RE = /(link|invite|friend|twitter|(^|[^a-z])x([^a-z]|$)|facebook|whatsapp|messenger|telegram|sms|text message|reddit|discord|instagram|tiktok|threads|bluesky|mastodon|linkedin|email|e-mail|embed|code|url|image|picture|photo|screenshot|download|save|enlace|vínculo|imagen|descargar|invitar|amigo|lien|télécharger|inviter|ami|bild|herunterladen|einladen|freund|immagine|scarica|invita|amico|imagem|baixar|convidar|afbeelding|downloaden|uitnodigen|vriend)/i;
  var END_CONTAINER_RE = /(modal|dialog|popup|overlay|end[-_]?screen|result[-_]?screen|game[-_]?over|camp[-_]?sheet|done[-_]?card|done[-_]?screen)/i;
  var PROSE_BLOCK_RE = /(^|[^a-z])(faq|seo|how[-_]?to|instructions?|rules[-_]?box|tutorial|privacy|cookie|legal|changelog|roadmap)([^a-z]|$)/i;
  var REVEAL_ATTR_TARGET_RE = /(modal|dialog|overlay|popup|camp|done|result|end|finish|win|summary|share|visible|open|show|active)/i;
  var NON_DAILY_HEADING_RE = /^(?:mode\s*[:·\-]\s*)?(unlimited(?: mode| puzzle| game)?|practice(?: mode| round| expedition| game)?|free play|training mode|custom (?:puzzle|game|grid)|replay\s*[·\-]\s*unranked)$/i;

  // Elements and text inside web components too (open shadow roots), not only the light DOM (1.8.3).
  var MAX_SHADOW_ROOTS = 200;
  function deepQueryAll(selector) {
    var out = [];
    var roots = [document];
    for (var r = 0; r < roots.length && r < MAX_SHADOW_ROOTS; r++) {
      try {
        var found = roots[r].querySelectorAll(selector);
        for (var i = 0; i < found.length; i++) out.push(found[i]);
        var all = roots[r].querySelectorAll('*');
        for (var j = 0; j < all.length && roots.length < MAX_SHADOW_ROOTS; j++) if (all[j].shadowRoot) roots.push(all[j].shadowRoot);
      } catch (e) {}
    }
    return out;
  }
  function classAndId(el) {
    var cls = el.className && typeof el.className === 'string' ? el.className : '';
    return cls + ' ' + (el.id || '') + ' ' + ((el.getAttribute && el.getAttribute('aria-label')) || '');
  }
  function buttonAttrHint(el) {
    var cls = el.className && typeof el.className === 'string' ? el.className : '';
    var dt = (el.getAttribute && (el.getAttribute('data-testid') || el.getAttribute('data-action'))) || '';
    return ((el.id || '') + ' ' + cls + ' ' + dt).trim();
  }
  function findEndContainer(el) {
    try {
      for (var cur = el; cur; cur = cur.parentElement || (cur.getRootNode && cur.getRootNode().host) || null) {
        if (cur.nodeType !== 1) continue;
        if (cur.tagName === 'DIALOG') return cur;
        var role = cur.getAttribute && cur.getAttribute('role');
        if (role === 'dialog' || role === 'alertdialog') return cur;
        if (END_CONTAINER_RE.test(classAndId(cur))) return cur;
      }
    } catch (e) {}
    return null;
  }
  function inModalContainer(el) {
    return Boolean(findEndContainer(el));
  }
  // Groups inline children and compact single-row flex pills (e.g. `<span>0/9</span> <span>Fields</span>`
  // or `Expedition #53 opens in <span>9 h 12 m</span>`) onto one logical line instead of splitting them.
  function lineContainer(el) {
    var cur = el;
    for (var depth = 0; cur && cur.nodeType === 1 && depth < 6; depth++) {
      var parent = cur.parentElement;
      if (!parent || parent.tagName === 'BODY') break;
      try {
        var cs = window.getComputedStyle(cur);
        var d = cs.display || '';
        if (d === 'inline' || d === 'contents') { cur = parent; continue; }
        var pcs = window.getComputedStyle(parent);
        var pd = pcs.display || '';
        if ((pd === 'inline-flex' || pd === 'flex') && (pcs.flexDirection || '').indexOf('column') < 0) {
          var pr = parent.getBoundingClientRect();
          if (pr.height > 0 && pr.height <= 56) { cur = parent; continue; }
        }
      } catch (e) {}
      break;
    }
    return cur;
  }
  // Visible UI lines currently on screen (plus scrollable content inside an active end-screen sheet),
  // excluding hidden elements, closed <details>, FAQ/SEO/rules prose, and off-screen below-the-fold text.
  function visibleLines(limit) {
    var lines = [];
    var totalChars = 0;
    var viewH = window.innerHeight || 0;
    function walkRoot(root, skipSingleCharRoot) {
      if (!root || totalChars >= limit) return;
      var rootLines = [];
      var lastBox = null;
      var current = '';
      try {
        var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
          acceptNode: function (n) {
            var el = n.parentElement;
            if (!el) return NodeFilter.FILTER_REJECT;
            var modal = findEndContainer(el);
            for (var p = el; p && p !== root; p = p.parentNode) {
              if (p.nodeType !== 1) continue;
              var tag = p.tagName;
              if (tag === 'STYLE' || tag === 'SCRIPT' || tag === 'TEMPLATE' || tag === 'NOSCRIPT' || tag === 'SVG') return NodeFilter.FILTER_REJECT;
              if (tag === 'DETAILS' && (!p.open || !modal)) return NodeFilter.FILTER_REJECT;
              if (p.hasAttribute && (p.hasAttribute('hidden') || p.hasAttribute('inert') || p.getAttribute('aria-hidden') === 'true')) return NodeFilter.FILTER_REJECT;
              if (!modal && PROSE_BLOCK_RE.test(classAndId(p))) return NodeFilter.FILTER_REJECT;
              try {
                var cs = window.getComputedStyle(p);
                if (cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity) <= 0.05) return NodeFilter.FILTER_REJECT;
              } catch (e) {}
            }
            if (viewH > 0) {
              try {
                var r = el.getBoundingClientRect();
                if (r.width > 0 || r.height > 0) {
                  if (r.width < 1 || r.height < 1) return NodeFilter.FILTER_REJECT;
                  if (r.bottom <= 0 || r.top >= viewH + 40) {
                    if (!modal) return NodeFilter.FILTER_REJECT;
                    var mr = modal.getBoundingClientRect();
                    if (mr.bottom <= 0 || mr.top >= viewH) return NodeFilter.FILTER_REJECT;
                  }
                }
              } catch (e) {}
            }
            return NodeFilter.FILTER_ACCEPT;
          }
        });
        var node;
        while ((node = w.nextNode()) && totalChars < limit) {
          var v = String(node.nodeValue || '').replace(/\s+/g, ' ').trim();
          if (!v) continue;
          var box = lineContainer(node.parentElement);
          if (box === lastBox && current) {
            current += ' ' + v;
          } else {
            if (current) { rootLines.push(current); totalChars += current.length + 1; }
            current = v;
            lastBox = box;
          }
        }
        if (current) { rootLines.push(current); totalChars += current.length + 1; }
      } catch (e) {}
      if (skipSingleCharRoot && rootLines.length === 1 && rootLines[0].length <= 1) return;
      for (var i = 0; i < rootLines.length; i++) lines.push(rootLines[i]);
    }
    if (document.body) walkRoot(document.body, false);
    try {
      var roots = [document];
      for (var r = 0; r < roots.length && r < MAX_SHADOW_ROOTS && totalChars < limit; r++) {
        var all = roots[r].querySelectorAll('*');
        for (var i = 0; i < all.length && roots.length < MAX_SHADOW_ROOTS && totalChars < limit; i++) {
          var sr = all[i].shadowRoot;
          if (sr) {
            roots.push(sr);
            walkRoot(sr, true);
          }
        }
      }
    } catch (e) {}
    return lines;
  }
  // Collects aria-label, title, and visible text independently so a verbose title attribute (>40 chars)
  // never hides a crisp visible "Share" label, while any AVOID_RE match on any label disqualifies the button.
  function buttonLabels(el) {
    var out = [];
    function push(raw, maxLen) {
      var s = String(raw || '').replace(/\s+/g, ' ').trim();
      if (s && s.length <= maxLen && out.indexOf(s) < 0) out.push(s);
    }
    push(el.getAttribute && el.getAttribute('aria-label'), 80);
    push(el.getAttribute && el.getAttribute('title'), 80);
    push(el.innerText || el.textContent, 45);
    return out;
  }
  function isStrongResultButton(el) {
    var labels = buttonLabels(el);
    for (var i = 0; i < labels.length; i++) {
      if (RESULT_BUTTON_RE.test(labels[i])) return true;
    }
    if (!labels.length && inModalContainer(el)) {
      var hint = buttonAttrHint(el);
      if (hint && !AVOID_RE.test(hint) && ICON_STRONG_ATTR_RE.test(hint)) return true;
    }
    return false;
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
  // Below-the-fold result button in normal scrollable document layout (e.g. Krillion's "Copy result"
  // below its 7-row dive breakdown): visible, enabled, unclipped by overflow:hidden ancestors, and
  // horizontally within the viewport.
  function pressableBelowFold(el) {
    try {
      if (el.disabled || el.getAttribute('aria-disabled') === 'true') return false;
      if (el.closest && el.closest('[aria-hidden="true"], [inert], [hidden]')) return false;
      if (typeof el.checkVisibility === 'function' && !el.checkVisibility({ opacityProperty: true, visibilityProperty: true })) return false;
      var r = el.getBoundingClientRect();
      if (r.width < 8 || r.height < 8) return false;
      var w = window.innerWidth || 0;
      var h = window.innerHeight || 0;
      if (w <= 0 || h <= 0) return false;
      var cx = r.left + r.width / 2;
      var cy = r.top + r.height / 2;
      if (cx < 0 || cx > w || cy <= h || r.top > Math.max(h * 5, 4000)) return false;
      var cs = window.getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.display === 'none' || cs.pointerEvents === 'none' || Number(cs.opacity) <= 0.05) return false;
      var modal = findEndContainer(el);
      for (var p = el.parentElement; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
        if (!modal && PROSE_BLOCK_RE.test(classAndId(p))) return false;
        var pcs = window.getComputedStyle(p);
        if (pcs.display === 'none' || pcs.visibility === 'hidden' || Number(pcs.opacity) <= 0.05) return false;
        var ov = (pcs.overflow || '') + ' ' + (pcs.overflowX || '') + ' ' + (pcs.overflowY || '');
        if (/(hidden|clip)/.test(ov)) {
          var pr = p.getBoundingClientRect();
          if (cx < pr.left - 1 || cx > pr.right + 1 || cy < pr.top - 1 || cy > pr.bottom + 1) return false;
        }
      }
      return true;
    } catch (e) { return false; }
  }
  function candidates(re, allowBelowFold) {
    var out = [];
    var test = re || BUTTON_RE;
    var allowIconAttr = (test === BUTTON_RE || test === COPY_ONLY_RE);
    var iconRe = test === COPY_ONLY_RE ? ICON_COPY_ATTR_RE : ICON_SHARE_ATTR_RE;
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
      var rawAll = ((el.getAttribute && el.getAttribute('aria-label')) || '') + ' ' +
                   ((el.getAttribute && el.getAttribute('title')) || '') + ' ' +
                   ((el.innerText || el.textContent || '').slice(0, 80));
      if (AVOID_RE.test(rawAll)) continue;
      var labels = buttonLabels(el);
      var matched = false;
      for (var j = 0; j < labels.length; j++) {
        if (test.test(labels[j])) { matched = true; break; }
      }
      if (!matched && !labels.length && allowIconAttr && inModalContainer(el)) {
        var hint = buttonAttrHint(el);
        if (hint && !AVOID_RE.test(hint) && iconRe.test(hint)) matched = true;
      }
      if (!matched) continue;
      if (!pressable(el) && !(allowBelowFold && pressableBelowFold(el))) continue;
      out.push(el);
    }
    return out;
  }
  function isNonDailyModeScreen(lines) {
    if (ALLOW_NON_DAILY) return false;
    for (var i = 0; i < lines.length; i++) {
      var l = lines[i];
      if (l.length <= 40 && NON_DAILY_HEADING_RE.test(l)) return true;
    }
    return false;
  }
  function lineIndicatesFinished(l) {
    if (!l || l.length > 120) return false;
    if (FINISHED_RE.test(l) || FINISHED_INTL_RE.test(l)) return true;
    return l.length <= 45 && FINISHED_CALLOUT_RE.test(l);
  }
  function hasFinishedStatPair(lines) {
    for (var i = 0; i + 1 < lines.length; i++) {
      if (TODAY_STAT_LABEL_RE.test(lines[i]) && TODAY_STAT_VALUE_RE.test(lines[i + 1])) return true;
    }
    return false;
  }
  function looksFinished(linesOpt) {
    try {
      var lines = linesOpt || visibleLines(20000);
      if (isNonDailyModeScreen(lines)) return false;
      if (storageMarkedFinished) return true;
      for (var i = 0; i < lines.length; i++) {
        var l = lines[i];
        if (lineIndicatesFinished(l) || hasCountdown(l)) return true;
      }
      return hasFinishedStatPair(lines);
    } catch (e) { return false; }
  }
  // End-screen words, not only a countdown: some games show "Next puzzle in 05:12:44" all the time,
  // also before the player has started (1.8.12).
  var NEXT_LINE_RE = /(next|new|nächste[sr]?|próxim[oa]|prochaine?|prossim[oa]|volgende?)\b[^\n]{0,35}\b(in|at|en|em|dans|tra|over|opens)\b|(?:[a-z]+\s+)?#[0-9]+\s+(opens?|unlocks?|starts?|available)\s+in\b|\b(opens?|unlocks?|resets?)\s+in\s+([0-9]|under a minute)/i;
  function saysFinished(linesOpt) {
    try {
      var lines = linesOpt || visibleLines(20000);
      if (isNonDailyModeScreen(lines)) return false;
      for (var i = 0; i < lines.length; i++) {
        var l = lines[i];
        if (NEXT_LINE_RE.test(l) || hasCountdown(l)) continue;
        if (lineIndicatesFinished(l)) return true;
      }
      return hasFinishedStatPair(lines);
    } catch (e) { return false; }
  }

  // ---- Pre-populated share text blocks (<textarea readonly>, <input readonly>, <pre>) ----
  function prepopulatedShareText() {
    try {
      var viewH = window.innerHeight || 0;
      var viewW = window.innerWidth || 0;
      var fields = deepQueryAll('textarea, input[readonly], pre');
      for (var i = 0; i < fields.length; i++) {
        var el = fields[i];
        if (el.closest && el.closest('[aria-hidden="true"], [inert], [hidden]')) continue;
        if (el.tagName === 'TEXTAREA' && !el.readOnly && !el.disabled && !inModalContainer(el)) continue;
        var cs = window.getComputedStyle(el);
        if (cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity) <= 0.05) continue;
        var r = el.getBoundingClientRect();
        if (r.width < 40 || r.height < 20) continue;
        if (viewH > 0 && (r.bottom <= 0 || r.top >= viewH || r.right <= 0 || r.left >= viewW)) continue;
        var raw = (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') ? el.value : el.innerText;
        var val = String(raw || '').trim();
        if (val.length < 6 || val.length > 2000 || !looksPlausibleShare(val)) continue;
        if (GRID_PAIR_RE.test(val) || (val.indexOf('\n') >= 0 && (/#[ \t]*[0-9]/.test(val) || /\b([0-9]{1,2}|x)\s*\/\s*[0-9]{1,2}\b/i.test(val)))) {
          return val;
        }
      }
    } catch (e) {}
    return '';
  }

  // ---- A result shown on the end screen, for a game with no Share / Copy button (1.8.3) ----
  // A line that holds a score: N/M, a count of guesses / moves…, a time, or points. The app only
  // offers it (the player decides), never logs it by itself.
  // A time counts only as the player's own ("Time 1:23", "Solved in 2:05"), not "… in 05:12:44" on
  // a countdown to the next puzzle (1.8.12: noillirk.com's countdown was offered as the score).
  var SCREEN_SCORE_RE = /(^|[^0-9a-z/])([0-9]{1,2}|x)\s*\/\s*[0-9]{1,2}([^0-9/:]|$)|\b[0-9]{1,3}\s+(guesses|guess|tries|try|attempts?|moves?|mistakes?|hints?|strokes?|clicks?|steps?)\b|\b(time|took|(solved|completed|finished|done|cleared|did it|got it) in)\b[^\n]{0,12}?\b[0-9]{1,2}:[0-5][0-9](:[0-5][0-9])?\b|\b[0-9][0-9,.]{0,8}\s*(points|pts)\b|\bscore[:\s]+[0-9][0-9,.]{0,8}\b/i;
  var SCREEN_CONTEXT_RE = /(solved|completed|finished|got it|found it|you won|your (final )?(score|time|result)|final (score|time|result)|result|score|time|took|guessed|in \d)/i;
  // Never a result: a countdown to the next puzzle, or a line about when it comes / resets.
  var NOT_RESULT_RE = /(next|new|until|tomorrow|available|refresh|resets?|opens?|unlocks?|countdown|time\s+(remaining|left))\b/i;
  // Live in-game progress / board counters ("0/9 Fields", "Round 2/5", "12 Guesses left", "3/10 Found")
  // that sit in a game's HUD while playing and must never be offered as a finished result.
  var PROGRESS_UNIT_RE = /\b(fields?|felder|cells?|tiles?|squares?|slots?|items?|objects?|differences?|pairs?|sets?|groups?|categories|countries|flags?|locations?|pins?|targets?|stages?|rounds?|levels?|questions?|prompts?|waves?|chapters?|hearts?|lives|left|remaining|verbleibend|restantes?|restants?|found|discovered|collected|filled|gefüllt)\b/i;
  var ZERO_SCORE_LINE_RE = /(^|[^0-9])0+\s*\/\s*[0-9]{1,2}\b|\bscore[:\s]+0+(\b|$)|\b0+\s*(points?|pts|guesses?|tries|moves?)\b/i;
  var FINAL_ZERO_CONTEXT_RE = /(final (score|result)|your (final )?(score|result)|game over|out of (guesses|lives|tries))/i;
  var ACTIVE_PLAY_RE = /\b([0-9]+\s+(guesses|tries|attempts|lives|moves|hints)\s+(left|remaining)|click a cell|select a cell|enter a (game|word|guess)|type (a|your) (guess|word|answer)|make a guess|drop pin)\b/i;
  function hasVisibleEndModal() {
    try {
      var nodes = deepQueryAll('dialog, [role="dialog"], [role="alertdialog"], [class*="modal" i], [class*="overlay" i], [class*="done-card" i], [class*="camp-sheet" i], [class*="end-screen" i], [class*="result-screen" i]');
      var viewH = window.innerHeight || 0;
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        if (el.hasAttribute && (el.hasAttribute('hidden') || el.getAttribute('aria-hidden') === 'true')) continue;
        var cs = window.getComputedStyle(el);
        if (cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity) <= 0.05) continue;
        var r = el.getBoundingClientRect();
        if (r.width >= 40 && r.height >= 40 && (viewH === 0 || (r.bottom > 0 && r.top < viewH))) return true;
      }
    } catch (e) {}
    return false;
  }
  function screenResultLines(linesOpt) {
    var out = [];
    try {
      var lines = linesOpt || visibleLines(20000);
      var allowZero = lines.some(function (ln) { return FINAL_ZERO_CONTEXT_RE.test(ln); });
      for (var i = 0; i < lines.length && out.length < 6; i++) {
        var l = lines[i].replace(/\s+/g, ' ').trim();
        if (l.length < 3 || l.length > 80 || NOT_RESULT_RE.test(l) || hasCountdown(l) || PROGRESS_UNIT_RE.test(l)) continue;
        if (/(^|[^0-9])0?0:00(:00)?([^0-9]|$)/.test(l)) continue; // a clock that hasn't started
        if (!allowZero && ZERO_SCORE_LINE_RE.test(l)) continue; // unstarted/zero HUD counter ("0/9", "Score: 0")
        var prev = i > 0 ? lines[i - 1].replace(/\s+/g, ' ').trim() : '';
        var next = i + 1 < lines.length ? lines[i + 1].replace(/\s+/g, ' ').trim() : '';
        if ((prev && prev.length <= 24 && PROGRESS_UNIT_RE.test(prev)) || (next && next.length <= 24 && PROGRESS_UNIT_RE.test(next))) continue;
        var bareTime = /^[0-9]{1,2}:[0-5][0-9](:[0-5][0-9])?$/.test(l);
        if (!SCREEN_SCORE_RE.test(l) && !bareTime) continue;
        // A bare "3/6" or "1:23" only counts when preceded by an explicit result/score/time label
        // ("Your score" / "7/9", "Your time" / "1:23"), never as an unlabeled HUD badge.
        if (!SCREEN_CONTEXT_RE.test(l)) {
          if (prev && prev.length <= 40 && SCREEN_CONTEXT_RE.test(prev) && !NOT_RESULT_RE.test(prev) && !PROGRESS_UNIT_RE.test(prev) && !SCREEN_SCORE_RE.test(prev)) {
            out.push(prev.replace(/[:\s]+$/, '') + ': ' + l);
          }
          continue;
        }
        if (bareTime) continue; // a time with nothing saying whose it is
        out.push(l);
      }
    } catch (e) {}
    return out;
  }
  var screenSent = false;
  function offerScreenResult() {
    // Only on a real end screen (its words, not only a countdown), never while active gameplay prompts
    // sit uncovered on screen, and only once.
    if (sentAny || screenSent || document.hidden) return;
    var lines = visibleLines(20000);
    if (!saysFinished(lines)) return;
    if (!hasVisibleEndModal() && lines.some(function (l) { return ACTIVE_PLAY_RE.test(l); })) return;
    var first = screenResultLines(lines);
    if (!first.length) return;
    // A line that changes from one second to the next is a clock still running (the game's own timer
    // or a countdown), never the result: read again a moment later and keep a line that stayed put.
    setTimeout(function () {
      if (sentAny || screenSent || document.hidden) return;
      var again = screenResultLines();
      // Of the lines that stayed put, the one that says what the player did ("Solved in 3 guesses")
      // before a count, and a count before a bare clock ("Time 0:11").
      var rank = function (l) {
        if (/(solved|completed|finished|got it|found it|you won|guessed|your (final )?(score|result)|final (score|result))/i.test(l)) return 0;
        if (/[0-9x]\s*\/\s*[0-9]|[0-9]\s+(guess|tr(y|ies)|attempt|move|mistake|hint|stroke|click|step|point|pts)/i.test(l)) return 1;
        return 2;
      };
      var stable = first.filter(function (l) { return again.indexOf(l) >= 0; });
      stable.sort(function (a, b) { return rank(a) - rank(b); }); // (a stable sort keeps the page's order otherwise)
      var line = stable[0] || '';
      if (!line) return;
      screenSent = true;
      try { post('{"via":"screen","n":' + quote(NONCE) + ',"text":' + quote(line.slice(0, 120)) + '}'); } catch (e) {}
    }, 1600);
  }
  // If pressing a "Share" button opened a custom share drawer instead of copying immediately,
  // check for a newly revealed "Copy" / "Copy result" item inside that drawer.
  function scanCopyFollowUp() {
    if (sentAny || document.hidden || presses >= MAX_PRESSES) return;
    var copyBtns = candidates(COPY_ONLY_RE);
    if (copyBtns.length) {
      press(copyBtns[0], true);
      return;
    }
    var pre = prepopulatedShareText();
    if (pre) {
      presses += 1;
      try { post('{"kind":"press","n":' + quote(NONCE) + '}'); } catch (e) {}
      setProbing(true);
      send('probe', pre);
    }
  }
  function dispatchTouchClick(el) {
    try {
      var r = el.getBoundingClientRect();
      var cx = r.left + r.width / 2;
      var cy = r.top + r.height / 2;
      var base = { bubbles: true, cancelable: true, composed: true, clientX: cx, clientY: cy, button: 0 };
      if (typeof PointerEvent === 'function') {
        el.dispatchEvent(new PointerEvent('pointerdown', Object.assign({ pointerId: 1, pointerType: 'touch', isPrimary: true, buttons: 1 }, base)));
      }
      if (typeof MouseEvent === 'function') {
        el.dispatchEvent(new MouseEvent('mousedown', Object.assign({ buttons: 1 }, base)));
      }
      if (typeof PointerEvent === 'function') {
        el.dispatchEvent(new PointerEvent('pointerup', Object.assign({ pointerId: 1, pointerType: 'touch', isPrimary: true, buttons: 0 }, base)));
      }
      if (typeof MouseEvent === 'function') {
        el.dispatchEvent(new MouseEvent('mouseup', Object.assign({ buttons: 0 }, base)));
      }
    } catch (e) {}
    if (!sentAny) {
      try { el.click(); } catch (e) {}
    }
  }
  function press(el, skipCopyFollowUp) {
    if (tried) tried.add(el);
    presses += 1;
    // Tells the app a press is coming, so it ignores windows, other apps and downloads it may start.
    try { post('{"kind":"press","n":' + quote(NONCE) + '}'); } catch (e) {}
    setProbing(true);
    dispatchTouchClick(el);
    if (!skipCopyFollowUp && !sentAny && presses < MAX_PRESSES) {
      timers.push(setTimeout(scanCopyFollowUp, 750), setTimeout(scanCopyFollowUp, 1800));
    }
  }
  var openedResults = false;
  var screenTimer = null;
  var scrollTimer = null;
  var loadedAt = Date.now();
  function scan() {
    // Stop once a result went out (found, or shared by the player) or after a few presses.
    if (sentAny || document.hidden) return;
    var lines = visibleLines(20000);
    if (isNonDailyModeScreen(lines)) return;
    var list = presses < MAX_PRESSES ? candidates() : [];
    var finished = looksFinished(lines);
    if (list.length) {
      // Explicit result buttons ("Copy result", "Share score", "Copy dive") only exist on end screens,
      // even when the top heading scrolled out of view or the container isn't a modal (e.g. Krillion).
      var strong = list.filter(isStrongResultButton);
      if (strong.length) { press(strong[0]); return; }
      if (finished) { press(list[0]); return; }
    }
    // Check below-the-fold explicit result buttons (e.g. Krillion's "Copy result" below 7 prompt cards)
    // and, on finished pages, below-the-fold Share / Copy buttons before opening a secondary Stats sheet.
    if (presses < MAX_PRESSES) {
      var belowStrong = candidates(RESULT_BUTTON_RE, true).filter(isStrongResultButton);
      if (belowStrong.length) { press(belowStrong[0]); return; }
      if (finished) {
        var belowShare = candidates(BUTTON_RE, true);
        if (belowShare.length) { press(belowShare[0]); return; }
      }
    }
    // No Share / Copy button on screen. A finished game may keep it behind "See results" / "Stats"
    // (1.8.3): press that once, then look again. Never during play: only on an end screen.
    if (!finished) return;
    if (!openedResults && presses < MAX_PRESSES) {
      var opener = candidates(RESULTS_OPEN_RE);
      if (opener.length) {
        openedResults = true;
        press(opener[0], true);
        // The results view opens with an animation; its Share button comes after.
        setTimeout(function () { setProbing(false); }, 400);
        timers.push(setTimeout(scan, 900), setTimeout(scan, 2200), setTimeout(scan, 4000));
        return;
      }
    }
    // Check if the end screen already displays a pre-populated share grid / text box.
    if (presses < MAX_PRESSES) {
      var pre = prepopulatedShareText();
      if (pre) {
        presses += 1;
        try { post('{"kind":"press","n":' + quote(NONCE) + '}'); } catch (e) {}
        setProbing(true);
        send('probe', pre);
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

  // A game that's already finished shows its end screen soon after loading (Globle opens Stats at ~3.5s).
  function onReady() { loadedAt = Date.now(); schedule([2500, 4200, 6500]); }
  if (document.readyState === 'complete') onReady(); else window.addEventListener('load', onReady);
  // After each move: end screens may animate in quickly or after a 30–45s finish sequence (Farflung).
  function onInput() {
    lastInputAt = Date.now();
    schedule([1500, 4000, 8000, 18000, 32000, 46000]);
  }
  // Touch-scrolling on Android fires pointercancel + scroll (no pointerup): scan once scrolling settles
  // so scrolling a below-the-fold Share / Copy button into view also triggers detection.
  window.addEventListener('scroll', function () {
    if (sentAny || document.hidden || presses >= MAX_PRESSES) return;
    var now = Date.now();
    if (now - loadedAt > 180000 && now - lastInputAt > POST_INPUT_WATCH_MS) return;
    if (scrollTimer) clearTimeout(scrollTimer);
    scrollTimer = setTimeout(function () { scrollTimer = null; scan(); }, 300);
  }, { passive: true, capture: true });
  // A real touch or key ends probe mode at once (so the player's own tap copies / shares normally),
  // and a button the player pressed themselves is never pressed again. Synthetic probe events
  // (e.isTrusted === false) are ignored here so dispatchTouchClick doesn't cancel its own probe!
  window.addEventListener('pointerdown', function (e) {
    if (e && e.isTrusted === false) return;
    setProbing(false);
    try {
      var b = e.target && e.target.closest && e.target.closest(CLICKABLE);
      if (b && tried) tried.add(b);
    } catch (x) {}
  }, true);
  window.addEventListener('keydown', function (e) {
    if (e && e.isTrusted === false) return;
    setProbing(false);
  }, true);
  window.addEventListener('pointerup', function (e) {
    if (e && e.isTrusted === false) return;
    onInput();
  }, true);
  window.addEventListener('keyup', function (e) {
    if (e && e.isTrusted === false) return;
    onInput();
  }, true);
  // Late-appearing end screens (including 30–60s finish animations and pre-rendered modals revealed via
  // class/style/hidden/disabled changes).
  try {
    var pending = false;
    new MutationObserver(function (records) {
      if (sentAny || Date.now() - lastInputAt > POST_INPUT_WATCH_MS || pending) return;
      // An end screen either adds element nodes or reveals a pre-rendered modal/done card/share button;
      // a ticking game clock only swaps characterData/text nodes and is ignored (1.8.12).
      var relevant = false;
      for (var i = 0; i < records.length && !relevant; i++) {
        var rec = records[i];
        if (rec.type === 'childList') {
          var nodes = rec.addedNodes;
          for (var j = 0; j < nodes.length; j++) if (nodes[j].nodeType === 1) { relevant = true; break; }
        } else if (rec.type === 'attributes') {
          var t = rec.target;
          if (t && t.nodeType === 1) {
            var attr = rec.attributeName;
            if (attr === 'hidden' || attr === 'open' || attr === 'disabled' || attr === 'aria-disabled' ||
                t.tagName === 'DIALOG' || t.tagName === 'BUTTON' ||
                (t.getAttribute && t.getAttribute('role') === 'dialog') || REVEAL_ATTR_TARGET_RE.test(classAndId(t))) {
              relevant = true;
            } else if ((attr === 'class' || attr === 'style') && t.querySelector && t.querySelector('button, [role="button"]') &&
                       (t.textContent || '').length <= 600 && BUTTON_RE.test(t.textContent || '')) {
              relevant = true;
            }
          }
        }
      }
      if (!relevant) return;
      pending = true;
      mutationTimers.forEach(clearTimeout);
      mutationTimers = [
        setTimeout(function () { pending = false; scan(); }, 900),
        setTimeout(scan, 2500)
      ];
      // The document itself: this runs before the page's <html> element exists, and observing that
      // (null) threw, so late end screens were never seen (1.8.12).
    }).observe(document, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['class', 'style', 'hidden', 'open', 'aria-hidden', 'disabled', 'aria-disabled']
    });
  } catch (e) {}
})();
