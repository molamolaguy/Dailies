// Hide Cookie Banners (1.8.0): hides the cookie / consent pop-ups of the common consent tools on game
// pages and lets the page scroll again. Nothing is clicked or accepted: the banner is only hidden.
(function () {
  if (window.__dailiesCookieHide) return;
  window.__dailiesCookieHide = true;
  var SELECTORS = [
    '#onetrust-consent-sdk', '#onetrust-banner-sdk', '.onetrust-pc-dark-filter',
    '#CybotCookiebotDialog', '#CybotCookiebotDialogBodyUnderlay',
    '.fc-consent-root', '.fc-dialog-overlay',
    '#qc-cmp2-container', '.qc-cmp2-container',
    '#didomi-host', '.didomi-popup-backdrop',
    'div[id^="sp_message_container_"]', '.sp_veil',
    '#usercentrics-root', '#usercentrics-cmp-ui',
    '#truste-consent-track', '#truste-consent-content', '.truste_overlay', '.truste_box_overlay',
    '.osano-cm-window', '.osano-cm-dialog',
    '#cmpbox', '#cmpbox2', '.cmpboxBG',
    '#iubenda-cs-banner', '.iubenda-cs-overlay',
    '.cc-window.cc-banner', '.cc-window.cc-floating', '.cc-grower',
    '.cky-consent-container', '.cky-overlay', '.cky-modal',
    '#cookie-law-info-bar', '.cli-modal-backdrop',
    '#moove_gdpr_cookie_info_bar',
    '#cmplz-cookiebanner-container', '.cmplz-cookiebanner',
    '#termly-code-snippet-support',
    '#klaro', '.klaro .cookie-notice',
    '#axeptio_overlay',
    '#tarteaucitronRoot',
    '.cookiefirst-root',
    '#hs-eu-cookie-confirmation',
    '#gdpr-consent-tool-wrapper', '#_evidon_banner', '.evidon-banner', '#consent_blackbar',
    '#ccc', '#ccc-overlay',
    '#cookie-banner', '#cookieBanner', '#cookie-notice', '#cookieNotice', '#cookie-consent-banner', '#cookieConsent',
    '.cookie-banner', '.cookie-consent-banner', '.cookie-notice-container', '.js-cookie-consent',
    '#gdpr-cookie-message', '#consent-banner', '.consent-banner'
  ];
  // Classes these tools put on <html>/<body> to stop the page scrolling behind the banner.
  var LOCK_CSS = 'html.sp-message-open, html.sp-message-open body, body.didomi-popup-open, html.didomi-popup-open,'
    + ' body.qc-cmp-showing, body.qc-cmp-ui-showing, body.cky-modal-open, body.cli-barmodal-open, body.iubenda-cs-blocking,'
    + ' body.osano-cm-no-scroll, html.cmplz-banner-open, body.tarteaucitron-modal-open, html.klaro-open'
    + ' { overflow: auto !important; }';
  var css = SELECTORS.join(',\n') + ' { display: none !important; visibility: hidden !important; pointer-events: none !important; }\n' + LOCK_CSS;
  function addStyle() {
    var root = document.head || document.documentElement;
    if (!root) return false;
    if (document.getElementById('dailies-cookie-hide')) return true;
    var s = document.createElement('style');
    s.id = 'dailies-cookie-hide';
    s.textContent = css;
    root.appendChild(s);
    return true;
  }
  if (!addStyle()) document.addEventListener('readystatechange', addStyle, { once: true });
  // Some tools set overflow:hidden straight on <html>/<body>: undo that once their banner is (hidden) on the page.
  var joined = SELECTORS.join(',');
  function unlock() {
    try {
      if (!document.querySelector(joined)) return;
      [document.documentElement, document.body].forEach(function (el) {
        if (el && el.style && el.style.overflow === 'hidden') el.style.overflow = '';
      });
    } catch (e) {}
  }
  [800, 2000, 4000, 8000].forEach(function (ms) { setTimeout(function () { addStyle(); unlock(); }, ms); });
})();
