// Unit tests for app/src/main/assets/hub-core.js. Run with:  node --test app/src/test/js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');

const core = require(path.join(__dirname, '../../main/assets/hub-core.js'));
const cases = JSON.parse(fs.readFileSync(path.join(__dirname, '../resources/schedule_cases.json'), 'utf8'));

function inZone(tz, fn) {
  const prev = process.env.TZ;
  process.env.TZ = tz;
  try { return fn(); } finally { process.env.TZ = prev; }
}

test('cycle keys match the shared Kotlin/JS fixtures', () => {
  for (const c of cases.cycleKey) {
    const key = inZone(c.tz, () => core.getCycleDateKey(c.resetTime, c.resetZone, new Date(c.now)));
    assert.equal(key, c.expect, c.note);
  }
});

test('ms until next reset match the shared fixtures', () => {
  for (const c of cases.msUntilNextReset) {
    const ms = inZone(c.tz, () => core.getMsUntilNextReset(c.resetTime, c.resetZone, new Date(c.now)));
    assert.equal(ms, c.expect, c.note);
  }
});

test('a UTC reset shows the right local time on both sides of a DST change', () => {
  inZone('America/New_York', () => {
    const winter = core.getLocalResetHourMinute('00:00', 'utc', new Date('2026-01-15T12:00:00Z'));
    const summer = core.getLocalResetHourMinute('00:00', 'utc', new Date('2026-07-15T12:00:00Z'));
    assert.deepEqual(winter, { hour: 19, minute: 0 });
    assert.deepEqual(summer, { hour: 20, minute: 0 });
  });
});

test('scheduleKeyShift maps flattened local times onto UTC days', () => {
  inZone('America/New_York', () => {
    const ref = new Date('2026-01-15T12:00:00Z');
    assert.equal(core.scheduleKeyShift({ resetTime: '19:00', resetZone: 'local' }, { resetTime: '00:00', resetZone: 'utc' }, ref), 1);
  });
  inZone('Europe/Berlin', () => {
    const ref = new Date('2026-01-15T12:00:00Z');
    assert.equal(core.scheduleKeyShift({ resetTime: '01:00', resetZone: 'local' }, { resetTime: '00:00', resetZone: 'utc' }, ref), 0);
  });
});

test('streaks and totals come from dayHistory', () => {
  inZone('UTC', () => {
    const now = new Date('2026-03-10T12:00:00Z');
    const game = {
      resetTime: '00:00', resetZone: 'utc', status: 'ready',
      dayHistory: {
        '2026-03-01': 'completed', '2026-03-02': 'completed', '2026-03-03': 'completed',
        '2026-03-04': 'missed',
        '2026-03-08': 'completed', '2026-03-09': 'completed'
      }
    };
    core.recomputeGameStatsFromHistory(game, now);
    assert.equal(game.streak, 2, 'yesterday + day before, today still open');
    assert.equal(game.bestStreak, 3);
    assert.equal(game.totalPlayed, 6);
    assert.equal(game.totalWon, 5);
    assert.equal(game.status, 'ready');

    game.dayHistory['2026-03-10'] = 'completed';
    core.recomputeGameStatsFromHistory(game, now);
    assert.equal(game.streak, 3);
    assert.equal(game.status, 'completed');
  });
});

test('history is never pruned, even with years of days', () => {
  inZone('UTC', () => {
    const dayHistory = {};
    for (let i = 0; i < 3000; i++) dayHistory[core.addDaysToCycleKey('2018-01-01', i)] = 'completed';
    const game = { resetTime: '00:00', resetZone: 'utc', dayHistory };
    core.recomputeGameStatsFromHistory(game, new Date('2026-03-10T12:00:00Z'));
    assert.equal(Object.keys(game.dayHistory).length, 3000);
    assert.equal(game.totalWon, 3000);
  });
});

test('shiftDayHistory and cleanDayHistory', () => {
  assert.deepEqual(core.shiftDayHistory({ '2026-02-28': 'completed', bad: 'x' }, 1), { '2026-03-01': 'completed' });
  assert.deepEqual(core.cleanDayHistory({ '2026-01-01': 'completed', '2026-01-02': 'weird', nope: 'missed' }), { '2026-01-01': 'completed' });
});

test('indexHistoryLogs finds rows by game id or legacy name', () => {
  const idx = core.indexHistoryLogs([
    { date: '2026-01-01', gameId: 'a', outcome: 'Completed' },
    { date: '2026-01-02', gameName: 'Legacy', outcome: 'Missed' }
  ]);
  assert.equal(idx.get('a|2026-01-01').outcome, 'Completed');
  assert.equal(idx.get('name:Legacy|2026-01-02').outcome, 'Missed');
});

test('looksLikeShareResult accepts share grids and rejects ordinary clipboard text', () => {
  const g = { name: 'FE Guess', url: 'https://fe-guess.com/' };
  assert.equal(core.looksLikeShareResult('Wordle 1,234 3/6\n\n⬛🟨⬛⬛⬛\n🟩🟩🟩🟩🟩', g), true);
  assert.equal(core.looksLikeShareResult('I got FE Guess in 4!', g), true);
  assert.equal(core.looksLikeShareResult('https://fe-guess.com/ daily', g), true);
  assert.equal(core.looksLikeShareResult('Puzzle #88\n4/8', g), true);
  assert.equal(core.looksLikeShareResult('buy milk and eggs', g), false);
  assert.equal(core.looksLikeShareResult('meeting at 3/4 pm', g), false);
  assert.equal(core.looksLikeShareResult('', g), false);
});

test('named zones: stored as found, displayed in local time', () => {
  assert.equal(core.normalizeZone('America/New_York'), 'America/New_York');
  assert.equal(core.normalizeZone('Etc/UTC'), 'utc');
  assert.equal(core.normalizeZone('Not/AZone'), 'local');
  assert.equal(core.normalizeZone(''), 'local');
  inZone('Europe/London', () => {
    // 9 AM Eastern is 2 PM in London in January, 1 PM in the 3 weeks the US is on DST and the UK isn't.
    assert.deepEqual(core.getLocalResetHourMinute('09:00', 'America/New_York', new Date('2026-01-15T00:00:00Z')), { hour: 14, minute: 0 });
    assert.deepEqual(core.getLocalResetHourMinute('09:00', 'America/New_York', new Date('2026-03-15T00:00:00Z')), { hour: 13, minute: 0 });
  });
  assert.equal(core.zoneLabel('America/Los_Angeles'), 'Los Angeles time');
});

test('strict share detection matches the shared Kotlin/JS fixtures', () => {
  const sc = JSON.parse(fs.readFileSync(path.join(__dirname, '../resources/share_cases.json'), 'utf8'));
  for (const c of sc.cases) {
    const game = { name: c.name || sc.game.name, url: c.url || sc.game.url };
    assert.equal(core.isStrictShareResult(c.text, game), c.strict, JSON.stringify(c.text));
  }
});

test('stats follow history down again (undo / relabel), keeping a legacy baseline', () => {
  inZone('UTC', () => {
    const now = new Date('2026-03-10T12:00:00Z');
    const g = { resetTime: '00:00', resetZone: 'utc', dayHistory: { '2026-03-08': 'completed', '2026-03-09': 'completed' } };
    core.recomputeGameStatsFromHistory(g, now);
    g.dayHistory['2026-03-10'] = 'completed'; core.recomputeGameStatsFromHistory(g, now);
    assert.deepEqual([g.streak, g.bestStreak, g.totalPlayed, g.totalWon], [3, 3, 3, 3]);
    delete g.dayHistory['2026-03-10']; core.recomputeGameStatsFromHistory(g, now);
    assert.deepEqual([g.streak, g.bestStreak, g.totalPlayed, g.totalWon], [2, 2, 2, 2]);
    g.dayHistory['2026-03-09'] = 'missed'; core.recomputeGameStatsFromHistory(g, now);
    assert.deepEqual([g.streak, g.totalPlayed, g.totalWon], [0, 2, 1]);
    // Legacy counters from before day history existed are kept as a baseline.
    const old = { resetTime: '00:00', resetZone: 'utc', totalPlayed: 40, totalWon: 30, bestStreak: 12, dayHistory: { '2026-03-09': 'completed' } };
    core.recomputeGameStatsFromHistory(old, now);
    assert.deepEqual([old.totalPlayed, old.totalWon, old.bestStreak], [40, 30, 12]);
    old.dayHistory['2026-03-10'] = 'completed'; core.recomputeGameStatsFromHistory(old, now);
    assert.deepEqual([old.totalPlayed, old.totalWon], [41, 31]);
  });
});

test('uniqueId never loops and skips taken suffixes', () => {
  const seen = new Set(['a-2', 'a', 'a-3']);
  assert.equal(core.uniqueId('a', seen), 'a-4');
  assert.equal(core.uniqueId('b', seen), 'b');
});

test('zone ids are canonicalised to the spelling Java accepts', () => {
  assert.equal(core.normalizeZone('america/new_york'), 'America/New_York');
  assert.equal(core.normalizeZone('EUROPE/LONDON'), 'Europe/London');
});

test('scheduleKeyShift keeps plays on the most-overlapping day when a wrong reset is corrected', () => {
  inZone('America/New_York', () => {
    // "00:00 local" corrected to "00:00 UTC" (7 PM New York): the old Nov 8 local day overlaps the
    // Nov 8 UTC puzzle for 19 of its 24 hours, so history must not move.
    const ref = new Date('2026-11-09T01:00:00Z'); // 8 PM New York, after the UTC reset
    assert.equal(core.scheduleKeyShift({ resetTime: '00:00', resetZone: 'local' }, { resetTime: '00:00', resetZone: 'utc' }, ref), 0);
    assert.equal(core.scheduleKeyShift({ resetTime: '00:00', resetZone: 'local' }, { resetTime: '09:00', resetZone: 'local' }, ref), 0);
    // Same instant, different label still shifts exactly.
    assert.equal(core.scheduleKeyShift({ resetTime: '19:00', resetZone: 'local' }, { resetTime: '00:00', resetZone: 'utc' }, ref), 1);
  });
});

test('isFailedShareResult spots unsolved results', () => {
  const failed = [
    'Wordle 1,238 X/6\n\n⬛🟨⬛⬛⬛\n⬛⬛⬛⬛⬛',
    'Wordle 1,238 x/6*',
    'Framed #900\n🎥 🟥 🟥 🟥 🟥 🟥 🟥\nNext time!',
    'Connections\nPuzzle #500\n🟨🟩🟨🟨\n🟦🟦🟪🟦\n🟩🟨🟩🟩\n🟪🟪🟦🟪',
    'Better luck tomorrow — Globle #12'
  ];
  const solved = [
    'Wordle 1,238 3/6\n\n⬛🟨⬛⬛⬛\n🟩🟩🟩🟩🟩',
    'Connections\nPuzzle #500\n🟨🟩🟨🟨\n🟨🟨🟨🟨\n🟩🟩🟩🟩\n🟦🟦🟦🟦\n🟪🟪🟪🟪',
    'Nerdle 900 4/6 nerdlegame.com',
    'Box 4x/6 tiles',
    ''
  ];
  failed.forEach((t) => assert.equal(core.isFailedShareResult(t), true, t));
  solved.forEach((t) => assert.equal(core.isFailedShareResult(t), false, t));
});
