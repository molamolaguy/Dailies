const test = require('node:test');
const assert = require('node:assert/strict');
const HubCore = require('../../main/assets/hub-core.js');
const H = require('../../main/assets/hub-ui-helpers.js');

test('1.9.4: hubOrder with hubSort === "reset" works with >= 2 games and msUntilNextResetCached coerces timestamps', () => {
  const games = [
    { id: 'late', name: 'Late', resetTime: '23:00', resetZone: 'utc', totalPlayed: 5 },
    { id: 'soon', name: 'Soon', resetTime: '13:00', resetZone: 'utc', totalPlayed: 10 },
    { id: 'mid', name: 'Mid', resetTime: '18:00', resetZone: 'utc', totalPlayed: 1 }
  ];
  const ref = new Date('2026-11-18T12:00:00Z');
  const cache = new Map();

  // Passing a Date instance sorts soonest reset first without throwing.
  const ordered = H.hubOrder(games, 'reset', ref, cache);
  assert.deepEqual(ordered.map((g) => g.id), ['soon', 'mid', 'late']);

  // Passing a numeric timestamp (regression for Date.now() crash) is safely coerced.
  const orderedFromNum = H.hubOrder(games, 'reset', ref.getTime(), new Map());
  assert.deepEqual(orderedFromNum.map((g) => g.id), ['soon', 'mid', 'late']);

  // Direct msUntilNextResetCached call with a numeric timestamp returns exact ms until reset.
  assert.equal(H.msUntilNextResetCached(games[1], ref.getTime(), new Map()), 3600000);

  // Default now (omitted) also succeeds without throwing.
  const orderedDefault = H.hubOrder(games, 'reset');
  assert.equal(orderedDefault.length, 3);
});

test('1.9.4: normalizeGameObject preserves valid altUrls (<=4) and embeds (<=8) and rejects invalid URLs', () => {
  const raw = {
    id: 'costco',
    name: 'Costcodle',
    url: 'https://costcodle.com',
    resetTime: '00:00',
    resetZone: 'utc',
    altUrls: [
      'https://www.costcodle.com/game',
      'http://m.costcodle.com/play',
      'https://www.costcodle.com/game', // duplicate
      'javascript:alert(1)',
      'ftp://files.example.com/a',
      'data:text/html,hi',
      'not-a-url',
      'https://a.example.com/1',
      'https://b.example.com/2',
      'https://c.example.com/3' // exceeds cap of 4
    ],
    embeds: [
      'https://e1.example.com/p',
      'https://e2.example.com/p',
      'https://e3.example.com/p',
      'https://e4.example.com/p',
      'https://e5.example.com/p',
      'https://e6.example.com/p',
      'https://e7.example.com/p',
      'https://e8.example.com/p',
      'https://e9.example.com/p', // exceeds cap of 8
      'javascript:void(0)',
      'file:///etc/passwd'
    ]
  };
  const norm = H.normalizeGameObject(raw, 0, new Date('2026-11-18T12:00:00Z'));
  assert.ok(norm);
  assert.equal(norm.altUrls.length, 4);
  assert.deepEqual(norm.altUrls, [
    'https://www.costcodle.com/game',
    'http://m.costcodle.com/play',
    'https://a.example.com/1',
    'https://b.example.com/2'
  ]);
  assert.equal(norm.embeds.length, 8);
  assert.ok(norm.embeds.every((u) => u.startsWith('https://e')));

  // Duplicate detection via HubCore.findSameGame still works after normalization.
  const same = HubCore.findSameGame({ url: 'https://www.costcodle.com/game' }, [norm]);
  assert.ok(same && same.sure && same.game.id === 'costco');
  const embedded = HubCore.findSameGame({ url: 'https://e1.example.com/p' }, [norm]);
  assert.ok(embedded && embedded.reason === 'embedded' && embedded.game.id === 'costco');

  // When altUrls/embeds contain only invalid values, keys are omitted cleanly.
  const bare = H.normalizeGameObject({
    id: 'bare',
    url: 'https://wordle.example.com',
    altUrls: ['javascript:alert(1)', 'ftp://bad.example.com'],
    embeds: ['data:image/png;base64,abc']
  }, 1, new Date('2026-11-18T12:00:00Z'));
  assert.equal('altUrls' in bare, false);
  assert.equal('embeds' in bare, false);
});

test('1.9.7: dead single-game restore helpers are removed and normalizeUrlKey strips m. alongside www.', () => {
  assert.equal(H.applyRestoreGameState, undefined);
  assert.equal(H.restoreRestoredGameState, undefined);
  assert.equal(H.normalizeUrlKey('https://m.nytimes.com/games/wordle/index.html'), 'nytimes.com/games/wordle');
  assert.equal(H.normalizeUrlKey('https://www.nytimes.com/games/wordle/'), 'nytimes.com/games/wordle');
  assert.equal(H.normalizeUrlKey('https://M.Contexto.me/'), 'contexto.me');
});

test('1.9.7: msUntilNextResetCached isolates cache entries by timezone', () => {
  const cache = new Map();
  const game = { id: 'local-g', name: 'Wordle', resetTime: '00:00', resetZone: 'local' };
  const ref = new Date('2026-06-15T12:00:00Z');
  const prevTz = process.env.TZ;
  try {
    process.env.TZ = 'UTC';
    const utcMs = H.msUntilNextResetCached(game, ref, cache);
    assert.equal(utcMs, 12 * 3600000);
    process.env.TZ = 'America/New_York';
    const nyMs = H.msUntilNextResetCached(game, ref, cache);
    assert.equal(nyMs, 16 * 3600000);
    // Same UTC offset (-04:00) vs another IANA zone with same offset still keys on IANA id
    process.env.TZ = 'America/Toronto';
    const torontoMs = H.msUntilNextResetCached(game, ref, cache);
    assert.equal(torontoMs, 16 * 3600000);
  } finally {
    process.env.TZ = prevTz;
  }
});

test('1.9.4: dedupeHistoryLogs and applyScheduleChangeState deduplicate colliding dates preferring Completed and score text', () => {
  const deduped = H.dedupeHistoryLogs([
    { gameId: 'g1', gameName: 'Wordle', date: '2026-11-18', outcome: 'Missed', score: '' },
    { gameId: 'g1', gameName: 'Wordle', date: '2026-11-18', outcome: 'Completed', score: '' },
    { gameId: 'g1', gameName: 'Wordle', date: '2026-11-18', outcome: 'Completed', score: '3/6' },
    { gameId: 'g2', gameName: 'Other', date: '2026-11-18', outcome: 'Missed', score: '' }
  ]);
  assert.equal(deduped.length, 2);
  assert.equal(deduped[0].gameId, 'g1');
  assert.equal(deduped[0].outcome, 'Completed');
  assert.equal(deduped[0].score, '3/6');
  assert.equal(deduped[1].gameId, 'g2');

  // Schedule change that clamps two rows onto todayKey deduplicates them cleanly and syncs todayScore + streaks.
  const now = new Date('2026-11-18T12:00:00Z');
  const game = {
    id: 'g1',
    name: 'Game',
    resetTime: '00:00',
    resetZone: 'utc',
    todayScore: 'stale score',
    lastCycleKey: '2026-11-18',
    dayHistory: { '2026-11-17': 'missed', '2026-11-18': 'completed' }
  };
  const logs = [
    { gameId: 'g1', gameName: 'Game', date: '2026-11-17', outcome: 'Missed', score: '', loggedAt: Date.parse('2026-11-18T02:00:00Z') },
    { gameId: 'g1', gameName: 'Game', date: '2026-11-18', outcome: 'Completed', score: '2/6', loggedAt: Date.parse('2026-11-18T06:00:00Z') }
  ];
  const res = H.applyScheduleChangeState(game, { resetTime: '00:00', resetZone: 'utc' }, { resetTime: '01:00', resetZone: 'utc' }, logs, now);
  assert.equal(res.changed, true);
  assert.equal(res.logs.length, 1);
  assert.equal(res.logs[0].date, '2026-11-18');
  assert.equal(res.logs[0].outcome, 'Completed');
  assert.equal(res.logs[0].score, '2/6');
  assert.equal(res.logs[0].streak, 1);
  assert.equal(game.todayScore, '2/6');

  // Invalid schedule inputs do not mutate game.resetTime / game.resetZone.
  const invalidRes = H.applyScheduleChangeState(game, { resetTime: '01:00', resetZone: 'utc' }, { resetTime: 'bad', resetZone: 'local' }, res.logs, now);
  assert.equal(invalidRes.changed, false);
  assert.equal(game.resetTime, '01:00');
  assert.equal(game.resetZone, 'utc');
});

test('1.9.4: streakAsOfDate computes historical streak without using today live streak', () => {
  const hist = {
    '2026-11-10': 'completed',
    '2026-11-11': 'completed',
    '2026-11-12': 'missed',
    '2026-11-13': 'completed',
    '2026-11-14': 'completed',
    '2026-11-15': 'completed',
    '2026-11-16': 'completed'
  };
  assert.equal(H.streakAsOfDate(hist, '2026-11-11'), 2);
  assert.equal(H.streakAsOfDate(hist, '2026-11-12'), 0);
  assert.equal(H.streakAsOfDate(hist, '2026-11-14'), 2);
  assert.equal(H.streakAsOfDate(hist, '2026-11-16'), 4);
  assert.equal(H.streakAsOfDate(hist, '2026-11-09'), 0);
});

test('1.10.1: paginateHistoryByDay keeps all entries of a day on the same page even past pageSize', () => {
  assert.deepEqual(H.paginateHistoryByDay([], 8), [{ rows: [], start: 0 }]);

  // Day 1 has 5 rows, Day 2 has 5 rows -> 5 + 5 > 8, so Day 2 moves intact to Page 2 (start: 5).
  const splitClean = [
    ...Array.from({ length: 5 }, (_, i) => ({ date: '2026-11-25', gameId: `a${i}` })),
    ...Array.from({ length: 5 }, (_, i) => ({ date: '2026-11-24', gameId: `b${i}` })),
    ...Array.from({ length: 2 }, (_, i) => ({ date: '2026-11-23', gameId: `c${i}` }))
  ];
  const pages1 = H.paginateHistoryByDay(splitClean, 8);
  assert.equal(pages1.length, 2);
  assert.equal(pages1[0].start, 0);
  assert.equal(pages1[0].rows.length, 5);
  assert.ok(pages1[0].rows.every((r) => r.date === '2026-11-25'));
  assert.equal(pages1[1].start, 5);
  assert.equal(pages1[1].rows.length, 7); // 5 from Nov 24 + 2 from Nov 23 <= 8

  // Single day with 11 entries (> pageSize 8) stays on 1 page, and next day starts on next page.
  const bigDay = [
    ...Array.from({ length: 11 }, (_, i) => ({ date: '2026-11-25', gameId: `big${i}` })),
    ...Array.from({ length: 3 }, (_, i) => ({ date: '2026-11-24', gameId: `next${i}` }))
  ];
  const pages2 = H.paginateHistoryByDay(bigDay, 8);
  assert.equal(pages2.length, 2);
  assert.equal(pages2[0].start, 0);
  assert.equal(pages2[0].rows.length, 11);
  assert.equal(pages2[1].start, 11);
  assert.equal(pages2[1].rows.length, 3);
});

test('normalizeGameObject preserves and deduplicates notDuplicateOf while omitting empty lists', () => {
  const now = new Date('2026-11-27T12:00:00Z');
  const g1 = H.normalizeGameObject({
    id: 'g-1',
    name: 'Game One',
    url: 'https://example.com/one',
    notDuplicateOf: [' g-2 ', 'g-3', 'g-2', '', null, 42]
  }, 0, now);
  assert.deepEqual(g1.notDuplicateOf, ['g-2', 'g-3']);

  const g2 = H.normalizeGameObject({
    id: 'g-2',
    name: 'Game Two',
    url: 'https://example.com/two',
    notDuplicateOf: []
  }, 1, now);
  assert.equal('notDuplicateOf' in g2, false);
});
