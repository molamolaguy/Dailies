// Pull Down to Refresh (1.8.0): at the top of a game page, pull down and let go to reload it.
// Decided on the page itself, so it never fights a game's own drags: it only starts when the page is
// scrolled to the top, the finger isn't on something the game drags (a canvas, a map, a slider,
// anything with touch-action), and the game didn't take the touch (preventDefault).
(function () {
  if (window.top !== window || window.__dailiesPull) return;
  window.__dailiesPull = true;
  var COLOR = '__DAILIES_COLOR__';
  var START = 14, TRIGGER = 86, MAX = 130;
  var host = null, ring = null, arrow = null, pull = null;

  function ui() {
    if (host) return true;
    if (!document.documentElement) return false;
    host = document.createElement('div');
    host.setAttribute('aria-hidden', 'true');
    host.style.cssText = 'position:fixed;left:0;right:0;top:0;height:0;z-index:2147483647;pointer-events:none;';
    var root = host.attachShadow ? host.attachShadow({ mode: 'closed' }) : host;
    root.innerHTML = '<style>.r{position:absolute;left:50%;top:0;width:40px;height:40px;margin-left:-20px;border-radius:50%;'
      + 'background:#fff;box-shadow:0 2px 10px rgba(0,0,0,.3);display:flex;align-items:center;justify-content:center;'
      + 'transform:translateY(-48px);opacity:0;transition:none}'
      + '.a{width:22px;height:22px;border-radius:50%;border:3px solid ' + COLOR + ';border-top-color:transparent;box-sizing:border-box}'
      + '.r.go .a{border-color:' + COLOR + ';border-top-color:transparent}'
      + '.r.spin .a{animation:s .8s linear infinite}@keyframes s{to{transform:rotate(360deg)}}</style>'
      + '<div class="r"><div class="a"></div></div>';
    ring = root.querySelector('.r');
    arrow = root.querySelector('.a');
    document.documentElement.appendChild(host);
    return true;
  }
  function atTop() {
    var se = document.scrollingElement || document.documentElement;
    return (window.scrollY || 0) <= 0 && (!se || se.scrollTop <= 0);
  }
  function blocked(el) {
    for (var n = el; n && n !== document.documentElement; n = n.parentElement) {
      if (n.nodeType !== 1) continue;
      var tag = n.tagName;
      if (tag === 'CANVAS' || tag === 'SVG' || tag === 'svg' || tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT'
        || tag === 'VIDEO' || tag === 'IFRAME' || n.isContentEditable || n.getAttribute('draggable') === 'true') return true;
      var cs = window.getComputedStyle(n);
      if (cs.touchAction && cs.touchAction !== 'auto' && cs.touchAction !== 'manipulation' && cs.touchAction.indexOf('pan-y') < 0) return true;
      // Inside a scrolled list: pulling scrolls the list back up instead.
      if ((cs.overflowY === 'auto' || cs.overflowY === 'scroll') && n.scrollHeight > n.clientHeight + 1 && n.scrollTop > 0) return true;
    }
    return false;
  }
  function show(d) {
    if (!ui()) return;
    var p = Math.min(1, d / TRIGGER);
    ring.className = 'r' + (d >= TRIGGER ? ' go' : '');
    ring.style.transition = 'none';
    ring.style.opacity = String(Math.min(1, p * 1.4));
    ring.style.transform = 'translateY(' + (Math.min(d, MAX) * 0.6 - 40) + 'px)';
    arrow.style.transform = 'rotate(' + Math.round(p * 300) + 'deg)';
    arrow.style.opacity = String(0.4 + p * 0.6);
  }
  function hide() {
    if (!ring) return;
    ring.style.transition = 'transform .2s ease-in, opacity .2s';
    ring.style.transform = 'translateY(-48px)';
    ring.style.opacity = '0';
  }
  window.addEventListener('touchstart', function (e) {
    pull = null;
    if (!e.touches || e.touches.length !== 1 || !atTop() || blocked(e.target)) return;
    pull = { x: e.touches[0].clientX, y: e.touches[0].clientY, d: 0, on: false };
  }, { passive: true });
  // Bubble phase on window: runs after the game's own handlers, so a drag it handles is seen as taken.
  window.addEventListener('touchmove', function (e) {
    if (!pull) return;
    if (e.defaultPrevented || !e.touches || e.touches.length !== 1 || !atTop()) { pull = null; hide(); return; }
    var dx = e.touches[0].clientX - pull.x, dy = e.touches[0].clientY - pull.y;
    if (!pull.on) {
      if (Math.abs(dx) > Math.abs(dy) || dy < -4) { pull = null; return; }
      if (dy < START) return;
      pull.on = true;
    }
    pull.d = Math.max(0, dy - START);
    show(pull.d);
  }, { passive: true });
  function end() {
    if (!pull) return;
    var go = pull.on && pull.d >= TRIGGER;
    pull = null;
    if (!go) { hide(); return; }
    ring.className = 'r go spin';
    ring.style.transition = 'transform .2s ease-out';
    ring.style.transform = 'translateY(24px)';
    try { navigator.vibrate && navigator.vibrate(10); } catch (e) {}
    setTimeout(function () { location.reload(); }, 120);
  }
  window.addEventListener('touchend', end, { passive: true });
  window.addEventListener('touchcancel', function () { pull = null; hide(); }, { passive: true });
})();
