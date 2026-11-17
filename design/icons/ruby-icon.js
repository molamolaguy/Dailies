// Ruby app icon (1.9.3, step cut): the Dailies star as a step-cut gemstone. Smooth bands follow the star's outline,
// each lit from the top left (1.7.0's 16 wedge facets looked like a pinwheel up close), with a soft highlight, a
// lit/shadowed rim, a drop shadow and red light underneath, and white glints for the mark and sparks.
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
      const sc = (s) => 'translate(54 54) scale(' + s + ') translate(-54 -54)';
      const stops = (list) => list.map((x) => '<stop offset="' + x[0] + '" stop-color="' + x[1] + '"' + (x[2] === undefined ? '' : ' stop-opacity="' + x[2] + '"') + '/>').join('');
      const lin = (n, x1, y1, x2, y2, list) => '<linearGradient id="' + id + n + '" gradientUnits="userSpaceOnUse" x1="' + x1 + '" y1="' + y1 + '" x2="' + x2 + '" y2="' + y2 + '">' + stops(list) + '</linearGradient>';
      const glow = (n, cx, cy, r, c, op) => '<radialGradient id="' + id + n + '" gradientUnits="userSpaceOnUse" cx="' + cx + '" cy="' + cy + '" r="' + r + '"><stop offset="0" stop-color="' + c + '" stop-opacity="' + op + '"/><stop offset="1" stop-color="' + c + '" stop-opacity="0"/></radialGradient>';
      // Step cut (1.9.3): bands that follow the star's outline, stepping down towards the middle. Each is lit
      // from the top left; the steps alternate between catching the light and showing it through the stone.
      const bands = [1, 0.84, 0.68, 0.52];
      const tones = [['#FF6A88', '#C0103A', '#4A0014'], ['#5A0018', '#B80E36', '#FF4C70'], ['#FF7A94', '#D3164A', '#6A0420'], ['#E8204E', '#A00A30', '#FF5878']];
      let bandDefs = '', body = '';
      bands.forEach((b, i) => {
        bandDefs += lin('g' + i, 54 - 30 * b, 54 - 30 * b, 54 + 30 * b, 54 + 30 * b, [[0, tones[i][0]], [0.5, tones[i][1]], [1, tones[i][2]]]);
        body += '<path d="' + flower + '" transform="' + sc(b) + '" fill="url(#' + id + 'g' + i + ')"/>';
        if (i) body += '<path d="' + flower + '" transform="' + sc(b) + '" fill="none" stroke="#FFC2CF" stroke-opacity="0.22" stroke-width="' + (0.5 / b).toFixed(2) + '"/>';
      });
      const defs = '<defs>'
        + glow('w1', 0, 112, 78, '#7A1028', 0.7) + glow('w2', 112, 112, 66, '#7A1028', 0.55) + glow('w3', -4, -4, 48, '#4A0E1A', 0.35)
        + bandDefs
        + lin('rim', 32, 30, 76, 78, [[0, '#FFFFFF', 0.9], [0.45, '#FFB8C6', 0.2], [1, '#2A000A', 0.9]])
        + '<clipPath id="' + id + 'k"><path d="' + flower + '"/></clipPath>'
        + '<filter id="' + id + 'd" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="2.4"/></filter>'
        + '<filter id="' + id + 'b" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="1.3"/></filter>'
        + '<filter id="' + id + 'h" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="2.2"/></filter>'
        + '<filter id="' + id + 's" x="-60%" y="-60%" width="220%" height="220%"><feGaussianBlur stdDeviation="0.9"/></filter>'
        + (o.clip ? '<clipPath id="' + id + 'c">' + o.clip + '</clipPath>' : '')
        + '</defs>';
      const t = sc(k);
      const bg = o.bg
        ? '<rect width="108" height="108" fill="#0C090A"/><rect width="108" height="108" fill="url(#' + id + 'w1)"/>'
          + '<rect width="108" height="108" fill="url(#' + id + 'w2)"/><rect width="108" height="108" fill="url(#' + id + 'w3)"/>'
        : '';
      const fg = o.star
        ? '<g transform="' + t + '">'
          // A dark drop shadow and red light thrown under the stone.
          + '<path d="' + flower + '" fill="#000" fill-opacity="0.7" transform="translate(1.2 3)" filter="url(#' + id + 'b)"/>'
          + '<path d="' + flower + '" fill="#E0204A" fill-opacity="0.45" transform="translate(0 2)" filter="url(#' + id + 'd)"/>'
          + '<g clip-path="url(#' + id + 'k)">' + body
          + '<ellipse cx="42" cy="38" rx="11" ry="5" transform="rotate(-38 42 38)" fill="#FFFFFF" fill-opacity="0.35" filter="url(#' + id + 'h)"/></g>'
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
