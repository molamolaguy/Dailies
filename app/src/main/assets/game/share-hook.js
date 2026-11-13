/*
 * Dailies — share/copy capture, injected into game pages at document start (GameActivity).
 *
 * Observes the ways a game puts its result on the clipboard or share sheet and forwards the text to
 * the app over the origin-scoped `DailesShare` channel. Every wrapper calls the original API and
 * returns its result unchanged, so the game behaves exactly as before; failures here are swallowed.
 */
(function () {
  'use strict';
  if (window.__dailesShareHook) return;
  try { Object.defineProperty(window, '__dailesShareHook', { value: true }); } catch (e) { window.__dailesShareHook = true; }
  var channel = window.DailesShare;
  if (!channel || typeof channel.postMessage !== 'function') return;

  var last = '';
  // While Dailies quietly presses the game's own Share / Copy button to read a finished result
  // (finish-probe.js), the text is captured but nothing is copied or shared.
  var probing = false;
  try {
    Object.defineProperty(window, '__dailesSetProbing', { value: function (on) { probing = Boolean(on); } });
  } catch (e) {}
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
      channel.postMessage(JSON.stringify({ via: probing ? 'probe' : via, text: t.slice(0, 8000) }));
      // Got it: anything the game does after this is real again.
      if (probing) setTimeout(function () { probing = false; }, 0);
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
})();
