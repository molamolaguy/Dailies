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
    'Framed #1234\n🎥 🟥 🟥 🟥 🟥 🟥 🟥\n\nhttps://framed.wtf',
    'Connections\nPuzzle #500\n🟨🟩🟨🟨\n🟦🟦🟪🟦\n🟩🟨🟩🟩\n🟪🟪🟦🟪',
    'Better luck tomorrow — Globle #12'
  ];
  const solved = [
    'Wordle 1,238 3/6\n\n⬛🟨⬛⬛⬛\n🟩🟩🟩🟩🟩',
    'Connections\nPuzzle #500\n🟨🟩🟨🟨\n🟨🟨🟨🟨\n🟩🟩🟩🟩\n🟦🟦🟦🟦\n🟪🟪🟪🟪',
    'Nerdle 900 4/6 nerdlegame.com',
    'Framed #1234\n🎥 🟥 🟥 🟩 ⬛ ⬛ ⬛',
    'Travle #400 +2\n✅✅🟧✅',
    'Box 4x/6 tiles',
    ''
  ];
  failed.forEach((t) => assert.equal(core.isFailedShareResult(t), true, t));
  solved.forEach((t) => assert.equal(core.isFailedShareResult(t), false, t));
});

test('scoreStats: win rate and average (1.1.0)', () => {
  const rows = [
    { outcome: 'Completed', score: 'Wordle 1,234 3/6\n⬛🟩🟩🟩🟩' },
    { outcome: 'Completed', score: '4/6' },
    { outcome: 'Missed', score: 'Wordle 1,235 X/6' },
    { outcome: 'Completed', score: '' },
    { outcome: 'Completed', score: 'Nerdle 2/6' },
    { outcome: 'Completed', score: '7/6' }
  ];
  const st = core.scoreStats(rows);
  assert.equal(st.played, 6);
  assert.equal(st.won, 5);
  assert.equal(st.winRate, 83);
  assert.equal(st.max, 6);
  assert.equal(st.avg, 3);
  assert.equal(st.dist, undefined); // 1.3.0: no distribution any more
  // Free-form scores: win rate only.
  const plain = core.scoreStats([{ outcome: 'Completed', score: '2,450 pts' }, { outcome: 'Missed', score: '' }]);
  assert.equal(plain.winRate, 50);
  assert.equal(plain.avg, null);
  assert.equal(core.scoreStats([]).winRate, null);
  assert.equal(core.scoreStats(null).played, 0);
});

test('1.2.0: play times, records, achievements and monthly recap', () => {
  assert.equal(core.formatDuration(65000), '1m 05s');
  assert.equal(core.formatDuration(42000), '42s');
  assert.equal(core.formatDuration(3780000), '1h 03m');
  assert.deepEqual(core.parseScore('Wordle 1,600 X/6'), { n: null, max: 6, fail: true });
  assert.equal(core.parseScore('no score'), null);

  const rows = [
    { outcome: 'Completed', score: '4/6', timeMs: 180000, streak: 3 },
    { outcome: 'Completed', score: '3/6', timeMs: 120000, streak: 4 },
    { outcome: 'Missed', score: 'X/6', streak: 0 }
  ];
  const st = core.scoreStats(rows);
  assert.equal(st.fastestMs, 120000);
  assert.equal(st.avgMs, 150000);

  const rec = core.newRecords(rows, { outcome: 'Completed', score: '2/6', timeMs: 90000, streak: 5 }, 4);
  assert.deepEqual(rec.map((r) => r.kind), ['score', 'time', 'streak']);
  assert.equal(rec[0].value, '2/6');
  assert.deepEqual(core.newRecords([], { outcome: 'Completed', score: '2/6', timeMs: 1000, streak: 1 }, 0), [], 'a first solve is not a record');
  assert.deepEqual(core.newRecords(rows, { outcome: 'Missed', score: 'X/6' }, 4), []);
  assert.deepEqual(core.personalBests(rows, 4), { bestScore: '3/6', bestKind: 'guesses', fastestMs: 120000, bestStreak: 4 });

  const day = (n) => `2026-11-${String(n).padStart(2, '0')}`;
  const hist = (from, to, skip) => { const h = {}; for (let d = from; d <= to; d++) h[day(d)] = d === skip ? 'missed' : 'completed'; return h; };
  const games = [{ id: 'a', dayHistory: hist(1, 9) }, { id: 'b', dayHistory: hist(3, 9, 5) }];
  // b is eligible from the 3rd and missed the 5th, so the best all-solved run is the 6th-9th (4 days)
  assert.equal(core.perfectRun(games), 4);
  assert.equal(core.perfectRun([{ id: 'a', dayHistory: hist(1, 9) }]), 9);

  const logs = [];
  for (let d = 1; d <= 9; d++) logs.push({ date: day(d), gameId: 'a', gameName: 'Wordle', outcome: 'Completed', score: d === 2 ? '2/6' : '4/6', streak: d, timeMs: d === 3 ? 50000 : 0 });
  const ach = core.computeAchievements([{ id: 'a', bestStreak: 9, dayHistory: hist(1, 9) }], logs);
  const earned = ach.filter((a) => a.earned).map((a) => a.id);
  // 2/6 on the 2nd beat the 4/6 before it: a personal best.
  assert.deepEqual(earned.sort(), ['first', 'lucky', 'perfectWeek', 'records', 'roll', 'speed']);
  assert.equal(ach.find((a) => a.id === 'started').next.progress, 9);

  const recap = core.monthlyRecap(logs.concat([{ date: '2026-10-31', gameName: 'Wordle', outcome: 'Completed', score: '1/6' }]), 2026, 10);
  assert.equal(recap.results, 9);
  assert.equal(recap.winRate, 100);
  assert.equal(recap.bestStreak, 9);
  assert.deepEqual(recap.bestResult, { game: 'Wordle', score: '2/6' });
  assert.deepEqual(recap.topGame, { name: 'Wordle', count: 9 });
  assert.equal(recap.timeMs, 50000);
  assert.equal(core.monthlyRecap([], 2026, 10).winRate, null);
});

test('extractResultText keeps the result lines of recognised screenshot text', () => {
  const ocr = '9:41\nWordle\nStatistics\n42\nPlayed\n95\nWin %\nWordle 1,234 3/6\nNext Wordle\n04:12:33\nShare';
  const out = core.extractResultText(ocr);
  assert.ok(out.includes('Wordle 1,234 3/6'));
  assert.ok(!out.includes('Statistics'));
  assert.equal(core.extractResultText(''), '');
  assert.equal(core.extractResultText('just\nsome\nwords'), 'just\nsome\nwords');
});

test('1.3.0: times, points and counts are scored like with like', () => {
  const v = (t) => core.parseResultValue(t);
  assert.deepEqual([v('Wordle 1,234 3/6').kind, v('Wordle 1,234 3/6').label], ['guesses', '3/6']);
  assert.equal(v('Wordle 1,235 X/6'), null);
  assert.deepEqual([v('Mini 1:42').kind, v('Mini 1:42').ms], ['time', 102000]);
  assert.equal(v('Solved in 1m 05s').ms, 65000);
  assert.equal(v('Queens #300 ⏱️ 42s').label, '0:42');
  assert.equal(v('Played at 9:14 AM'), null); // a clock time, not a score
  assert.deepEqual([v('Contexto #812 - 45 guesses').kind, v('Contexto #812 - 45 guesses').n], ['count', 45]);
  assert.deepEqual([v('Score: 2,450').kind, v('Score: 2,450').value, v('Score: 2,450').label], ['points', 2450, '2,450 pts']);
  assert.equal(v('Dove 4,250m').label, '4,250m');
  const kr = v('Krillion #117 🦐\n60\n\n⬛⬛🫧🫧🫧🐟⬛');
  assert.deepEqual([kr.kind, kr.value, kr.label], ['points', 60, '60']); // not the puzzle number
  assert.equal(v('Strands #200\n🔵🟡'), null);

  // Points: higher is better; times: lower is better.
  const pts = [{ outcome: 'Completed', score: 'Krillion #117\n60' }, { outcome: 'Completed', score: 'Krillion #118\n85' },
    { outcome: 'Missed', score: 'Krillion #119\n99' }, { outcome: 'Completed', score: 'Krillion #120\n40' }];
  assert.equal(core.personalBests(pts, 0).bestScore, '85');
  assert.equal(core.scoreStats(pts).avgText, '61.7');
  assert.deepEqual(core.newRecords(pts, { outcome: 'Completed', score: 'Krillion #121\n90' }, 0).map((r) => r.value), ['90']);
  assert.deepEqual(core.newRecords(pts, { outcome: 'Completed', score: 'Krillion #121\n70' }, 0), []);
  const times = [{ outcome: 'Completed', score: 'Mini 1:42' }, { outcome: 'Completed', score: 'Mini 0:58' }];
  assert.equal(core.personalBests(times, 0).bestScore, '0:58');
  assert.equal(core.scoreStats(times).scoreKind, 'time');
  assert.equal(core.scoreStats(times).avgText, '1:20');
  assert.deepEqual(core.newRecords(times, { outcome: 'Completed', score: 'Mini 0:45' }, 0).map((r) => r.value), ['0:45']);
  // Guess scores keep working as before.
  assert.equal(core.personalBests([{ outcome: 'Completed', score: '4/6' }, { outcome: 'Completed', score: '2/6' }], 0).bestScore, '2/6');
});

test('1.4.0: puzzle numbers and auto-detected reset times', () => {
  assert.equal(core.puzzleNumber('Krillion #117 🦐\n60'), 117);
  assert.equal(core.puzzleNumber('Wordle 1,234 3/6\n🟩🟩'), 1234);
  assert.equal(core.puzzleNumber('Nerdle 123 3/6'), 123);
  assert.equal(core.puzzleNumber('Connections\nPuzzle #512'), 512);
  assert.equal(core.puzzleNumber('Score: 2,450'), null);
  assert.equal(core.puzzleNumber('Mini 1:42'), null);
  assert.equal(core.puzzleNumber('Dove 4,250m'), null);

  const row = (n, iso) => ({ score: `Krillion #${n}`, loggedAt: Date.parse(iso) });
  // Really resets at midnight New York time (05:00 UTC in November); set up as midnight UTC.
  const ny = [row(100, '2026-11-02T02:00:00Z'), row(101, '2026-11-02T06:00:00Z'), row(101, '2026-11-02T23:00:00Z'),
    row(102, '2026-11-03T05:30:00Z'), row(102, '2026-11-04T04:30:00Z'), row(103, '2026-11-04T06:00:00Z')];
  assert.deepEqual(core.suggestResetTime(ny, { resetTime: '00:00', resetZone: 'utc' }), { resetTime: '00:00', resetZone: 'America/New_York' });
  // Already right: nothing to suggest.
  assert.equal(core.suggestResetTime(ny, { resetTime: '00:00', resetZone: 'America/New_York' }), null);
  // Too little evidence, or rows from before 1.4.0 without a logged time.
  assert.equal(core.suggestResetTime(ny.slice(0, 2), { resetTime: '00:00', resetZone: 'utc' }), null);
  assert.equal(core.suggestResetTime(ny.map((r) => ({ score: r.score })), { resetTime: '00:00', resetZone: 'utc' }), null);
  // Ambiguous (many midnights fit, none local or UTC): wait for more results.
  const wide = [row(100, '2026-11-01T10:00:00Z'), row(101, '2026-11-01T21:00:00Z'), row(102, '2026-11-03T08:00:00Z'), row(103, '2026-11-03T22:00:00Z')];
  assert.equal(core.suggestResetTime(wide, { resetTime: '00:00', resetZone: 'utc' }), null);
});

test('1.4.0 review: reset suggestions only when unambiguous', () => {
  const at = (iso) => Date.parse(iso);
  const row = (n, iso) => ({ score: `Game #${n} 3/6`, loggedAt: at(iso) });
  // Configured right (midnight New York), one result logged just after the reset with yesterday's number.
  const late = [row(1000, '2026-06-15T02:00:00Z'), row(1001, '2026-06-16T02:05:00Z'), row(1001, '2026-06-16T04:02:00Z'),
    row(1002, '2026-06-17T01:00:00Z'), row(1003, '2026-06-18T02:00:00Z')];
  assert.equal(core.suggestResetTime(late, { resetTime: '00:00', resetZone: 'America/New_York' }), null);
  // An old (archive) puzzle among the rows.
  const archive = [row(1000, '2026-06-15T12:00:00Z'), row(1001, '2026-06-16T12:00:00Z'), row(700, '2026-06-16T13:00:00Z'),
    row(1002, '2026-06-17T12:00:00Z'), row(1003, '2026-06-18T12:00:00Z')];
  assert.equal(core.suggestResetTime(archive, { resetTime: '00:00', resetZone: 'utc' }), null);
  // Wrong, but the results only narrow it to "some time in the evening": wait.
  const vague = [row(1000, '2026-06-15T15:00:00Z'), row(1001, '2026-06-16T22:00:00Z'), row(1001, '2026-06-17T14:00:00Z'),
    row(1002, '2026-06-17T23:30:00Z'), row(1003, '2026-06-18T23:00:00Z')];
  assert.equal(core.suggestResetTime(vague, { resetTime: '00:00', resetZone: 'America/New_York' }), null);
});

test('1.4.0 review: changing the schedule keeps a just-logged result on its day', () => {
  const now = new Date('2026-10-30T05:00:00Z'); // 14:00 in Tokyo
  const from = { resetTime: '00:00', resetZone: 'America/New_York' };
  const to = { resetTime: '00:00', resetZone: 'Asia/Tokyo' };
  const hist = { '2026-10-27': 'completed', '2026-10-28': 'completed', '2026-10-29': 'completed', '2026-10-30': 'completed' };
  const rows = [{ date: '2026-10-30', outcome: 'Completed', loggedAt: now.getTime() - 60000 }, { date: '2026-10-29', outcome: 'Completed' }];
  const out = core.rescheduleDays(hist, rows, from, to, now);
  const today = core.getCycleDateKey(to.resetTime, to.resetZone, now);
  assert.equal(out.dates[0], today);
  assert.ok(Object.keys(out.dayHistory).every((k) => k <= today), JSON.stringify(out.dayHistory));
  assert.equal(out.dayHistory[today], 'completed');
});

test('1.4.0 review: score parsing', () => {
  const p = (t) => { const v = core.parseResultValue(t); return v ? `${v.kind} ${v.label}` : null; };
  assert.equal(p('1h 3m'), 'time 1:03:00');
  assert.equal(p('58m'), 'time 58:00');
  assert.equal(p('Krillion dive 4,250m'), 'points 4,250m');
  assert.equal(p('Squaredle 11/13:\n📖 42/42 words'), null);
  assert.equal(p('TimeGuessr #890 38,412/50,000'), 'points 38,412 pts');
  assert.equal(p('12.5 pts'), 'points 12.5 pts');
  assert.equal(p('-50 pts'), 'points -50 pts');
  assert.equal(p('High score: 900 Score: 100'), 'points 100 pts');
  assert.equal(p('putt.day #210 ⛳\n3 strokes'), 'count 3 strokes');
  assert.equal(p('FE Guess 512 - guessed in 4'), 'count 4 guesses');
  assert.equal(core.formatClock(Infinity), '0:00');
  assert.equal(core.puzzleNumber('I solved Clues by Sam, Nov 13th 2026 (Hard) in 05:12'), null);
  assert.equal(core.puzzleNumber('Moviedle #2026-11-13'), null);
  assert.equal(core.isFailedShareResult('Daily Quordle 1389\n🟥🟥\n6️⃣4️⃣'), true);
  assert.equal(core.isFailedShareResult('I failed to resist: Wordle 1,234 3/6'), false);
});

test('1.5.0: two-tier achievements, old badges carried over', () => {
  assert.equal(core.ACHIEVEMENTS.length, 19);
  assert.equal(core.ALL_TIER_KEYS.length, 38);
  assert.deepEqual(core.migrateAchievementKeys(['first', 'solves100', 'streak365', 'speed', 'roll#2', 'future#1', 42]).sort(),
    ['centurion#1', 'centurion#2', 'first#1', 'future#1', 'roll#2', 'speed#1', 'started#1', 'started#2'].sort());

  const day = (n) => `2026-11-${String(n).padStart(2, '0')}`;
  // Midnight-UTC game: one solve 10 min after the reset, one 20 min before the next.
  const g = { id: 'a', name: 'A', resetTime: '00:00', resetZone: 'utc', bestStreak: 3,
    dayHistory: { [day(2)]: 'missed', [day(3)]: 'completed', [day(4)]: 'completed', [day(7)]: 'completed', [day(8)]: 'completed' } };
  const rows = [
    { gameId: 'a', date: day(3), outcome: 'Completed', score: 'A #3 1/6', loggedAt: Date.parse('2026-11-03T00:10:00Z'), timeMs: 15000 },
    { gameId: 'a', date: day(4), outcome: 'Completed', score: 'A #4 3/6', loggedAt: Date.parse('2026-11-04T23:40:00Z') }
  ];
  const m = core.achievementMetrics([g], rows);
  assert.equal(m.early, 1);
  assert.equal(m.late, 1);
  assert.equal(m.holeInOne, 1);
  assert.equal(m.lucky, 1);
  assert.equal(m.bounces, 1);
  assert.equal(m.weekends, 1, 'Sat 7th and Sun 8th');
  assert.equal(m.fastest, 15000);
  const ach = core.computeAchievements([g], rows);
  const speed = ach.find((a) => a.id === 'speed');
  assert.equal(speed.tier, 2, 'under 20 seconds');
  assert.deepEqual(speed.keys, ['speed#1', 'speed#2']);
  assert.equal(speed.next, null);
  const first = ach.find((a) => a.id === 'first');
  assert.equal(first.tier, 1);
  assert.deepEqual(first.next, { desc: 'Solve a puzzle in 5 different games', progress: 1, target: 5, below: false, unit: '' });
  assert.deepEqual(first.tierList.map((t) => t.earned), [true, false]);
  // Marathon counts whole hours (rounded), not milliseconds.
  const marathon = core.computeAchievements([{ id: 'a' }], [{ gameId: 'a', date: '2026-11-01', outcome: 'Completed', score: 'x', timeMs: 3.6 * 3600000 }])
    .find((x) => x.id === 'marathon');
  assert.deepEqual([marathon.next.progress, marathon.next.target, marathon.next.unit], [4, 10, 'hours']);
  // Clean sweep needs at least 3 games, all solved on the day.
  const three = ['a', 'b', 'c'].map((id) => ({ id, dayHistory: { [day(1)]: 'completed', [day(2)]: id === 'c' ? 'missed' : 'completed' } }));
  assert.equal(core.achievementMetrics(three, []).sweeps, 1);
});

// ---- 1.6.0 ----
const H = core;
test('weekBounds: Monday to Sunday', () => {
  assert.deepStrictEqual(H.weekBounds('2026-11-13'), { from: '2026-11-09', to: '2026-11-15' });
  assert.deepStrictEqual(H.weekBounds('2026-11-15'), { from: '2026-11-09', to: '2026-11-15' });
  assert.deepStrictEqual(H.weekBounds('2026-11-16'), { from: '2026-11-16', to: '2026-11-22' });
  assert.deepStrictEqual(H.weekBounds('2027-01-01'), { from: '2026-12-28', to: '2027-01-03' });
});

test('periodRecap and recapChange: a week compared with the one before', () => {
  const logs = [
    { date: '2026-11-10', gameName: 'Wordle', outcome: 'Completed', score: 'Wordle 1 3/6', streak: 4 },
    { date: '2026-11-11', gameName: 'Wordle', outcome: 'Completed', score: 'Wordle 2 2/6', streak: 5 },
    { date: '2026-11-11', gameName: 'Strands', outcome: 'Missed', score: '', streak: 0 },
    { date: '2026-11-12', gameName: 'Strands', outcome: 'Completed', score: '', streak: 1 },
    { date: '2026-11-03', gameName: 'Wordle', outcome: 'Completed', score: 'Wordle 0 4/6', streak: 2 },
    { date: '2026-11-08', gameName: 'Wordle', outcome: 'Missed', score: 'X/6', streak: 0 }
  ];
  const r = H.periodRecap(logs, '2026-11-09', '2026-11-15');
  assert.strictEqual(r.results, 4);
  assert.strictEqual(r.solved, 3);
  assert.strictEqual(r.winRate, 75);
  assert.strictEqual(r.games, 2);
  assert.strictEqual(r.days, 3);
  assert.deepStrictEqual(r.bestGame, { name: 'Wordle', solved: 2, results: 2 });
  assert.deepStrictEqual(r.bestResult, { game: 'Wordle', score: '2/6' });
  const prev = H.periodRecap(logs, '2026-11-02', '2026-11-08');
  assert.deepStrictEqual(H.recapChange(r, prev), { solved: 2, winRate: 25 });
  assert.strictEqual(H.recapChange(r, H.periodRecap([], '2026-01-01', '2026-01-07')), null);
  // The monthly recap is the same thing for a month.
  assert.strictEqual(H.monthlyRecap(logs, 2026, 10).results, 6);
});

test('toughDays: much worse than usual, at most the worst 15%', () => {
  const rows = [3, 4, 4, 5, 4, 3, 6, 4, 4, 4].map((n, i) => ({ date: `2026-11-${String(i + 1).padStart(2, '0')}`, outcome: 'Completed', score: `Wordle 1 ${n}/6` }));
  const t = H.toughDays(rows);
  assert.deepStrictEqual([...t.keys()], ['2026-11-07']);
  assert.deepStrictEqual(t.get('2026-11-07'), { label: '6/6', typical: '4/6' });
  // Too few results: nothing is tough yet.
  assert.strictEqual(H.toughDays(rows.slice(0, 4)).size, 0);
  // Times: 1.75x the usual and at least 30 s slower.
  const timed = ['1:00', '1:05', '0:58', '1:02', '3:40', '1:01'].map((t2, i) => ({ date: `2026-10-0${i + 1}`, outcome: 'Completed', score: `Mini ${t2}` }));
  assert.deepStrictEqual([...H.toughDays(timed).keys()], ['2026-10-05']);
  // Points: half the usual or less.
  const pts = [800, 820, 790, 300, 810, 805].map((p, i) => ({ date: `2026-09-0${i + 1}`, outcome: 'Completed', score: `Score: ${p}` }));
  assert.deepStrictEqual([...H.toughDays(pts).keys()], ['2026-09-04']);
});

test('sortGames: custom, A-Z, next reset and least played (ties keep the custom order)', () => {
  const games = [{ name: 'wordle', t: 3, p: 10 }, { name: 'Connections', t: 1, p: 2 }, { name: 'bandle', t: 3, p: 2 }];
  const names = (l) => l.map((g) => g.name);
  const opts = { msUntilReset: (g) => g.t, playCount: (g) => g.p };
  assert.deepStrictEqual(names(H.sortGames(games, 'custom', opts)), ['wordle', 'Connections', 'bandle']);
  assert.deepStrictEqual(names(H.sortGames(games, 'alpha', opts)), ['bandle', 'Connections', 'wordle']);
  assert.deepStrictEqual(names(H.sortGames(games, 'reset', opts)), ['Connections', 'wordle', 'bandle']);
  assert.deepStrictEqual(names(H.sortGames(games, 'least', opts)), ['Connections', 'bandle', 'wordle']);
  assert.deepStrictEqual(names(H.sortGames(games, 'nonsense', opts)), ['wordle', 'Connections', 'bandle']);
});

test('combinedShareText: heading, results, names added where missing, grids optional', () => {
  const entries = [
    { name: 'Wordle', outcome: 'completed', text: 'Wordle 1,234 3/6\n\n⬛🟨⬛⬛⬛\n🟩🟩🟩🟩🟩' },
    { name: 'Krillion', url: 'https://krillion.io/daily', outcome: 'completed', text: '60' },
    { name: 'Strands', outcome: 'missed', text: '' }
  ];
  const withGrids = H.combinedShareText(entries, { dateLabel: 'Fri 13 Nov', total: 4 });
  assert.ok(withGrids.startsWith('🧩 Dailies · Fri 13 Nov — 2/4 solved\n\n'));
  assert.ok(withGrids.includes('🟩🟩🟩🟩🟩'));
  assert.ok(withGrids.includes('✅ Krillion\n60'));
  assert.ok(withGrids.endsWith('❌ Strands'));
  const noGrids = H.combinedShareText(entries, { dateLabel: 'Fri 13 Nov', total: 4, grids: false });
  assert.ok(!noGrids.includes('🟩'));
  assert.ok(noGrids.includes('Wordle 1,234 3/6\n\n✅ Krillion'));
  assert.ok(H.isGridLine('🟩🟩⬛⬛⬛'));
  assert.ok(H.isGridLine('1️⃣ 2️⃣'));
  assert.ok(!H.isGridLine('60'));
  assert.ok(!H.isGridLine('Wordle 3/6 🟩'));
});

test('themeTokens: readable tones from a seed, in and out of gamut', () => {
  const hex = /^#[0-9A-F]{6}$/;
  for (const seed of ['#1E88C8', '#2E8B57', '#F06A3C', '#D9467A', '#FF0000', '#00FF00', '#808080', '#000000', '#FFFFFF']) {
    for (const light of [false, true]) {
      const t = H.themeTokens(seed, light);
      ['--md-surface', '--md-primary', '--md-on-primary', '--md-primary-container', '--md-on-primary-container', '--md-tertiary'].forEach((k) => assert.match(t[k], hex, `${seed} ${k}`));
      const lum = (h) => H.hexToOklch(h).L;
      // Text on the primary colour and on the container stays far apart in lightness.
      assert.ok(Math.abs(lum(t['--md-primary']) - lum(t['--md-on-primary'])) > 0.4, `${seed} ${light} primary contrast`);
      assert.ok(Math.abs(lum(t['--md-primary-container']) - lum(t['--md-on-primary-container'])) > 0.45, `${seed} ${light} container contrast`);
      assert.ok(light ? lum(t['--md-surface']) > 0.95 : lum(t['--md-surface']) < 0.2);
    }
  }
  assert.strictEqual(H.themeTokens('blue', false), null);
  // Round trip.
  const o = H.hexToOklch('#1E88C8');
  assert.strictEqual(H.oklchToHex(o.L, o.C, o.H), '#1E88C8');
});
