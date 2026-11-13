'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const known = require(path.join(__dirname, '../../main/assets/known-games.js'));

test('known games match by host and path prefix', () => {
  assert.equal(known.find('https://www.nytimes.com/games/wordle/index.html').name, 'Wordle');
  assert.equal(known.find('nytimes.com/games/connections').name, 'Connections');
  assert.equal(known.find('https://www.nytimes.com/crosswords'), null);
  assert.equal(known.find('https://nerdlegame.com/').resetZone, 'utc');
  assert.equal(known.find('https://wafflegame.net/daily').name, 'Waffle');
  assert.equal(known.find('https://example.com/'), null);
  assert.equal(known.find('not a url at all %%%'), null);
});

const validZone = (z) => { try { new Intl.DateTimeFormat('en-US', { timeZone: z }); return true; } catch (e) { return false; } };

test('every catalogue entry has a valid schedule', () => {
  for (const e of known.CATALOG) {
    assert.match(e.resetTime, /^\d{2}:\d{2}$/, e.name);
    assert.ok(e.resetZone === 'local' || e.resetZone === 'utc' || validZone(e.resetZone), e.name);
    assert.ok(e.host && !e.host.startsWith('www.'), e.name);
  }
});

test('1.4.0: popular games, including the ones asked for', () => {
  assert.ok(known.CATALOG.length >= 54);
  const keys = new Set();
  for (const e of known.CATALOG) {
    const k = `${e.host}${e.path || ''}`;
    assert.ok(!keys.has(k), `duplicate ${k}`);
    keys.add(k);
    if (e.url) assert.ok(known.find(e.url) && known.find(e.url).name === e.name, `url of ${e.name} matches itself`);
  }
  assert.equal(known.find('https://fe-guess.com/').name, 'FE Guess');
  assert.equal(known.find('https://globle-game.com/game').name, 'Globle');
  assert.equal(known.find('https://krillion.io/').name, 'Krillion · The Daily Dive');
  const nk = known.find('https://n-puzzle.com/en/nintendoku');
  assert.equal(nk.name, 'Nintendoku');
  assert.deepEqual([nk.resetTime, nk.resetZone], ['06:00', 'Europe/Paris']);
  assert.equal(known.find('https://www.linkedin.com/games/queens/').resetZone, 'America/Los_Angeles');
  assert.equal(known.find('https://www.nytimes.com/puzzles/spelling-bee').resetTime, '03:00');
});
