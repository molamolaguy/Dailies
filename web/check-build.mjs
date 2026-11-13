#!/usr/bin/env node
// Sanity checks on a web build (node web/check-build.mjs dist-web): every file the page, the
// manifest and the service worker list exists, and the page carries the web app's head tags.
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const dir = process.argv[2] || 'dist-web';
const fail = (m) => { console.error(`✗ ${m}`); process.exitCode = 1; };
const strip = (u) => u.split('?')[0];
const has = (u) => existsSync(join(dir, strip(u) === './' ? 'index.html' : strip(u)));

const html = readFileSync(join(dir, 'index.html'), 'utf8');
for (const tag of ['rel="manifest"', 'apple-touch-icon', 'apple-mobile-web-app-capable', 'viewport-fit=cover', 'web.js?v=']) {
  if (!html.includes(tag)) fail(`index.html is missing ${tag}`);
}
for (const m of html.matchAll(/(?:src|href)="([^"#:$]+)"/g)) if (!has(m[1])) fail(`index.html links a missing file: ${m[1]}`);

const manifest = JSON.parse(readFileSync(join(dir, 'manifest.webmanifest'), 'utf8'));
if (manifest.display !== 'standalone') fail('manifest: display should be standalone');
for (const i of manifest.icons) if (!has(i.src)) fail(`manifest icon missing: ${i.src}`);
if (!manifest.icons.some((i) => i.sizes === '512x512')) fail('manifest: needs a 512×512 icon');

const sw = readFileSync(join(dir, 'sw.js'), 'utf8');
if (sw.includes('__VERSION__') || sw.includes('__FILES__')) fail('sw.js placeholders not filled in');
const files = JSON.parse(sw.match(/const FILES = (\[[\s\S]*?\]);/)[1]);
for (const f of files) if (!has(f)) fail(`sw.js caches a missing file: ${f}`);

if (!process.exitCode) console.log(`✓ web build in ${dir} looks complete (${files.length} cached files)`);
