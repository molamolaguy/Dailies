/*
 * Dailies — finds a finished game's result without the player pressing Share (GameActivity).
 *
 * When the page shows an end screen ("Next puzzle in…", "You solved it", statistics…) next to a
 * Share / Copy button, Dailies presses that button once while share-hook.js is in probe mode: the
 * result text the game produces is captured, and nothing is copied or shared. Each button is pressed
 * at most once per page, and at most three per page in total. Links (e.g. "Share on X") are never
 * pressed. Turned off with Settings → Find Results Automatically.
 */
(function () {
  'use strict';
  if (window.__dailesFinishProbe) return;
  try { Object.defineProperty(window, '__dailesFinishProbe', { value: true }); } catch (e) { window.__dailesFinishProbe = true; }
  var setProbing = window.__dailesSetProbing;
  if (typeof setProbing !== 'function') return;

  var MAX_PRESSES = 3;
  var presses = 0;
  var tried = typeof WeakSet === 'function' ? new WeakSet() : null;
  var lastInputAt = 0;
  var timers = [];

  // Words a game's end screen shows (results, stats, a countdown to the next puzzle).
  var FINISHED_RE = /(next (puzzle|game|word|challenge|dive|round|grid|board|level)|next [a-z]+ (in|at|tomorrow)|new [a-z]+ (in|at)|come back tomorrow|see you tomorrow|you('ve| have)? (won|solved|got it|found|did it|win|completed|finished|crushed)|puzzle (complete|solved|done)|(solved|completed|complete)!|well done|great job|congrat|splendid|magnificent|impressive|genius|phew|nice work|crowned|champion|flawless|game over|out of (guesses|lives|tries)|better luck|the answer was|today'?s (answer|word|country)|statistics|your (score|time|rank|results)|final score|total score|you scored|streak)/i;
  // A countdown to the next puzzle ("05:12:44"), which end screens show and games rarely show mid-play.
  var COUNTDOWN_RE = /(^|[^0-9:])[0-2]?[0-9]:[0-5][0-9]:[0-5][0-9]([^0-9:]|$)/;
  var BUTTON_RE = /(^|[^a-z])(share|copy)([^a-z]|$)/i;
  var AVOID_RE = /(link|invite|friend|twitter|facebook|whatsapp|reddit|discord|instagram|tiktok|threads|bluesky|mastodon|email|e-mail|embed|code|url)/i;

  function label(el) {
    var t = (el.getAttribute('aria-label') || el.getAttribute('title') || el.innerText || el.textContent || '').trim();
    return t.replace(/\s+/g, ' ');
  }
  function visible(el) {
    try {
      if (el.disabled || el.getAttribute('aria-disabled') === 'true') return false;
      var r = el.getBoundingClientRect();
      if (r.width < 8 || r.height < 8) return false;
      if (r.bottom < 0 || r.top > (window.innerHeight || 0) + 200) return false;
      var cs = window.getComputedStyle(el);
      return cs.visibility !== 'hidden' && cs.display !== 'none' && Number(cs.opacity) > 0.05;
    } catch (e) { return false; }
  }
  function candidates() {
    var out = [];
    var nodes = document.querySelectorAll('button, [role="button"], a');
    for (var i = 0; i < nodes.length && out.length < 4; i++) {
      var el = nodes[i];
      if (tried && tried.has(el)) continue;
      if (el.tagName === 'A') {
        var href = (el.getAttribute('href') || '').trim();
        if (href && href !== '#' && href.toLowerCase().indexOf('javascript:') !== 0) continue; // a real link: never follow it
      }
      var text = label(el);
      if (!text || text.length > 40 || !BUTTON_RE.test(text) || AVOID_RE.test(text)) continue;
      if (!visible(el)) continue;
      out.push(el);
    }
    return out;
  }
  function looksFinished() {
    try {
      var text = (document.body && document.body.innerText) || '';
      var head = text.slice(0, 20000);
      return FINISHED_RE.test(head) || COUNTDOWN_RE.test(head);
    } catch (e) { return false; }
  }
  function press(el) {
    if (tried) tried.add(el);
    presses += 1;
    setProbing(true);
    try { el.click(); } catch (e) {}
    // Games build the text right away or after a short animation; probe mode ends after that.
    setTimeout(function () { setProbing(false); }, 1500);
  }
  function scan() {
    if (presses >= MAX_PRESSES || document.hidden) return;
    if (!looksFinished()) return;
    var list = candidates();
    if (list.length) press(list[0]);
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
  // A real touch or key ends probe mode at once, so the player's own tap copies / shares normally.
  window.addEventListener('pointerdown', function () { setProbing(false); }, true);
  window.addEventListener('keydown', function () { setProbing(false); }, true);
  window.addEventListener('pointerup', onInput, true);
  window.addEventListener('keyup', onInput, true);
  // Late-appearing end screens (only shortly after the player did something).
  try {
    var pending = null;
    new MutationObserver(function () {
      if (Date.now() - lastInputAt > 20000 || pending) return;
      pending = setTimeout(function () { pending = null; scan(); }, 900);
    }).observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) {}
})();
