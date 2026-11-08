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

test('every catalogue entry has a valid schedule', () => {
  for (const e of known.CATALOG) {
    assert.match(e.resetTime, /^\d{2}:\d{2}$/, e.name);
    assert.ok(e.resetZone === 'local' || e.resetZone === 'utc', e.name);
    assert.ok(e.host && !e.host.startsWith('www.'), e.name);
  }
});
