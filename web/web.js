/*
 * Dailies web app: the parts the Android app does natively, done with browser features.
 * Loaded only by the web build (web/build.mjs adds it to index.html); it does nothing inside the app.
 *
 *  - Games open in a new tab. Coming back to Dailies counts as "back from the game", so the hub asks
 *    "Finished?" with Solved / Missed / Paste, as the app does after its game player.
 *  - Offline: a service worker (sw.js) keeps the hub working without a connection.
 *  - Storage: asks the browser to keep the hub's data (navigator.storage.persist).
 *  - Install hint: on iPhone / iPad Safari, how to add Dailies to the Home Screen; elsewhere, the
 *    browser's own Install prompt.
 */
(function () {
  'use strict';
  if (window.AndroidBridge) return;

  const PENDING_KEY = 'dailies_web_open_game';
  const HINT_KEY = 'dailies_web_install_hint_dismissed';
  const MAX_AWAY_MS = 6 * 3600000;

  const store = {
    get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set(k, v) { try { if (v === null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch (e) {} }
  };
  function readPending() {
    try { const p = JSON.parse(store.get(PENDING_KEY) || 'null'); return p && typeof p.id === 'string' ? p : null; } catch (e) { return null; }
  }
  function writePending(p) { store.set(PENDING_KEY, p ? JSON.stringify(p) : null); }

  // ---- Playing a game in another tab, then coming back ----
  function returned(p) {
    writePending(null);
    const away = Date.now() - Number(p.openedAt || 0);
    if (!(away > 0) || away > MAX_AWAY_MS) return;
    const report = () => {
      if (typeof window.onReturnFromGame === 'function') window.onReturnFromGame(p.id, false, away, '', p.openedAt);
    };
    // The hub may still be starting (the page was reloaded while away).
    if (typeof window.onReturnFromGame === 'function') setTimeout(report, 50);
    else window.addEventListener('load', () => setTimeout(report, 300), { once: true });
  }

  window.DailiesWeb = {
    isStandalone: () => Boolean((window.matchMedia && window.matchMedia('(display-mode: standalone)').matches) || window.navigator.standalone),
    openGame(id, url) {
      writePending({ id: String(id), openedAt: Date.now(), left: false });
      window.open(url, '_blank', 'noopener,noreferrer');
    }
  };

  document.addEventListener('visibilitychange', () => {
    const p = readPending();
    if (!p) return;
    if (document.visibilityState === 'hidden') {
      if (!p.left) writePending(Object.assign(p, { left: true }));
    } else if (p.left) {
      returned(p);
    }
  });
  // Opened again after the browser closed the page while the game was on screen (common on iPhone).
  const atStart = readPending();
  if (atStart && atStart.left) returned(atStart);
  else if (atStart) writePending(null);

  // ---- Offline + keeping the data ----
  if ('serviceWorker' in navigator && (location.protocol === 'https:' || location.hostname === 'localhost' || location.hostname === '127.0.0.1')) {
    window.addEventListener('load', () => { navigator.serviceWorker.register('sw.js').catch(() => {}); });
  }
  try { if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {}); } catch (e) {}

  // ---- Install hint ----
  let deferredPrompt = null;
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferredPrompt = e;
    showHint();
  });
  function isIosSafari() {
    const ua = navigator.userAgent || '';
    const ios = /iPhone|iPad|iPod/.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
    return ios && !window.DailiesWeb.isStandalone();
  }
  function showHint() {
    if (store.get(HINT_KEY) || window.DailiesWeb.isStandalone() || document.getElementById('webInstallHint')) return;
    if (!deferredPrompt && !isIosSafari()) return;
    const bar = document.createElement('div');
    bar.id = 'webInstallHint';
    bar.className = 'web-install-hint';
    bar.setAttribute('role', 'status');
    bar.innerHTML = deferredPrompt
      ? '<span class="wih-text"><strong>Install Dailies</strong> for its own icon and a full-screen view.</span><button type="button" class="wih-go">Install</button><button type="button" class="wih-x" aria-label="Dismiss">✕</button>'
      : '<span class="wih-text"><strong>Add Dailies to your Home Screen:</strong> tap <span class="wih-share" aria-label="Share">'
        + '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v12"/><path d="M8 7l4-4 4 4"/><path d="M5 12v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-7"/></svg>'
        + '</span> Share, then <strong>Add to Home Screen</strong>.</span><button type="button" class="wih-x" aria-label="Dismiss">✕</button>';
    document.body.appendChild(bar);
    bar.querySelector('.wih-x').addEventListener('click', () => { store.set(HINT_KEY, '1'); bar.remove(); });
    const go = bar.querySelector('.wih-go');
    if (go) go.addEventListener('click', async () => {
      bar.remove();
      try { deferredPrompt.prompt(); await deferredPrompt.userChoice; } catch (e) {}
      deferredPrompt = null;
    });
  }
  window.addEventListener('load', () => setTimeout(showHint, 1500));
  window.addEventListener('appinstalled', () => { const b = document.getElementById('webInstallHint'); if (b) b.remove(); });
})();
