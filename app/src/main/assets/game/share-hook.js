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

  // 5) A Share button that opens a window (a "share on …" page) opens nothing while probing.
  try {
    var origOpen = window.open;
    if (typeof origOpen === 'function') {
      window.open = mask(function open() {
        if (probing) return null;
        return origOpen.apply(this, arguments);
      }, origOpen);
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
  var presses = 0;
  var tried = typeof WeakSet === 'function' ? new WeakSet() : null;
  var lastInputAt = 0;
  var timers = [];

  // Words a game's end screen shows (a result, the answer, when the next puzzle comes). Not words
  // that also sit on a game's screen while playing ("Statistics", "Streak", "Score").
  var FINISHED_RE = /(next (puzzle|game|word|challenge|dive|round|grid|board|level) (in|at|tomorrow|available)|next [a-z]+ (in|at|tomorrow)|new [a-z]+ (in|at)|come back tomorrow|see you tomorrow|you('ve| have)? (won|solved|got it|found it|did it|completed|finished|crushed)|puzzle (complete|solved|done)|(solved|completed)!|well done|great job|congratulations|splendid|magnificent|phew|nice work|flawless|game over|out of (guesses|lives|tries)|better luck|the answer was|final score|you scored)/i;
  // A countdown to the next puzzle ("Next puzzle in 05:12:44"), not a game's own timer.
  var COUNTDOWN_RE = /(next|new|until|tomorrow|available|refresh|resets?)[^\n]{0,40}?[0-2]?[0-9]:[0-5][0-9]:[0-5][0-9]/i;
  var BUTTON_RE = /(^|[^a-z])(share|copy)([^a-z]|$)/i;
  // "Copy result", "Share my score", "Copy results 🦐": a button that only an end screen has, so it
  // counts as finished on its own (Krillion shows no other end-screen wording).
  var RESULT_BUTTON_RE = /(share|copy)[ \t]*(my|your|the|today'?s)?[ \t]*(results?|scores?|stats|grid|dive)/i;
  // Buttons, and the clickable divs / spans some games use instead.
  var CLICKABLE = 'button, [role="button"], a, [onclick], div[class*="button" i], div[class*="btn" i], span[class*="btn" i], div[tabindex="0"]';
  var AVOID_RE = /(link|invite|friend|twitter|(^|[^a-z])x([^a-z]|$)|facebook|whatsapp|messenger|telegram|sms|text message|reddit|discord|instagram|tiktok|threads|bluesky|mastodon|linkedin|email|e-mail|embed|code|url|image|picture|photo|screenshot|download|save)/i;

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
      return Boolean(hit && (hit === el || el.contains(hit)));
    } catch (e) { return false; }
  }
  function candidates() {
    var out = [];
    var nodes = document.querySelectorAll(CLICKABLE);
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
      if (!text || text.length > 40 || !BUTTON_RE.test(text) || AVOID_RE.test(text)) continue;
      if (!pressable(el)) continue;
      out.push(el);
    }
    return out;
  }
  function looksFinished() {
    try {
      var head = ((document.body && document.body.innerText) || '').slice(0, 20000);
      return FINISHED_RE.test(head) || COUNTDOWN_RE.test(head);
    } catch (e) { return false; }
  }
  function press(el) {
    if (tried) tried.add(el);
    presses += 1;
    // Tells the app a press is coming, so it ignores windows, other apps and downloads it may start.
    try { post('{"kind":"press","n":' + quote(NONCE) + '}'); } catch (e) {}
    setProbing(true);
    try { el.click(); } catch (e) {}
  }
  function scan() {
    // Stop once a result went out (found, or shared by the player) or after a few presses.
    if (sentAny || presses >= MAX_PRESSES || document.hidden) return;
    var list = candidates(); // cheap; the page text is only read when there's a button to press
    if (!list.length) return;
    var strong = list.filter(function (el) { return RESULT_BUTTON_RE.test(label(el)); });
    if (strong.length) { press(strong[0]); return; }
    if (!looksFinished()) return;
    press(list[0]);
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
