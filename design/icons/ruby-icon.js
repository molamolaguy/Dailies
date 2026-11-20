// Ruby app icon (Crystalline Prism): the Dailies star as a faceted pigeon-blood ruby with 4 broad crystal
// planes, a central diamond table, a crisp prism rim, crimson under-glow, and luminous white glints for the
// mark and sparks.
// Run from the repo root: node design/icons/ruby-icon.js (uses Playwright if installed, or system Chromium via CDP).
// `node design/icons/ruby-icon.js preview <dir>` writes preview-ruby.png only.
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const ROOT = path.join(__dirname, '../../app/src/main/') + '/';
const htmlPath = ROOT + 'assets/index.html';
const html = fs.readFileSync(htmlPath, 'utf8');
const FLOWER = html.match(/const ICON_FLOWER = '([^']+)'/)[1];
const MARK = html.match(/const ICON_MARK = '([^']+)'/)[1];
const SPARKS = html.match(/const ICON_SPARKS = '([^']+)'/)[1];

// The builder, also written into index.html. Same options as goldIconSvg: { id, size, viewBox, k, bg, star, clip }.
const BUILDER = String.raw`function rubyIconSvg(o) {
      const id = o.id;
      const k = o.k || 1;
      const flower = '${FLOWER}';
      const mark = '${MARK}';
      const sparks = '${SPARKS}';
      const sc = (s) => 'translate(54 54) scale(' + s + ') translate(-54 -54)';
      const stops = (list) => list.map((x) => '<stop offset="' + x[0] + '" stop-color="' + x[1] + '"' + (x[2] === undefined ? '' : ' stop-opacity="' + x[2] + '"') + '/>').join('');
      const lin = (n, x1, y1, x2, y2, list) => '<linearGradient id="' + id + n + '" gradientUnits="userSpaceOnUse" x1="' + x1 + '" y1="' + y1 + '" x2="' + x2 + '" y2="' + y2 + '">' + stops(list) + '</linearGradient>';
      const defs = '<defs>'
        + '<radialGradient id="' + id + 'bg" cx="54" cy="54" r="46" gradientUnits="userSpaceOnUse">'
        + stops([[0, '#400612'], [0.55, '#170307'], [1, '#080305']]) + '</radialGradient>'
        // Crystalline Prism: 4 broad geometric crystal planes + center diamond table.
        + lin('p1', 28, 28, 54, 54, [[0, '#FF4766'], [1, '#BD0B2C']])
        + lin('p2', 80, 28, 54, 54, [[0, '#B00725'], [1, '#6E0215']])
        + lin('p3', 28, 80, 54, 54, [[0, '#7D0318'], [1, '#C90E2F']])
        + lin('p4', 80, 80, 54, 54, [[0, '#47010D'], [0.6, '#96051F'], [1, '#EB1C3F']])
        + lin('rim', 28, 28, 80, 80, [[0, '#FFFFFF', 0.95], [0.5, '#FF5C77', 0.35], [1, '#300009', 0.95]])
        + '<clipPath id="' + id + 'k"><path d="' + flower + '"/></clipPath>'
        + '<filter id="' + id + 'sh" x="-25%" y="-25%" width="150%" height="150%"><feGaussianBlur stdDeviation="2"/></filter>'
        + '<filter id="' + id + 'gl" x="-35%" y="-35%" width="170%" height="170%"><feGaussianBlur stdDeviation="3.4"/></filter>'
        + '<filter id="' + id + 'sg" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="0.8"/></filter>'
        + (o.clip ? '<clipPath id="' + id + 'c">' + o.clip + '</clipPath>' : '')
        + '</defs>';
      const t = sc(k);
      const bg = o.bg ? '<rect width="108" height="108" fill="url(#' + id + 'bg)"/>' : '';
      const fg = o.star
        ? '<g transform="' + t + '">'
          + '<path d="' + flower + '" fill="#FF2247" fill-opacity="0.42" transform="translate(0 1.5)" filter="url(#' + id + 'gl)"/>'
          + '<path d="' + flower + '" fill="#000" fill-opacity="0.75" transform="translate(0.8 2.6)" filter="url(#' + id + 'sh)"/>'
          + '<g clip-path="url(#' + id + 'k)">'
          + '<polygon points="0,0 54,0 54,54 0,54" fill="url(#' + id + 'p1)"/>'
          + '<polygon points="54,0 108,0 108,54 54,54" fill="url(#' + id + 'p2)"/>'
          + '<polygon points="0,54 54,54 54,108 0,108" fill="url(#' + id + 'p3)"/>'
          + '<polygon points="54,54 108,54 108,108 54,108" fill="url(#' + id + 'p4)"/>'
          + '<polygon points="54,34 74,54 54,74 34,54" fill="#B50928" fill-opacity="0.45" stroke="#FFE0E6" stroke-opacity="0.32" stroke-width="0.6"/>'
          + '<line x1="24" y1="54" x2="84" y2="54" stroke="#FFD6DE" stroke-opacity="0.18" stroke-width="0.5"/>'
          + '<line x1="54" y1="24" x2="54" y2="84" stroke="#FFD6DE" stroke-opacity="0.18" stroke-width="0.5"/></g>'
          + '<path d="' + flower + '" fill="none" stroke="url(#' + id + 'rim)" stroke-width="0.95" stroke-linejoin="round"/>'
          + '<path d="' + mark + ' ' + sparks + '" fill="#FFFFFF" fill-opacity="0.65" filter="url(#' + id + 'sg)"/>'
          + '<path d="' + mark + ' ' + sparks + '" fill="#FFFFFF"/></g>'
        : '';
      return '<svg xmlns="http://www.w3.org/2000/svg" width="' + o.size + '" height="' + o.size + '" viewBox="' + (o.viewBox || '0 0 108 108') + '">'
        + defs + (o.clip ? '<g clip-path="url(#' + id + 'c)">' + bg + fg + '</g>' : bg + fg) + '</svg>';
    }`;
const sub = (s, f, m, sp) => s.replace(/'\$\{FLOWER\}'/, f).replace(/'\$\{MARK\}'/, m).replace(/'\$\{SPARKS\}'/, sp);
const rubyIconSvg = new Function(sub(BUILDER, JSON.stringify(FLOWER), JSON.stringify(MARK), JSON.stringify(SPARKS)) + '; return rubyIconSvg;')();
const forHub = sub(BUILDER, 'ICON_FLOWER', 'ICON_MARK', 'ICON_SPARKS');
module.exports = { rubyIconSvg };

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

  // Fallback: drive system Chromium/Chrome directly over CDP (Node 22+ built-in WebSocket).
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
      await r.shot(rubyIconSvg({ id: 'p', size: 512, viewBox: '18 18 72 72', bg: true, star: true, clip: '<rect x="18" y="18" width="72" height="72" rx="16"/>' }), 512, (process.argv[3] || __dirname) + '/preview-ruby.png', false);
      await r.close(); return;
    }
    for (const [dn, f] of [['mdpi', 1], ['hdpi', 1.5], ['xhdpi', 2], ['xxhdpi', 3], ['xxxhdpi', 4]]) {
      const px = Math.round(108 * f);
      await r.shot(rubyIconSvg({ id: 'b', size: px, bg: true, star: false }), px, `${ROOT}res/mipmap-${dn}/ic_ruby_bg.png`, true);
      await r.shot(rubyIconSvg({ id: 'f', size: px, bg: false, star: true }), px, `${ROOT}res/mipmap-${dn}/ic_ruby_fg.png`, true);
      const lp = Math.round(48 * f);
      await r.shot(rubyIconSvg({ id: 's', size: lp, viewBox: '18 18 72 72', bg: true, star: true, clip: '<rect x="22.5" y="22.5" width="63" height="63" rx="13.7"/>' }), lp, `${ROOT}res/mipmap-${dn}/ic_launcher_ruby.png`, true);
      await r.shot(rubyIconSvg({ id: 'r', size: lp, viewBox: '18 18 72 72', bg: true, star: true, clip: '<circle cx="54" cy="54" r="31.5"/>' }), lp, `${ROOT}res/mipmap-${dn}/ic_launcher_ruby_round.png`, true);
    }
    await r.close();
    const block = `    // ---- Ruby icon: a faceted gemstone star, as on the launcher (design/icons/ruby-icon.js) ----\n    ${forHub}\n    // ---- end ruby icon ----\n`;
    let h = fs.readFileSync(htmlPath, 'utf8');
    if (h.includes('    // ---- end ruby icon ----\n')) h = h.replace(/    \/\/ ---- Ruby icon: [^\n]*\n[\s\S]*?    \/\/ ---- end ruby icon ----\n/, block);
    else h = h.replace('    function appIconSvg(styleId, size, uid) {', block + '    function appIconSvg(styleId, size, uid) {');
    fs.writeFileSync(htmlPath, h);
    console.log('ok');
  })();
}
