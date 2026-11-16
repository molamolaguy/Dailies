/*
 * Dailies game player (1.8.7): drop-down lists on game pages open the app's own menu instead of Android's
 * picker, in the app's colours. Single-choice lists only (a list showing several rows, or allowing several
 * choices, is left to the page). Choosing sets the list's value and sends the same input / change events a
 * person's choice does, so the game reacts as usual. The menu lives in a closed shadow root, so the page's
 * styles can't reach it and it can't be read by the page.
 */
(function () {
  'use strict';
  if (window.__dailiesSelectMenu) return;
  Object.defineProperty(window, '__dailiesSelectMenu', { value: true });
  var C = __DAILIES_MENU_COLORS__;

  var host = null;
  var root = null;
  var open = null; // { select, close }

  function ensureHost() {
    if (host && host.isConnected) return;
    host = document.createElement('dailies-select-menu');
    host.style.cssText = 'all:initial;position:fixed;inset:0;z-index:2147483647;display:none;';
    root = host.attachShadow({ mode: 'closed' });
    var style = document.createElement('style');
    style.textContent = [
      ':host{all:initial}',
      '.scrim{position:fixed;inset:0;background:rgba(0,0,0,' + (C.light ? '0.18' : '0.35') + ');-webkit-tap-highlight-color:transparent}',
      '.menu{position:fixed;box-sizing:border-box;overflow-y:auto;overscroll-behavior:contain;padding:6px;border-radius:18px;',
      'background:' + C.bg + ';border:1px solid ' + C.outline + ';box-shadow:0 18px 40px -12px rgba(0,0,0,0.6);',
      'font:600 14px/1.3 "Google Sans Flex","Google Sans",Roboto,system-ui,sans-serif;color:' + C.fg + ';',
      'animation:in 140ms cubic-bezier(0.2,0,0,1)}',
      '@keyframes in{from{opacity:0;transform:translateY(-4px) scale(0.98)}to{opacity:1;transform:none}}',
      '.group{padding:10px 12px 4px;font-size:11.5px;font-weight:700;letter-spacing:0.02em;opacity:0.65;text-transform:uppercase}',
      '.item{display:flex;align-items:center;justify-content:space-between;gap:10px;width:100%;box-sizing:border-box;margin:0;',
      'padding:11px 12px;border:0;border-radius:12px;background:transparent;color:inherit;font:inherit;text-align:left;cursor:pointer;',
      '-webkit-tap-highlight-color:transparent}',
      '.item:active{background:' + C.press + '}',
      '.item.on{background:' + C.sel + ';color:' + C.selFg + '}',
      '.item[disabled]{opacity:0.4;cursor:default}',
      '.item .tick{flex-shrink:0;width:16px;height:16px;color:' + C.accent + '}',
      '.item.on .tick{color:' + C.selFg + '}',
      '.label{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}'
    ].join('');
    root.appendChild(style);
    (document.body || document.documentElement).appendChild(host);
  }

  function optionText(o) { return (o.label || o.textContent || '').replace(/\s+/g, ' ').trim(); }

  function choose(select, index) {
    if (index < 0 || select.selectedIndex === index) return;
    select.selectedIndex = index;
    select.dispatchEvent(new Event('input', { bubbles: true }));
    select.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function show(select) {
    ensureHost();
    while (root.childNodes.length > 1) root.removeChild(root.lastChild);
    var scrim = document.createElement('div');
    scrim.className = 'scrim';
    var menu = document.createElement('div');
    menu.className = 'menu';
    menu.setAttribute('role', 'listbox');
    var tick = '<svg class="tick" viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" d="M5 12.5l4.5 4.5L19 7.5"/></svg>';
    var current = null;
    var lastGroup = null;
    for (var i = 0; i < select.options.length; i++) {
      var o = select.options[i];
      if (o.hidden) continue;
      var g = o.parentElement && o.parentElement.tagName === 'OPTGROUP' ? o.parentElement : null;
      if (g !== lastGroup) {
        lastGroup = g;
        if (g && g.label) { var h = document.createElement('div'); h.className = 'group'; h.textContent = g.label; menu.appendChild(h); }
      }
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'item' + (i === select.selectedIndex ? ' on' : '');
      b.setAttribute('role', 'option');
      b.setAttribute('aria-selected', String(i === select.selectedIndex));
      if (o.disabled || (g && g.disabled)) b.disabled = true;
      var span = document.createElement('span');
      span.className = 'label';
      span.textContent = optionText(o);
      b.appendChild(span);
      if (i === select.selectedIndex) { b.insertAdjacentHTML('beforeend', tick); current = b; }
      b.dataset.index = String(i);
      menu.appendChild(b);
    }
    root.appendChild(scrim);
    root.appendChild(menu);
    host.style.display = 'block';

    // Under the list (or above it when there's more room there), as wide as the list, within the screen.
    var r = select.getBoundingClientRect();
    var vw = window.innerWidth, vh = window.innerHeight, m = 8;
    var width = Math.min(vw - m * 2, Math.max(r.width, 200));
    var left = Math.min(Math.max(m, r.left), vw - m - width);
    var below = vh - r.bottom - m, above = r.top - m;
    var natural = menu.scrollHeight;
    var placeBelow = below >= Math.min(natural, 280) || below >= above;
    var maxH = Math.max(160, (placeBelow ? below : above) - 4);
    menu.style.width = width + 'px';
    menu.style.left = left + 'px';
    menu.style.maxHeight = maxH + 'px';
    if (placeBelow) menu.style.top = Math.min(r.bottom + 4, vh - m - Math.min(natural, maxH)) + 'px';
    else menu.style.bottom = (vh - r.top + 4) + 'px';
    if (current) menu.scrollTop = Math.max(0, current.offsetTop - menu.clientHeight / 2 + current.offsetHeight / 2);

    function close() {
      if (!open) return;
      open = null;
      host.style.display = 'none';
      while (root.childNodes.length > 1) root.removeChild(root.lastChild);
      window.removeEventListener('resize', close);
      window.removeEventListener('popstate', close);
    }
    scrim.addEventListener('click', close);
    menu.addEventListener('click', function (e) {
      var b = e.target.closest ? e.target.closest('.item') : null;
      if (!b || b.disabled) return;
      var idx = Number(b.dataset.index);
      close();
      choose(select, idx);
    });
    window.addEventListener('resize', close);
    window.addEventListener('popstate', close);
    open = { select: select, close: close };
  }

  function menuSelect(t) {
    var s = t && t.closest ? t.closest('select') : null;
    if (!s || s.multiple || s.size > 1 || s.disabled) return null;
    return s;
  }

  // The page's own picker opens on mousedown (a tap sends one too): stop that and open ours on the tap.
  document.addEventListener('mousedown', function (e) {
    if (e.button !== 0 || !menuSelect(e.target)) return;
    e.preventDefault();
  }, true);
  document.addEventListener('click', function (e) {
    var s = menuSelect(e.target);
    if (!s) return;
    e.preventDefault();
    try { s.focus({ preventScroll: true }); } catch (err) {}
    show(s);
  }, true);
  // Keyboard (Enter / Space / Alt+Down on a focused list) opens it too.
  document.addEventListener('keydown', function (e) {
    var s = menuSelect(e.target);
    if (!s) { if (open && e.key === 'Escape') open.close(); return; }
    if (e.key === 'Enter' || e.key === ' ' || (e.altKey && e.key === 'ArrowDown')) { e.preventDefault(); show(s); }
  }, true);
})();
