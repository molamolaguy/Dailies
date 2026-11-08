/*
 * Dailies — blob: download support, injected into every frame at document start (GameActivity).
 *
 * A "save image" button usually does `a.href = URL.createObjectURL(blob); a.click();
 * URL.revokeObjectURL(a.href)`. By the time Android reports the download the URL is already revoked,
 * and only the frame that made it could read it anyway. So this keeps each Blob for a minute after
 * its URL is revoked, says "hi" to the app (`DailesDownload` channel) the first time the frame makes
 * a blob: URL, and answers the app's {t:'get', url, token} with the Blob's bytes. The page's own
 * calls behave exactly as before.
 */
(function () {
  'use strict';
  if (window.__dailesDownloadHook) return;
  try { Object.defineProperty(window, '__dailesDownloadHook', { value: true }); } catch (e) { window.__dailesDownloadHook = true; }
  var channel = window.DailesDownload;
  var U = window.URL;
  if (!channel || typeof channel.postMessage !== 'function' || !U || typeof U.createObjectURL !== 'function' ||
      typeof Map !== 'function' || typeof Blob !== 'function') return;

  var KEEP_AFTER_REVOKE_MS = 60000;
  var blobs = new Map();
  var greeted = false;
  var origCreate = U.createObjectURL;
  var origRevoke = U.revokeObjectURL;

  function post(obj) { try { channel.postMessage(JSON.stringify(obj)); } catch (e) {} }

  U.createObjectURL = function createObjectURL(obj) {
    var url = origCreate.apply(this, arguments);
    try {
      if (obj instanceof Blob && typeof url === 'string') {
        blobs.set(url, obj);
        if (!greeted) { greeted = true; post({ t: 'hi' }); }
      }
    } catch (e) {}
    return url;
  };
  if (typeof origRevoke === 'function') {
    U.revokeObjectURL = function revokeObjectURL(url) {
      var r = origRevoke.apply(this, arguments);
      try {
        if (blobs.has(url)) setTimeout(function () { blobs.delete(url); }, KEEP_AFTER_REVOKE_MS);
      } catch (e) {}
      return r;
    };
  }

  channel.onmessage = function (ev) {
    var m;
    try { m = JSON.parse(ev.data); } catch (e) { return; }
    if (!m || m.t !== 'get' || typeof m.url !== 'string' || typeof m.token !== 'string') return;
    var blob = blobs.get(m.url);
    var found;
    if (blob) found = Promise.resolve(blob);
    else if (m.url.indexOf('blob:' + location.origin + '/') === 0) found = fetch(m.url).then(function (r) { return r.blob(); });
    else return; // another frame's blob
    found.then(function (b) {
      if (typeof m.max === 'number' && b.size > m.max) { post({ token: m.token, error: 'size' }); return; }
      var fr = new FileReader();
      fr.onload = function () { post({ token: m.token, type: b.type, data: fr.result }); };
      fr.onerror = function () { post({ token: m.token, error: 'read' }); };
      fr.readAsDataURL(b);
    }, function () {
      // Not readable here; another frame of this origin may still hold it (the app times out otherwise).
      if (blob) post({ token: m.token, error: 'fetch' });
    });
  };
})();
