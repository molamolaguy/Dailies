// Gold app icon (Brushed Champagne): pure-vector diagonal specular sweep across smooth champagne gold
// with a razor-clean hairline rim, centered elevation shadow, warm golden aura, and obsidian backdrop.
// Run from the repo root: node design/icons/gold-icon.js (uses Playwright if installed, or system Chromium via CDP).
// `node design/icons/gold-icon.js preview <dir>` writes preview-gold.png only.
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const ROOT = path.join(__dirname, '../../app/src/main/') + '/';
const htmlPath = ROOT + 'assets/index.html';
const html = fs.readFileSync(htmlPath, 'utf8');
const FLOWER = html.match(/const ICON_FLOWER = '([^']+)'/)[1];
const MARK = html.match(/const ICON_MARK = '([^']+)'/)[1];
const SPARKS = html.match(/const ICON_SPARKS = '([^']+)'/)[1];

// The builder, also written into index.html (so the hub draws exactly this). Coordinates are the
// 108-unit adaptive-icon grid; `k` is the star's scale around the centre.
const BUILDER = String.raw`function goldIconSvg(o) {
      // o: { id, size, viewBox, k, bg: bool, star: bool, clip: '' | svg clip shape, p: palette (METAL_ICON_PALETTES) }
      const id = o.id;
      const p = o.p || METAL_ICON_PALETTES.gold;
      const k = o.k || 1;
      const flower = '${FLOWER}';
      const star = flower + ' ' + '${MARK}' + ' ' + '${SPARKS}';
      const defs = '<defs>'
        + '<radialGradient id="' + id + 'bg" cx="54" cy="54" r="46" gradientUnits="userSpaceOnUse">'
        + '<stop offset="0" stop-color="' + p.bg0 + '"/><stop offset="0.58" stop-color="' + p.bg1 + '"/><stop offset="1" stop-color="' + p.base + '"/></radialGradient>'
        // Brushed Champagne: smooth diagonal specular highlight band with pure vector edges (no noise grain).
        + '<linearGradient id="' + id + 'g" gradientUnits="userSpaceOnUse" x1="24" y1="34" x2="84" y2="74">'
        + '<stop offset="0" stop-color="' + p.g0 + '"/><stop offset="0.3" stop-color="' + p.g1 + '"/><stop offset="0.5" stop-color="' + p.hi + '"/>'
        + '<stop offset="0.72" stop-color="' + p.g2 + '"/><stop offset="1" stop-color="' + p.g3 + '"/></linearGradient>'
        + '<linearGradient id="' + id + 'rim" gradientUnits="userSpaceOnUse" x1="28" y1="28" x2="80" y2="80">'
        + '<stop offset="0" stop-color="#FFFFFF" stop-opacity="0.88"/><stop offset="0.5" stop-color="' + p.g1 + '" stop-opacity="0.3"/>'
        + '<stop offset="1" stop-color="' + p.sh + '" stop-opacity="0.88"/></linearGradient>'
        + '<filter id="' + id + 'gl" x="-35%" y="-35%" width="170%" height="170%"><feGaussianBlur stdDeviation="3.2"/></filter>'
        + '<filter id="' + id + 'sh" x="-25%" y="-25%" width="150%" height="150%"><feGaussianBlur stdDeviation="2.1"/></filter>'
        + (o.clip ? '<clipPath id="' + id + 'c">' + o.clip + '</clipPath>' : '')
        + '</defs>';
      const t = 'translate(54 54) scale(' + k + ') translate(-54 -54)';
      const bg = o.bg ? '<rect width="108" height="108" fill="url(#' + id + 'bg)"/>' : '';
      const fg = o.star
        ? '<g transform="' + t + '">'
          + '<path d="' + flower + '" fill="' + p.glow + '" fill-opacity="0.32" transform="translate(0 1.2)" filter="url(#' + id + 'gl)"/>'
          + '<path d="' + star + '" fill-rule="evenodd" fill="#000" fill-opacity="0.68" transform="translate(0 2.4)" filter="url(#' + id + 'sh)"/>'
          + '<path d="' + star + '" fill-rule="evenodd" fill="url(#' + id + 'g)"/>'
          + '<path d="' + flower + '" fill="none" stroke="url(#' + id + 'rim)" stroke-width="0.85" stroke-linejoin="round"/></g>'
        : '';
      return '<svg xmlns="http://www.w3.org/2000/svg" width="' + o.size + '" height="' + o.size + '" viewBox="' + (o.viewBox || '0 0 108 108') + '">'
        + defs + (o.clip ? '<g clip-path="url(#' + id + 'c)">' + bg + fg + '</g>' : bg + fg) + '</svg>';
    }`;
const PALETTES = {
  gold: {
    g0: '#B08F44', g1: '#EBD796', hi: '#FFF8DF', g2: '#CBA854', g3: '#8C6D28',
    sh: '#403112', glow: '#D9BD6C', bg0: '#292214', bg1: '#12100A', base: '#080706'
  }
};
const PALETTES_JS = 'const METAL_ICON_PALETTES = ' + JSON.stringify(PALETTES) + ';';
const METAL_ICON_PALETTES = PALETTES;
const goldIconSvg = new Function('METAL_ICON_PALETTES', BUILDER.replace(/'\$\{FLOWER\}'/, JSON.stringify(FLOWER)).replace(/'\$\{MARK\}'/, JSON.stringify(MARK)).replace(/'\$\{SPARKS\}'/, JSON.stringify(SPARKS)) + '; return goldIconSvg;')(PALETTES);
const forHub = PALETTES_JS + '\n    ' + BUILDER.replace(/'\$\{FLOWER\}'/, 'ICON_FLOWER').replace(/'\$\{MARK\}'/, 'ICON_MARK').replace(/'\$\{SPARKS\}'/, 'ICON_SPARKS');
module.exports = { goldIconSvg };

async function createRenderer() {
  try {
    const { chromium } = require('playwright');
    const b = await chromium.launch();
    const p = await b.newPage();
    return {
      async shot(svgText, px, file, omitBg) {
        await p.setViewportSize({ width: px, height: px });
        await p.setContent(`<html><body style="margin:0;background:transparent">${svgText}</body></html>`);
        await p.screenshot({ path: file, omitBackground: omitBg });
      },
      async close() { await b.close(); }
    };
  } catch (_) {}

  const bin = ['/usr/bin/chromium', '/usr/bin/chromium-browser', '/usr/bin/google-chrome'].find((p) => fs.existsSync(p));
  if (!bin) throw new Error('Install playwright or chromium to render icon PNGs');
  const port = 9222 + Math.floor(Math.random() * 500);
  const proc = spawn(bin, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage',
    `--remote-debugging-port=${port}`, 'about:blank'
  ], { stdio: ['ignore', 'ignore', 'pipe'] });
  let wsUrl = null;
  for (let i = 0; i < 60 && !wsUrl; i++) {
    await new Promise((r) => setTimeout(r, 100));
    try {
      const res = await fetch(`http://127.0.0.1:${port}/json/list`);
      const list = await res.json();
      const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
      if (page) wsUrl = page.webSocketDebuggerUrl;
    } catch (_) {}
  }
  if (!wsUrl) { proc.kill(); throw new Error('Could not connect to headless Chromium CDP'); }
  const ws = new WebSocket(wsUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  let nextId = 1;
  const pending = new Map();
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      const { res, rej } = pending.get(msg.id);
      pending.delete(msg.id);
      if (msg.error) rej(new Error(msg.error.message)); else res(msg.result);
    }
  };
  const send = (method, params = {}) => new Promise((res, rej) => {
    const id = nextId++;
    pending.set(id, { res, rej });
    ws.send(JSON.stringify({ id, method, params }));
  });
  await send('Page.enable');
  return {
    async shot(svgText, px, file, omitBg) {
      await send('Emulation.setDeviceMetricsOverride', { width: px, height: px, deviceScaleFactor: 1, mobile: false });
      await send('Emulation.setDefaultBackgroundColorOverride', omitBg ? { color: { r: 0, g: 0, b: 0, a: 0 } } : {});
      const { frameTree } = await send('Page.getFrameTree');
      await send('Page.setDocumentContent', {
        frameId: frameTree.frame.id,
        html: `<html><body style="margin:0;background:transparent">${svgText}</body></html>`
      });
      await new Promise((r) => setTimeout(r, 40));
      const { data } = await send('Page.captureScreenshot', { format: 'png' });
      fs.writeFileSync(file, Buffer.from(data, 'base64'));
    },
    async close() { ws.close(); proc.kill(); }
  };
}

if (require.main === module) {
  (async () => {
    const r = await createRenderer();
    if (process.argv[2] === 'preview') {
      await r.shot(goldIconSvg({ id: 'p', size: 512, viewBox: '18 18 72 72', k: 1, bg: true, star: true, p: PALETTES.gold, clip: '<rect x="18" y="18" width="72" height="72" rx="16"/>' }), 512, (process.argv[3] || __dirname) + '/preview-gold.png', false);
      await r.close(); return;
    }
    for (const metal of ['gold']) {
      const pal = PALETTES[metal];
      for (const [dn, f] of [['mdpi', 1], ['hdpi', 1.5], ['xhdpi', 2], ['xxhdpi', 3], ['xxxhdpi', 4]]) {
        const px = Math.round(108 * f);
        await r.shot(goldIconSvg({ id: 'b', size: px, bg: true, star: false, p: pal }), px, `${ROOT}res/mipmap-${dn}/ic_${metal}_bg.png`, true);
        await r.shot(goldIconSvg({ id: 'f', size: px, bg: false, star: true, p: pal }), px, `${ROOT}res/mipmap-${dn}/ic_${metal}_fg.png`, true);
        const lp = Math.round(48 * f);
        await r.shot(goldIconSvg({ id: 's', size: lp, viewBox: '18 18 72 72', bg: true, star: true, p: pal, clip: '<rect x="22.5" y="22.5" width="63" height="63" rx="13.7"/>' }), lp, `${ROOT}res/mipmap-${dn}/ic_launcher_${metal}.png`, true);
        await r.shot(goldIconSvg({ id: 'r', size: lp, viewBox: '18 18 72 72', bg: true, star: true, p: pal, clip: '<circle cx="54" cy="54" r="31.5"/>' }), lp, `${ROOT}res/mipmap-${dn}/ic_launcher_${metal}_round.png`, true);
      }
    }
    await r.close();
    const block = `    // ---- Gold icon: Brushed Champagne gold star, as on the launcher (same design as its PNGs) ----\n    ${forHub}\n    // ---- end gold icon ----\n`;
    let h = fs.readFileSync(htmlPath, 'utf8');
    if (h.includes('    // ---- end gold icon ----\n')) {
      h = h.replace(/    \/\/ ---- Gold icon: [^\n]*\n[\s\S]*?    \/\/ ---- end gold icon ----\n/, block);
    } else {
      h = h.replace('    function appIconSvg(styleId, size, uid) {', block + '    function appIconSvg(styleId, size, uid) {');
    }
    fs.writeFileSync(htmlPath, h);
    console.log('ok');
  })();
}
