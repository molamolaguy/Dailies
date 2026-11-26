// Step-by-step browser for playing one daily game. Each run reopens the game with the same profile
// (so the game's progress is kept), runs your steps, then saves a screenshot and prints the page text
// and every text the game copied or shared (kept across runs in work/<id>/shares.json).
//
//   node drive.js <id> <url> '<steps>'
//
// <steps> is the body of an async function with `page` (Playwright) and these helpers:
//   await click('Share')          click the visible button/link whose text matches (regex, case-insensitive)
//   await type('CRANE')           type text and press Enter
//   await key('Backspace')        press a key
//   await tap(x, y)               tap a point (in the 412x900 screenshot)
//   await wait(ms)
// Example: node drive.js 12 https://example.com 'await click("^play$"); await type("CRANE"); await click("share")'
// Pass '' to just look. Screenshot: work/<id>/shot.png (Read it to see the page).
const { chromium } = require('playwright');
const fs = require('fs'); const path = require('path');
const [id, url, steps = ''] = process.argv.slice(2);
const dir = path.join(__dirname, 'work', String(id)); fs.mkdirSync(dir, { recursive: true });
const sharesFile = path.join(dir, 'shares.json');
const HOOK = () => {
  window.__shares = [];
  const rec = (kind, t) => { try { if (t && String(t).trim()) window.__shares.push({ kind, text: String(t) }); } catch (e) {} };
  try { const c = navigator.clipboard; if (c) {
    const wt = c.writeText && c.writeText.bind(c); c.writeText = (t) => { rec('clipboard', t); return wt ? wt(t).catch(() => {}) : Promise.resolve(); };
    const w = c.write && c.write.bind(c); c.write = async (items) => { try { for (const it of items) for (const ty of it.types) if (ty.startsWith('text/plain')) rec('clipboard', await (await it.getType(ty)).text()); } catch (e) {} return w ? w(items).catch(() => {}) : undefined; };
  } } catch (e) {}
  try { navigator.share = (d) => { rec('share', [d && d.title, d && d.text, d && d.url].filter(Boolean).join('\n')); return Promise.resolve(); }; navigator.canShare = () => true; } catch (e) {}
  document.addEventListener('copy', (e) => { setTimeout(() => { try { rec('copy-event', (e.clipboardData && e.clipboardData.getData('text/plain')) || String(getSelection())); } catch (x) {} }, 0); }, true);
  const ex = document.execCommand.bind(document);
  document.execCommand = (cmd, ...a) => { if (String(cmd).toLowerCase() === 'copy') { const el = document.activeElement; rec('execCommand', (el && 'value' in el && el.value) || String(getSelection())); } return ex(cmd, ...a); };
  window.open = (u) => { rec('window.open', u); return null; };
};
(async () => {
  const ctx = await chromium.launchPersistentContext(path.join(dir, 'profile'), {
    viewport: { width: 412, height: 900 }, deviceScaleFactor: 1,
    userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36',
    permissions: ['clipboard-read', 'clipboard-write'] });
  await ctx.addInitScript(HOOK);
  await ctx.route(/googlesyndication|doubleclick|googletagmanager|google-analytics|adservice|amazon-adsystem|mediavine|pubmatic|criteo|taboola|outbrain|adnxs|rubiconproject/, (r) => r.abort());
  const page = ctx.pages()[0] || await ctx.newPage();
  page.on('dialog', (d) => { console.log('DIALOG:', d.message().slice(0, 300)); d.accept().catch(() => {}); });
  let err = '';
  try {
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
    await page.waitForTimeout(3000);
    const click = async (src) => {
      for (const fr of page.frames()) {
        let t = null;
        try {
          t = await fr.evaluate((src) => {
            const rx = new RegExp(src, 'i');
            const vis = (e) => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const label = (e) => (e.innerText || e.value || e.getAttribute('aria-label') || e.title || '').trim();
            const btns = [...document.querySelectorAll('button, a, [role=button], [onclick], input[type=button], input[type=submit], [tabindex]')];
            let el = btns.find((e) => { const t = label(e); return t.length < 60 && rx.test(t) && vis(e); });
            if (!el) el = [...document.querySelectorAll('div, span, p, li, label, td')].find((e) => { const t = label(e); return t.length < 60 && rx.test(t) && vis(e) && !(e.firstElementChild && rx.test(label(e.firstElementChild))); });
            if (!el) return null; el.scrollIntoView({ block: 'center' }); el.click(); return label(el);
          }, src);
        } catch (e) {}
        if (t !== null) { console.log(`click(${src}): clicked "${t.slice(0, 40)}"${fr === page.mainFrame() ? '' : ' (in frame ' + fr.url().slice(0, 60) + ')'}`); await page.waitForTimeout(900); return true; }
      }
      console.log(`click(${src}): not found`); await page.waitForTimeout(300); return false;
    };
    const frame = (part) => page.frames().find((f) => f.url().includes(part)) || page.mainFrame();
    const type = async (s) => { await page.keyboard.type(String(s), { delay: 35 }); await page.keyboard.press('Enter'); await page.waitForTimeout(1100); };
    const key = async (k) => { await page.keyboard.press(k); await page.waitForTimeout(300); };
    const tap = async (x, y) => { await page.mouse.click(x, y); await page.waitForTimeout(700); };
    const wait = (ms) => page.waitForTimeout(ms);
    if (steps.trim()) await (new Function('page', 'click', 'type', 'key', 'tap', 'wait', 'frame', `return (async () => { ${steps} })();`))(page, click, type, key, tap, wait, frame);
    await page.waitForTimeout(1500);
  } catch (e) { err = String(e).slice(0, 300); }
  let got = [];
  for (const fr of page.frames()) { try { got = got.concat(await fr.evaluate(() => window.__shares || [])); } catch (e) {} }
  const all = fs.existsSync(sharesFile) ? JSON.parse(fs.readFileSync(sharesFile, 'utf8')) : [];
  for (const s of got) if (!all.some((a) => a.text === s.text)) all.push(s);
  fs.writeFileSync(sharesFile, JSON.stringify(all, null, 1));
  try { await page.screenshot({ path: path.join(dir, 'shot.png') }); } catch (e) {}
  let text = ''; try { text = await page.evaluate(() => document.body.innerText); } catch (e) {}
  console.log('URL:', page.url());
  const others = page.frames().filter((f) => f !== page.mainFrame() && f.url().startsWith('http')).map((f) => f.url().slice(0, 100));
  if (others.length) console.log('FRAMES:', others.join(' | '));
  if (err) console.log('ERROR:', err);
  console.log('--- PAGE TEXT (first 1500 chars) ---\n' + text.slice(0, 1500));
  console.log('--- CAPTURED SHARES (all runs) ---\n' + (all.length ? all.map((s) => `[${s.kind}]\n${s.text}`).join('\n-----\n') : '(none yet)'));
  await ctx.close();
})();
