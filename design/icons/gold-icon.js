// Gold app icon (1.6.2): a satin, finely grained metal Dailies star with a crisp bevel lit from the top
// left and cut-outs that show the background, on near-black with a warm glow (matched to a reference).
// The Ruby icon is a gemstone instead: design/icons/ruby-icon.js.
// Run from the repo root: node design/icons/gold-icon.js (needs Playwright with Chromium).
// One SVG design -> adaptive icon layers (PNG, every density), legacy PNGs, and the hub's own preview.
const fs = require('fs');
const { chromium } = require('playwright');
const ROOT = require('path').join(__dirname, '../../app/src/main/') + '/';
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
      const star = '${FLOWER}' + ' ' + '${MARK}' + ' ' + '${SPARKS}';
      const defs = '<defs>'
        + '<linearGradient id="' + id + 'g" gradientUnits="userSpaceOnUse" x1="78" y1="26" x2="30" y2="82">'
        + '<stop offset="0" stop-color="' + p.g0 + '"/><stop offset="0.5" stop-color="' + p.g1 + '"/><stop offset="1" stop-color="' + p.g2 + '"/></linearGradient>'
        + '<radialGradient id="' + id + 'w1" gradientUnits="userSpaceOnUse" cx="0" cy="112" r="78"><stop offset="0" stop-color="' + p.glow + '" stop-opacity="0.75"/><stop offset="1" stop-color="' + p.glow + '" stop-opacity="0"/></radialGradient>'
        + '<radialGradient id="' + id + 'w2" gradientUnits="userSpaceOnUse" cx="112" cy="112" r="66"><stop offset="0" stop-color="' + p.glow + '" stop-opacity="0.6"/><stop offset="1" stop-color="' + p.glow + '" stop-opacity="0"/></radialGradient>'
        + '<radialGradient id="' + id + 'w3" gradientUnits="userSpaceOnUse" cx="-4" cy="-4" r="48"><stop offset="0" stop-color="' + p.glow2 + '" stop-opacity="0.35"/><stop offset="1" stop-color="' + p.glow2 + '" stop-opacity="0"/></radialGradient>'
        // Satin grain + crisp bevel: a light band where the shape ends toward the top left, a dark band toward the bottom right.
        + '<filter id="' + id + 'f" x="-5%" y="-5%" width="110%" height="110%" color-interpolation-filters="sRGB">'
        + '<feTurbulence type="fractalNoise" baseFrequency="1.4" numOctaves="2" seed="7" result="n"/>'
        + '<feColorMatrix in="n" type="matrix" values="0 0 0 0 0.5  0 0 0 0 0.5  0 0 0 0 0.5  0.33 0.33 0.33 0 0" result="ng"/>'
        + '<feComposite in="SourceGraphic" in2="ng" operator="arithmetic" k1="0" k2="1" k3="0.16" k4="-0.08" result="grain"/>'
        + '<feComposite in="grain" in2="SourceAlpha" operator="in" result="body"/>'
        + '<feOffset in="SourceAlpha" dx="0.75" dy="0.75" result="o1"/><feComposite in="SourceAlpha" in2="o1" operator="out" result="b1"/>'
        + '<feGaussianBlur in="b1" stdDeviation="0.22" result="b1s"/><feFlood flood-color="' + p.hi + '" flood-opacity="0.85"/><feComposite in2="b1s" operator="in"/><feComposite in2="SourceAlpha" operator="in" result="hi"/>'
        + '<feOffset in="SourceAlpha" dx="-0.75" dy="-0.75" result="o2"/><feComposite in="SourceAlpha" in2="o2" operator="out" result="b2"/>'
        + '<feGaussianBlur in="b2" stdDeviation="0.22" result="b2s"/><feFlood flood-color="' + p.sh + '" flood-opacity="0.85"/><feComposite in2="b2s" operator="in"/><feComposite in2="SourceAlpha" operator="in" result="sh"/>'
        + '<feMerge><feMergeNode in="body"/><feMergeNode in="hi"/><feMergeNode in="sh"/></feMerge></filter>'
        + '<filter id="' + id + 'd" x="-10%" y="-10%" width="120%" height="120%"><feGaussianBlur stdDeviation="1.1"/></filter>'
        + (o.clip ? '<clipPath id="' + id + 'c">' + o.clip + '</clipPath>' : '')
        + '</defs>';
      const t = 'translate(54 54) scale(' + k + ') translate(-54 -54)';
      const bg = o.bg
        ? '<rect width="108" height="108" fill="' + p.base + '"/><rect width="108" height="108" fill="url(#' + id + 'w1)"/>'
          + '<rect width="108" height="108" fill="url(#' + id + 'w2)"/><rect width="108" height="108" fill="url(#' + id + 'w3)"/>'
        : '';
      const fg = o.star
        ? '<g transform="' + t + '"><path d="' + star + '" fill-rule="evenodd" fill="#000" fill-opacity="0.55" transform="translate(0.3 0.9)" filter="url(#' + id + 'd)"/>'
          + '<path d="' + star + '" fill-rule="evenodd" fill="url(#' + id + 'g)" filter="url(#' + id + 'f)"/></g>'
        : '';
      return '<svg xmlns="http://www.w3.org/2000/svg" width="' + o.size + '" height="' + o.size + '" viewBox="' + (o.viewBox || '0 0 108 108') + '">'
        + defs + (o.clip ? '<g clip-path="url(#' + id + 'c)">' + bg + fg + '</g>' : bg + fg) + '</svg>';
    }`;
const PALETTES = {
  gold: { g0: '#E6D49A', g1: '#CBB679', g2: '#A68E54', hi: '#FFF7DC', sh: '#4A3E22', glow: '#6B5128', glow2: '#4A3A20', base: '#0B0B09' }
};
const PALETTES_JS = 'const METAL_ICON_PALETTES = ' + JSON.stringify(PALETTES) + ';';
const METAL_ICON_PALETTES = PALETTES;
const goldIconSvg = new Function('METAL_ICON_PALETTES', BUILDER.replace(/'\$\{FLOWER\}'/, JSON.stringify(FLOWER)).replace(/'\$\{MARK\}'/, JSON.stringify(MARK)).replace(/'\$\{SPARKS\}'/, JSON.stringify(SPARKS)) + '; return goldIconSvg;')(PALETTES);
const forHub = PALETTES_JS + '\n    ' + BUILDER.replace(/'\$\{FLOWER\}'/, 'ICON_FLOWER').replace(/'\$\{MARK\}'/, 'ICON_MARK').replace(/'\$\{SPARKS\}'/, 'ICON_SPARKS');
module.exports = { goldIconSvg };

(async () => {
  if (process.argv[2] === 'preview') {
    const b = await chromium.launch(); const p = await b.newPage();
    await p.setViewportSize({ width: 512, height: 512 });
    for (const metal of ['gold']) {
      await p.setContent(`<body style="margin:0">${goldIconSvg({ id: 'p', size: 512, viewBox: '18 18 72 72', k: 1, bg: true, star: true, p: PALETTES[metal], clip: '<rect x="18" y="18" width="72" height="72" rx="16"/>' })}</body>`);
      await p.screenshot({ path: (process.argv[3] || __dirname) + `/preview-${metal}.png` });
    }
    await b.close(); return;
  }
  const b = await chromium.launch(); const p = await b.newPage();
  const shot = async (svgText, px, file) => {
    await p.setViewportSize({ width: px, height: px });
    await p.setContent(`<html><body style="margin:0;background:transparent">${svgText}</body></html>`);
    await p.screenshot({ path: file, omitBackground: true });
  };
  for (const metal of ['gold']) {
    const pal = PALETTES[metal];
    // Adaptive icon layers: 108 dp each.
    for (const [dn, f] of [['mdpi', 1], ['hdpi', 1.5], ['xhdpi', 2], ['xxhdpi', 3], ['xxxhdpi', 4]]) {
      const px = Math.round(108 * f);
      await shot(goldIconSvg({ id: 'b', size: px, bg: true, star: false, p: pal }), px, `${ROOT}res/mipmap-${dn}/ic_${metal}_bg.png`);
      await shot(goldIconSvg({ id: 'f', size: px, bg: false, star: true, p: pal }), px, `${ROOT}res/mipmap-${dn}/ic_${metal}_fg.png`);
      // Legacy (pre-Android 8) icons: the 72-unit safe zone, as a rounded square and a circle.
      const lp = Math.round(48 * f);
      await shot(goldIconSvg({ id: 's', size: lp, viewBox: '18 18 72 72', bg: true, star: true, p: pal, clip: '<rect x="22.5" y="22.5" width="63" height="63" rx="13.7"/>' }), lp, `${ROOT}res/mipmap-${dn}/ic_launcher_${metal}.png`);
      await shot(goldIconSvg({ id: 'r', size: lp, viewBox: '18 18 72 72', bg: true, star: true, p: pal, clip: '<circle cx="54" cy="54" r="31.5"/>' }), lp, `${ROOT}res/mipmap-${dn}/ic_launcher_${metal}_round.png`);
    }
  }
  await b.close();
  // The hub draws the same design.
  const start = '    // ---- Gold icon (generated: web/../scratchpad goldicon2.js) ----';
  const block = `    // ---- Gold icon: satin gold star with a crisp bevel, as on the launcher (same design as its PNGs) ----\n    ${forHub}\n    // ---- end gold icon ----\n`;
  let h = fs.readFileSync(htmlPath, 'utf8');
  if (h.includes('    // ---- end gold icon ----\n')) {
    h = h.replace(/    \/\/ ---- Gold icon: [^\n]*\n[\s\S]*?    \/\/ ---- end gold icon ----\n/, block);
  } else {
    h = h.replace('    function appIconSvg(styleId, size, uid) {', block + '    function appIconSvg(styleId, size, uid) {');
  }
  fs.writeFileSync(htmlPath, h);
  console.log('ok');
})();
