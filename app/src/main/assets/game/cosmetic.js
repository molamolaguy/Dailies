/*
 * Dailies — element hiding for the game player's ad blocker, injected at document start.
 *
 * Asks the app (origin-scoped `DailesAdBlock` channel) for this site's hiding CSS, then reports the
 * class/id names present on the page so only matching generic rules come back. Only adds a <style>
 * element; never removes or edits the page's own nodes.
 */
(function () {
  'use strict';
  if (window.__dailesCosmetic) return;
  try { Object.defineProperty(window, '__dailesCosmetic', { value: true }); } catch (e) { window.__dailesCosmetic = true; }
  var channel = window.DailesAdBlock;
  if (!channel || typeof channel.postMessage !== 'function') return;

  var host = location.hostname;
  var style = null;
  var seen = Object.create(null);
  var pending = [];
  var flushTimer = 0;
  var disabled = false;
  var cssText = '';
  var observer = null;

  function ensureStyle() {
    if (!style || !style.isConnected) {
      style = document.createElement('style');
      style.setAttribute('data-dailes-adblock', '');
      (document.head || document.documentElement).appendChild(style);
    }
    return style;
  }
  function addCss(css) {
    if (!css || disabled) return;
    cssText += css;
    try {
      if (style && style.isConnected) style.appendChild(document.createTextNode(css));
      else restore();
    } catch (e) {}
  }
  // A page that removed our <style> (a head re-render, say) gets the full set back. Capped, so a
  // page that keeps removing it can't make the two of us loop forever.
  var restores = 0;
  function restore() {
    if (disabled || !cssText || (style && style.isConnected) || restores > 50) return;
    restores++;
    try {
      ensureStyle();
      style.textContent = cssText;
    } catch (e) {}
  }
  channel.onmessage = function (ev) {
    try {
      var m = JSON.parse(ev.data);
      if (m && m.disabled) {
        // Blocking is off for this site: stop scanning and remove anything we added.
        disabled = true;
        pending.length = 0;
        if (observer) observer.disconnect();
        if (style && style.parentNode) style.parentNode.removeChild(style);
        return;
      }
      if (m && m.css) addCss(m.css);
    } catch (e) {}
  };

  function flush() {
    flushTimer = 0;
    if (disabled || !pending.length) return;
    var batch = pending.splice(0, 2000);
    try { channel.postMessage(JSON.stringify({ t: 'tokens', host: host, tokens: batch })); } catch (e) {}
    if (pending.length) flushTimer = setTimeout(flush, 0);
  }
  function note(tok) {
    if (disabled || seen[tok]) return;
    seen[tok] = 1;
    pending.push(tok);
    if (!flushTimer) flushTimer = setTimeout(flush, 30);
  }
  function scan(el) {
    if (!el || el.nodeType !== 1) return;
    if (el.id) note('#' + el.id);
    var cl = el.classList;
    if (cl) for (var i = 0; i < cl.length; i++) note('.' + cl[i]);
  }
  function scanTree(root) {
    scan(root);
    if (!root.querySelectorAll) return;
    var all = root.querySelectorAll('[id],[class]');
    for (var i = 0; i < all.length; i++) scan(all[i]);
  }

  // A per-document nonce: the app answers each document's hello once (a reload or the next page on
  // the same host is a new document and gets its CSS again).
  var nonce = Math.random().toString(36).slice(2) + Date.now().toString(36);
  try { channel.postMessage(JSON.stringify({ t: 'hello', host: host, n: nonce })); } catch (e) {}
  try {
    observer = new MutationObserver(function (records) {
      if (cssText && !(style && style.isConnected)) restore();
      for (var i = 0; i < records.length; i++) {
        var r = records[i];
        if (r.type === 'attributes') scan(r.target);
        else for (var j = 0; j < r.addedNodes.length; j++) scanTree(r.addedNodes[j]);
      }
    });
    observer.observe(document.documentElement || document, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'] });
  } catch (e) {}
  if (document.documentElement) scanTree(document.documentElement);
  document.addEventListener('DOMContentLoaded', function () { scanTree(document.documentElement); }, { once: true });
})();
