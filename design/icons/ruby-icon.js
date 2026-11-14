// Ruby app icon (1.7.0): the Dailies star cut as a faceted gemstone. It has a bright table in the middle, crown facets
// radiating out (lit from the top left, with light bouncing back from the lower right), a glassy highlight, white
// sparkle glints for the mark and sparks, and a red glow on near-black. No grain and no metal bevel.
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
      const shade = (v) => (v < 0.5 ? mix('#26010A', '#B8102F', v * 2) : mix('#B8102F', '#FF6A86', (v - 0.5) * 2));
      const pt = (r, a) => (54 + r * Math.cos(a)).toFixed(2) + ',' + (54 + r * Math.sin(a)).toFixed(2);
      const N = 16, R = 34, T = 11.5, L = -2.36;
      let facets = '', lines = '';
      for (let i = 0; i < N; i++) {
        const a0 = (i / N) * 2 * Math.PI - Math.PI / 2 - Math.PI / N, a1 = a0 + (2 * Math.PI) / N, m = (a0 + a1) / 2;
        const key = 0.5 + 0.5 * Math.cos(m - L), back = Math.pow(Math.max(0, Math.cos(m - L - Math.PI)), 3);
        const v = Math.min(1, 0.02 + 0.6 * key + 0.5 * back + (i % 2 ? 0.16 : -0.1));
        // Each crown facet splits in two (star facet near the table, upper girdle facet outside) for a cut look.
        facets += '<path d="M' + pt(T, a0) + ' L' + pt(R, a0) + ' L' + pt(R, a1) + ' L' + pt(T, a1) + 'Z" fill="' + shade(v) + '"/>'
          + '<path d="M' + pt(T, a0) + ' L' + pt(T * 1.9, m) + ' L' + pt(T, a1) + 'Z" fill="' + shade(Math.min(1, v + 0.16)) + '"/>';
        lines += 'M' + pt(T, a0) + ' L' + pt(R, a0) + ' M' + pt(T, a0) + ' L' + pt(T * 1.9, m) + ' L' + pt(T, a1) + ' ';
      }
      let table = '';
      for (let i = 0; i < 8; i++) table += (i ? ' L' : 'M') + pt(T, (i / 8) * 2 * Math.PI - Math.PI / 2 - Math.PI / N);
      table += 'Z';
      const defs = '<defs>'
        + '<radialGradient id="' + id + 'w1" gradientUnits="userSpaceOnUse" cx="0" cy="112" r="78"><stop offset="0" stop-color="#7A1028" stop-opacity="0.7"/><stop offset="1" stop-color="#7A1028" stop-opacity="0"/></radialGradient>'
        + '<radialGradient id="' + id + 'w2" gradientUnits="userSpaceOnUse" cx="112" cy="112" r="66"><stop offset="0" stop-color="#7A1028" stop-opacity="0.55"/><stop offset="1" stop-color="#7A1028" stop-opacity="0"/></radialGradient>'
        + '<radialGradient id="' + id + 'w3" gradientUnits="userSpaceOnUse" cx="-4" cy="-4" r="48"><stop offset="0" stop-color="#4A0E1A" stop-opacity="0.35"/><stop offset="1" stop-color="#4A0E1A" stop-opacity="0"/></radialGradient>'
        + '<radialGradient id="' + id + 't" gradientUnits="userSpaceOnUse" cx="50" cy="49" r="15"><stop offset="0" stop-color="#FF9AAE"/><stop offset="0.5" stop-color="#E8244E"/><stop offset="1" stop-color="#8E0A2A"/></radialGradient>'
        + '<linearGradient id="' + id + 'gl" gradientUnits="userSpaceOnUse" x1="34" y1="28" x2="58" y2="58"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.38"/><stop offset="0.5" stop-color="#FFFFFF" stop-opacity="0.04"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>'
        + '<clipPath id="' + id + 'k"><path d="' + flower + '"/></clipPath>'
        + '<filter id="' + id + 'd" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="2.6"/></filter>'
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
          // Red light thrown under the stone.
          + '<path d="' + flower + '" fill="#E0204A" fill-opacity="0.5" transform="translate(0 1.6)" filter="url(#' + id + 'd)"/>'
          + '<g clip-path="url(#' + id + 'k)">' + facets
          + '<path d="' + table + '" fill="url(#' + id + 't)"/>'
          + '<path d="' + lines + table + '" fill="none" stroke="#FFD6DE" stroke-opacity="0.35" stroke-width="0.35" stroke-linejoin="round"/>'
          + '<path d="' + flower + '" fill="url(#' + id + 'gl)"/>'
          // A thin dark girdle just inside the edge, and a bright rim on the lit side.
          + '<path d="' + flower + '" fill="none" stroke="#2A020A" stroke-opacity="0.7" stroke-width="1.6"/></g>'
          + '<path d="' + flower + '" fill="none" stroke="#FFC2CE" stroke-opacity="0.55" stroke-width="0.4" stroke-linejoin="round"/>'
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
