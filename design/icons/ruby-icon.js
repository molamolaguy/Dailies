// Ruby app icon (1.7.0): the Dailies star cut as a raised, faceted gemstone. An outer slope lit hard from the top
// left gives it depth; the crown facets and table inside show light refracted the other way, with a specular
// highlight, a lit/shadowed rim, a drop shadow and red light underneath, and white glints for the mark and sparks.
// Run from the repo root: node design/icons/ruby-icon.js (needs Playwright with Chromium).
// `node design/icons/ruby-icon.js preview <dir>` writes preview-ruby.png only.
const fs = require('fs');
const { chromium } = require('playwright');
const ROOT = require('path').join(__dirname, '../../app/src/main/') + '/';
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
      const mix = (a, b, t) => '#' + [0, 2, 4].map((i) => Math.round(parseInt(a.substr(i + 1, 2), 16) * (1 - t) + parseInt(b.substr(i + 1, 2), 16) * t).toString(16).padStart(2, '0')).join('');
      const shade = (v) => { v = Math.max(0, Math.min(1, v)); return v < 0.5 ? mix('#1A0006', '#BA0C30', v * 2) : mix('#BA0C30', '#FF4E72', (v - 0.5) * 2); };
      const pt = (r, a) => (54 + r * Math.cos(a)).toFixed(2) + ',' + (54 + r * Math.sin(a)).toFixed(2);
      const N = 16, R = 34, C = 0.7, T = 10.5, L = -2.36, step = (2 * Math.PI) / N;
      const inner = 'translate(54 54) scale(' + C + ') translate(-54 -54)';
      // Outer slope (the bevel down to the edge): lit strongly from the top left, so the stone reads as raised.
      let slope = '', crown = '', lines = '';
      for (let i = 0; i < N; i++) {
        const a0 = i * step - Math.PI / 2, a1 = a0 + step, m = a0 + step / 2;
        const v = 0.5 + 0.47 * Math.cos(m - L) + (i % 2 ? 0.07 : -0.07);
        slope += '<path d="M54,54 L' + pt(R, a0) + ' L' + pt(R, a1) + 'Z" fill="' + shade(v) + '"/>';
        lines += 'M' + pt(R * C, a0) + ' L' + pt(R, a0) + ' ';
      }
      // Crown facets on top, seen through the stone, so their light is reversed (refraction) with a bright kite each.
      for (let i = 0; i < N; i++) {
        const a0 = i * step - Math.PI / 2 + step / 2, a1 = a0 + step, m = a0 + step / 2;
        const v = 0.48 - 0.3 * Math.cos(m - L) + (i % 2 ? 0.12 : -0.08);
        crown += '<path d="M' + pt(T, a0) + ' L' + pt(R * C, a0) + ' L' + pt(R * C, a1) + ' L' + pt(T, a1) + 'Z" fill="' + shade(v) + '"/>'
          + '<path d="M' + pt(T, a0) + ' L' + pt(T * 1.85, m) + ' L' + pt(T, a1) + 'Z" fill="' + shade(v + 0.2) + '"/>';
        lines += 'M' + pt(T, a0) + ' L' + pt(R * C, a0) + ' M' + pt(T, a0) + ' L' + pt(T * 1.85, m) + ' L' + pt(T, a1) + ' ';
      }
      let table = '';
      for (let i = 0; i < 8; i++) table += (i ? ' L' : 'M') + pt(T, i * step * 2 - Math.PI / 2 + step / 2);
      table += 'Z';
      const glow = (n, cx, cy, r, c, op) => '<radialGradient id="' + id + n + '" gradientUnits="userSpaceOnUse" cx="' + cx + '" cy="' + cy + '" r="' + r + '"><stop offset="0" stop-color="' + c + '" stop-opacity="' + op + '"/><stop offset="1" stop-color="' + c + '" stop-opacity="0"/></radialGradient>';
      const defs = '<defs>'
        + glow('w1', 0, 112, 78, '#7A1028', 0.7) + glow('w2', 112, 112, 66, '#7A1028', 0.55) + glow('w3', -4, -4, 48, '#4A0E1A', 0.35)
        + '<linearGradient id="' + id + 't" gradientUnits="userSpaceOnUse" x1="46" y1="45" x2="62" y2="63"><stop offset="0" stop-color="#FF5C7E"/><stop offset="0.45" stop-color="#D4143E"/><stop offset="1" stop-color="#6E0620"/></linearGradient>'
        + '<linearGradient id="' + id + 'rim" gradientUnits="userSpaceOnUse" x1="32" y1="30" x2="76" y2="78"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.95"/><stop offset="0.45" stop-color="#FFB8C6" stop-opacity="0.25"/><stop offset="1" stop-color="#3A0010" stop-opacity="0.9"/></linearGradient>'
        + '<linearGradient id="' + id + 'gl" gradientUnits="userSpaceOnUse" x1="30" y1="30" x2="60" y2="60"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.2"/><stop offset="0.45" stop-color="#FFFFFF" stop-opacity="0.02"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>'
        + glow('spec', 40, 39, 9, '#FFFFFF', 0.85) + glow('cau', 60, 62, 12, '#FF5A7E', 0.55)
        + '<clipPath id="' + id + 'k"><path d="' + flower + '"/></clipPath>'
        + '<clipPath id="' + id + 'i"><path d="' + flower + '" transform="' + inner + '"/></clipPath>'
        + '<filter id="' + id + 'd" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="2.4"/></filter>'
        + '<filter id="' + id + 'b" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="1.3"/></filter>'
        + '<filter id="' + id + 's" x="-60%" y="-60%" width="220%" height="220%"><feGaussianBlur stdDeviation="0.9"/></filter>'
        + (o.clip ? '<clipPath id="' + id + 'c">' + o.clip + '</clipPath>' : '')
        + '</defs>';
      const t = 'translate(54 54) scale(' + k + ') translate(-54 -54)';
      const bg = o.bg
        ? '<rect width="108" height="108" fill="#0C090A"/><rect width="108" height="108" fill="url(#' + id + 'w1)"/>'
          + '<rect width="108" height="108" fill="url(#' + id + 'w2)"/><rect width="108" height="108" fill="url(#' + id + 'w3)"/>'
        : '';
      const fg = o.star
        ? '<g transform="' + t + '">'
          // A dark drop shadow and red light thrown under the stone.
          + '<path d="' + flower + '" fill="#000" fill-opacity="0.7" transform="translate(1.2 3)" filter="url(#' + id + 'b)"/>'
          + '<path d="' + flower + '" fill="#E0204A" fill-opacity="0.45" transform="translate(0 2)" filter="url(#' + id + 'd)"/>'
          + '<g clip-path="url(#' + id + 'k)">' + slope
          + '<g clip-path="url(#' + id + 'i)">' + crown + '<path d="' + table + '" fill="url(#' + id + 't)"/>'
          + '<circle cx="60" cy="62" r="12" fill="url(#' + id + 'cau)"/></g>'
          + '<path d="' + lines + table + '" fill="none" stroke="#FFD6DE" stroke-opacity="0.3" stroke-width="0.3" stroke-linejoin="round"/>'
          // The ridge between slope and crown: a dark line with a bright edge above it.
          + '<path d="' + flower + '" transform="' + inner + '" fill="none" stroke="#2A0009" stroke-opacity="0.75" stroke-width="1.2"/>'
          + '<path d="' + flower + '" transform="translate(-0.45 -0.45) ' + inner + '" fill="none" stroke="#FFC9D4" stroke-opacity="0.5" stroke-width="0.5"/>'
          + '<path d="' + flower + '" fill="url(#' + id + 'gl)"/>'
          + '<ellipse cx="40" cy="39" rx="9" ry="5" transform="rotate(-40 40 39)" fill="url(#' + id + 'spec)"/></g>'
          // A rim lit at the top left and in shadow at the bottom right.
          + '<path d="' + flower + '" fill="none" stroke="url(#' + id + 'rim)" stroke-width="0.8" stroke-linejoin="round"/>'
          // The Dailies mark and sparks as white glints.
          + '<path d="' + mark + ' ' + sparks + '" fill="#FFFFFF" fill-opacity="0.75" filter="url(#' + id + 's)"/>'
          + '<path d="' + mark + ' ' + sparks + '" fill="#FFFFFF"/></g>'
        : '';
      return '<svg xmlns="http://www.w3.org/2000/svg" width="' + o.size + '" height="' + o.size + '" viewBox="' + (o.viewBox || '0 0 108 108') + '">'
        + defs + (o.clip ? '<g clip-path="url(#' + id + 'c)">' + bg + fg + '</g>' : bg + fg) + '</svg>';
    }`;
const sub = (s, f, m, sp) => s.replace(/'\$\{FLOWER\}'/, f).replace(/'\$\{MARK\}'/, m).replace(/'\$\{SPARKS\}'/, sp);
const rubyIconSvg = new Function(sub(BUILDER, JSON.stringify(FLOWER), JSON.stringify(MARK), JSON.stringify(SPARKS)) + '; return rubyIconSvg;')();
const forHub = sub(BUILDER, 'ICON_FLOWER', 'ICON_MARK', 'ICON_SPARKS');
module.exports = { rubyIconSvg };

(async () => {
  const b = await chromium.launch(); const p = await b.newPage();
  if (process.argv[2] === 'preview') {
    await p.setViewportSize({ width: 512, height: 512 });
    await p.setContent(`<body style="margin:0">${rubyIconSvg({ id: 'p', size: 512, viewBox: '18 18 72 72', bg: true, star: true, clip: '<rect x="18" y="18" width="72" height="72" rx="16"/>' })}</body>`);
    await p.screenshot({ path: (process.argv[3] || __dirname) + '/preview-ruby.png' });
    await b.close(); return;
  }
  const shot = async (svgText, px, file) => {
    await p.setViewportSize({ width: px, height: px });
    await p.setContent(`<html><body style="margin:0;background:transparent">${svgText}</body></html>`);
    await p.screenshot({ path: file, omitBackground: true });
  };
  for (const [dn, f] of [['mdpi', 1], ['hdpi', 1.5], ['xhdpi', 2], ['xxhdpi', 3], ['xxxhdpi', 4]]) {
    const px = Math.round(108 * f);
    await shot(rubyIconSvg({ id: 'b', size: px, bg: true, star: false }), px, `${ROOT}res/mipmap-${dn}/ic_ruby_bg.png`);
    await shot(rubyIconSvg({ id: 'f', size: px, bg: false, star: true }), px, `${ROOT}res/mipmap-${dn}/ic_ruby_fg.png`);
    const lp = Math.round(48 * f);
    await shot(rubyIconSvg({ id: 's', size: lp, viewBox: '18 18 72 72', bg: true, star: true, clip: '<rect x="22.5" y="22.5" width="63" height="63" rx="13.7"/>' }), lp, `${ROOT}res/mipmap-${dn}/ic_launcher_ruby.png`);
    await shot(rubyIconSvg({ id: 'r', size: lp, viewBox: '18 18 72 72', bg: true, star: true, clip: '<circle cx="54" cy="54" r="31.5"/>' }), lp, `${ROOT}res/mipmap-${dn}/ic_launcher_ruby_round.png`);
  }
  await b.close();
  const block = `    // ---- Ruby icon: a faceted gemstone star, as on the launcher (design/icons/ruby-icon.js) ----\n    ${forHub}\n    // ---- end ruby icon ----\n`;
  let h = fs.readFileSync(htmlPath, 'utf8');
  if (h.includes('    // ---- end ruby icon ----\n')) h = h.replace(/    \/\/ ---- Ruby icon: [^\n]*\n[\s\S]*?    \/\/ ---- end ruby icon ----\n/, block);
  else h = h.replace('    function appIconSvg(styleId, size, uid) {', block + '    function appIconSvg(styleId, size, uid) {');
  fs.writeFileSync(htmlPath, h);
  console.log('ok');
})();
