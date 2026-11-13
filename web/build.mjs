#!/usr/bin/env node
/*
 * Builds the Dailies web app into dist-web/ from the same hub the Android app ships
 * (app/src/main/assets), plus the web-only files in web/. No dependencies: `node web/build.mjs`.
 *
 * index.html gets the web app's head tags (manifest, icons, iPhone settings) and web.js / web.css;
 * local file URLs get ?v=<version> so a new deploy never mixes old and new files from a cache.
 */
import { createHash } from 'node:crypto';
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const assets = join(root, 'app/src/main/assets');
const web = join(root, 'web');
const out = join(root, process.argv[2] || 'dist-web');

rmSync(out, { recursive: true, force: true });
mkdirSync(join(out, 'fonts'), { recursive: true });
mkdirSync(join(out, 'icons'), { recursive: true });

const copies = [
  [join(assets, 'hub-core.js'), 'hub-core.js'],
  [join(assets, 'known-games.js'), 'known-games.js'],
  [join(assets, 'app.css'), 'app.css'],
  [join(web, 'web.js'), 'web.js'],
  [join(web, 'web.css'), 'web.css'],
  [join(web, 'manifest.webmanifest'), 'manifest.webmanifest']
];
for (const f of readdirSync(join(assets, 'fonts'))) copies.push([join(assets, 'fonts', f), `fonts/${f}`]);
for (const f of readdirSync(join(web, 'icons'))) copies.push([join(web, 'icons', f), `icons/${f}`]);
for (const [from, to] of copies) cpSync(from, join(out, to));

let html = readFileSync(join(assets, 'index.html'), 'utf8');
const hash = createHash('sha256');
hash.update(html);
for (const [from] of copies) hash.update(readFileSync(from));
const version = hash.digest('hex').slice(0, 12);
const v = (path) => `${path}?v=${version}`;

const head = [
  '<meta name="theme-color" content="#121118" />',
  '<meta name="description" content="Your daily puzzle games in one place: streaks, results, stats and reset countdowns." />',
  '<meta name="mobile-web-app-capable" content="yes" />',
  '<meta name="apple-mobile-web-app-capable" content="yes" />',
  '<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent" />',
  '<meta name="apple-mobile-web-app-title" content="Dailies" />',
  `<link rel="manifest" href="${v('manifest.webmanifest')}" />`,
  `<link rel="icon" type="image/svg+xml" href="${v('icons/icon.svg')}" />`,
  `<link rel="icon" type="image/png" sizes="192x192" href="${v('icons/icon-192.png')}" />`,
  `<link rel="apple-touch-icon" href="${v('icons/icon-180.png')}" />`,
  `<link rel="stylesheet" href="${v('web.css')}" />`,
  `<script src="${v('web.js')}"></script>`
].map((l) => `  ${l}`).join('\n');

const viewport = /(<meta name="viewport"[^>]*>\n)/;
if (!viewport.test(html)) throw new Error('index.html: viewport meta not found');
html = html.replace(viewport, `$1${head}\n`);
for (const name of ['app.css', 'hub-core.js', 'known-games.js']) {
  const before = html;
  html = html.replace(new RegExp(`(src|href)="${name.replace('.', '\\.')}"`, 'g'), `$1="${v(name)}"`);
  if (html === before) throw new Error(`index.html: no reference to ${name}`);
}
if (/<title>[^<]*<\/title>/.test(html) === false) throw new Error('index.html: no <title>');
writeFileSync(join(out, 'index.html'), html);

// The URLs the page really requests: versioned where index.html links them, plain where app.css
// (fonts) or the manifest (icons) does.
const versioned = ['app.css', 'hub-core.js', 'known-games.js', 'web.js', 'web.css', 'manifest.webmanifest',
  'icons/icon.svg', 'icons/icon-192.png', 'icons/icon-180.png'];
const plain = copies.map(([, to]) => to).filter((to) => (to.startsWith('fonts/') && to.endsWith('.woff2')) || to.startsWith('icons/'));
const files = ['./', 'index.html', ...versioned.map(v), ...plain];
const sw = readFileSync(join(web, 'sw.js'), 'utf8')
  .replace('__VERSION__', version)
  .replace('__FILES__', JSON.stringify(files, null, 2));
writeFileSync(join(out, 'sw.js'), sw);
writeFileSync(join(out, '.nojekyll'), '');
if (existsSync(join(out, 'index.html'))) console.log(`Dailies web app ${version} built in ${out} (${copies.length + 2} files)`);
