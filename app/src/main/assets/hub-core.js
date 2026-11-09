/*
 * Dailies — pure hub logic (no DOM access).
 *
 * Loaded by index.html before the main UI script, and require()-able from Node so the
 * date / cycle / streak rules can be unit tested (see app/src/test/js/hub-core.test.js).
 *
 * Reset schedules are stored as { resetTime: 'HH:MM', resetZone: 'local' | 'utc' }.
 * A "cycle key" (YYYY-MM-DD) names the daily puzzle that is live at a given moment: it is the
 * date, in the reset's own zone, on which the current cycle started. UTC schedules are evaluated in
 * UTC so they stay pinned to the real reset instant across DST changes and time-zone travel.
 * This mirrors GameStore.cycleKey() on the Kotlin side.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) {
    module.exports = api;
  } else {
    root.HubCore = api;
  }
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const DATE_KEY_RE = /^\d{4}-\d{2}-\d{2}$/;
  const HHMM_RE = /^\d{2}:\d{2}$/;

  // ---- Time zones -------------------------------------------------------------------------
  // A schedule's zone is one of:
  //   'local'  – the device's zone, wherever the phone is (e.g. Wordle: midnight wherever you are)
  //   'utc'    – Coordinated Universal Time
  //   an IANA zone id such as 'America/New_York' – the zone the reset time was published in.
  // Whatever the zone, every time shown to the user is converted to their local time.

  const UTC_ALIASES = new Set(['utc', 'gmt', 'z', 'zulu', 'etc/utc', 'etc/gmt', 'etc/uct', 'uct', 'etc/zulu', 'universal', 'etc/universal']);
  const zoneFormatterCache = new Map();

  function zoneFormatter(tz) {
    let f = zoneFormatterCache.get(tz);
    if (!f) {
      f = new Intl.DateTimeFormat('en-US', {
        timeZone: tz, hourCycle: 'h23',
        year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit'
      });
      zoneFormatterCache.set(tz, f);
    }
    return f;
  }

  /** True if `tz` is an IANA zone id this runtime knows. */
  function isValidTimeZone(tz) {
    if (!tz || typeof tz !== 'string' || !/^[A-Za-z_]+(?:\/[A-Za-z0-9_+\-]+)+$/.test(tz)) return false;
    try {
      zoneFormatter(tz);
      return true;
    } catch (e) {
      return false;
    }
  }

  /** Canonical zone value: 'local', 'utc', or a valid IANA id (anything else falls back to 'local'). */
  function normalizeZone(zone) {
    const raw = String(zone || '').trim();
    if (!raw) return 'local';
    if (raw.toLowerCase() === 'local') return 'local';
    if (UTC_ALIASES.has(raw.toLowerCase())) return 'utc';
    if (!isValidTimeZone(raw)) return 'local';
    // Intl accepts ids case-insensitively but java.util.TimeZone does not: store the canonical
    // spelling ("america/new_york" -> "America/New_York") so the notification side agrees.
    try {
      const resolved = zoneFormatter(raw).resolvedOptions().timeZone;
      if (resolved && resolved.toLowerCase() === raw.toLowerCase()) return resolved;
    } catch (e) {}
    return raw;
  }

  function isNamedZone(zone) {
    const z = normalizeZone(zone);
    return z !== 'local' && z !== 'utc';
  }

  /** Wall-clock parts of `date` in IANA zone `tz`. */
  function zoneParts(date, tz) {
    const out = {};
    zoneFormatter(tz).formatToParts(date).forEach((p) => {
      if (p.type !== 'literal') out[p.type] = parseInt(p.value, 10);
    });
    return { y: out.year, m: out.month, d: out.day, h: out.hour % 24, mi: out.minute, s: out.second };
  }

  function zoneOffsetMs(instantMs, tz) {
    const p = zoneParts(new Date(instantMs), tz);
    return Date.UTC(p.y, p.m - 1, p.d, p.h, p.mi, p.s) - Math.floor(instantMs / 1000) * 1000;
  }

  /**
   * Instant at which wall time y-m-d h:mi occurs in `tz`, resolved exactly like java.util.Calendar
   * on the Kotlin side: a time skipped by a DST jump moves forward (02:30 on spring-forward day ->
   * 03:30), and a repeated fall-back time (01:30 twice) means the later, standard-time instant.
   */
  function zonedWallTimeToDate(y, m, d, h, mi, tz) {
    const guess = Date.UTC(y, m - 1, d, h, mi, 0, 0);
    // Offsets from either side of any transition near this wall time give both candidates. The probes
    // are 26 h out because UTC offsets reach +14 h (e.g. Pacific/Chatham +13:45 in summer): a 12 h
    // probe can land past the transition and miss one candidate.
    const c1 = guess - zoneOffsetMs(guess - 26 * 3600000, tz);
    const c2 = guess - zoneOffsetMs(guess + 26 * 3600000, tz);
    const matches = (t) => {
      const p = zoneParts(new Date(t), tz);
      return p.y === y && p.m === m && p.d === d && p.h === h && p.mi === mi;
    };
    const ok = [c1, c2].filter(matches);
    if (ok.length) return new Date(Math.max.apply(null, ok));
    return new Date(Math.max(c1, c2));
  }

  function zoneDateKey(date, tz) {
    const p = zoneParts(date, tz);
    return `${p.y}-${pad2(p.m)}-${pad2(p.d)}`;
  }

  function resetInstantOnKey(key, resetTime, tz) {
    const [y, m, d] = key.split('-').map(Number);
    const { h, m: mi } = parseHHMM(resetTime);
    return zonedWallTimeToDate(y, m, d, h, mi, tz);
  }

  /** Short human label for a zone, e.g. 'America/New_York' -> 'New York time'. */
  function zoneLabel(zone) {
    const z = normalizeZone(zone);
    if (z === 'local') return 'your local time';
    if (z === 'utc') return 'UTC';
    const city = z.split('/').pop().replace(/_/g, ' ');
    return `${city} time`;
  }

  function parseHHMM(resetTime) {
    const parts = String(resetTime || '00:00').split(':');
    const h = Math.min(23, Math.max(0, parseInt(parts[0] || '0', 10) || 0));
    const m = Math.min(59, Math.max(0, parseInt(parts[1] || '0', 10) || 0));
    return { h, m };
  }

  function sanitizeResetTime(resetTime) {
    if (!HHMM_RE.test(String(resetTime || ''))) return '00:00';
    const { h, m } = parseHHMM(resetTime);
    return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
  }

  function pad2(n) {
    return String(n).padStart(2, '0');
  }

  /**
   * Reset instant on the calendar day `dayOffset` days from `ref`, for 'utc' or 'local' schedules.
   * The day is chosen first and the wall time set on it afterwards, so a DST-adjusted time on one
   * day (02:30 -> 03:30 on spring-forward day) never leaks into the following days.
   */
  function resetOnDay(resetTime, zone, ref, dayOffset) {
    const { h, m } = parseHHMM(resetTime);
    if (normalizeZone(zone) === 'utc') {
      return new Date(Date.UTC(ref.getUTCFullYear(), ref.getUTCMonth(), ref.getUTCDate() + dayOffset, h, m, 0, 0));
    }
    const t = new Date(ref.getFullYear(), ref.getMonth(), ref.getDate() + dayOffset, h, m, 0, 0);
    // Repeated fall-back time: JS picks the earlier instant, java.util.Calendar the later one. The
    // repeat lasts as long as the offset drop (1 h usually, 30 min on Lord Howe Island), so measure it.
    const drop = (new Date(t.getTime() + 3 * 3600000).getTimezoneOffset() - t.getTimezoneOffset()) * 60000;
    if (drop > 0) {
      const later = new Date(t.getTime() + drop);
      if (later.getHours() === t.getHours() && later.getMinutes() === t.getMinutes() && later.getDate() === t.getDate()) {
        return later;
      }
    }
    return t;
  }

  function dayKeyInZone(ref, zone, dayOffset) {
    if (normalizeZone(zone) === 'utc') {
      const d = new Date(Date.UTC(ref.getUTCFullYear(), ref.getUTCMonth(), ref.getUTCDate() + dayOffset));
      return `${d.getUTCFullYear()}-${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())}`;
    }
    const d = new Date(ref.getFullYear(), ref.getMonth(), ref.getDate() + dayOffset, 12, 0, 0, 0);
    return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
  }

  /** Cycle key of the puzzle that is live at `refDate` (defaults to now). */
  function getCycleDateKey(resetTime, resetZone, refDate) {
    const now = refDate || new Date();
    const zone = normalizeZone(resetZone);
    if (isNamedZone(zone)) {
      const todayKey = zoneDateKey(now, zone);
      const start = resetInstantOnKey(todayKey, resetTime, zone);
      return now.getTime() < start.getTime() ? addDaysToCycleKey(todayKey, -1) : todayKey;
    }
    const start = resetOnDay(resetTime, zone, now, 0);
    return dayKeyInZone(now, zone, now.getTime() < start.getTime() ? -1 : 0);
  }

  /** The next reset instant strictly after `refDate`. */
  function getNextResetDate(resetTime, resetZone, refDate) {
    const now = refDate || new Date();
    const zone = normalizeZone(resetZone);
    if (isNamedZone(zone)) {
      const todayKey = zoneDateKey(now, zone);
      const t = resetInstantOnKey(todayKey, resetTime, zone);
      return t.getTime() > now.getTime() ? t : resetInstantOnKey(addDaysToCycleKey(todayKey, 1), resetTime, zone);
    }
    const today = resetOnDay(resetTime, zone, now, 0);
    return today.getTime() > now.getTime() ? today : resetOnDay(resetTime, zone, now, 1);
  }

  function getMsUntilNextReset(resetTime, resetZone, refDate) {
    const now = refDate || new Date();
    return Math.max(0, getNextResetDate(resetTime, resetZone, now).getTime() - now.getTime());
  }

  /** Local wall-clock hour/minute of the next reset (correct for UTC schedules in any DST state). */
  function getLocalResetHourMinute(resetTime, resetZone, refDate) {
    const next = getNextResetDate(resetTime, resetZone, refDate);
    return { hour: next.getHours(), minute: next.getMinutes() };
  }

  function cycleKeyToDay(key) {
    const p = String(key || '').split('-').map((n) => parseInt(n, 10));
    if (p.length !== 3 || p.some((n) => isNaN(n))) return null;
    return Math.round(Date.UTC(p[0], p[1] - 1, p[2]) / 86400000);
  }

  function addDaysToCycleKey(key, deltaDays) {
    const p = String(key || '').split('-').map((n) => parseInt(n, 10));
    if (p.length !== 3 || p.some((n) => isNaN(n))) return key;
    const dt = new Date(Date.UTC(p[0], p[1] - 1, p[2] + deltaDays));
    return dt.toISOString().slice(0, 10);
  }

  /**
   * How many days the cycle keys of a game move when its schedule changes from `from` to `to`
   * while describing the same real reset instant (e.g. "19:00 local" -> "00:00 UTC" in New York
   * is +1: the puzzle that starts at 19:00 on Nov 5 local is the Nov 6 UTC puzzle).
   * Measured just after the new schedule's next reset so both keys name the same puzzle.
   */
  function scheduleKeyShift(from, to, refDate) {
    // Probe the middle of the new schedule's current cycle: the old cycle that contains it is the one
    // that overlaps the new cycle the most. When both schedules reset at the same instant this is
    // the exact correspondence; when the user corrects a wrong reset time it keeps each logged play
    // on the puzzle that was live for most of that old day (instead of always moving forward).
    const probe = new Date(getNextResetDate(to.resetTime, to.resetZone, refDate).getTime() - 12 * 3600000);
    const a = cycleKeyToDay(getCycleDateKey(from.resetTime, from.resetZone, probe));
    const b = cycleKeyToDay(getCycleDateKey(to.resetTime, to.resetZone, probe));
    if (a === null || b === null) return 0;
    return b - a;
  }

  /** Returns a copy of `dayHistory` with every date key moved by `delta` days. */
  function shiftDayHistory(dayHistory, delta) {
    if (!delta || !dayHistory || typeof dayHistory !== 'object') return Object.assign({}, dayHistory || {});
    const next = {};
    Object.keys(dayHistory).forEach((k) => {
      if (DATE_KEY_RE.test(k)) next[addDaysToCycleKey(k, delta)] = dayHistory[k];
    });
    return next;
  }

  function cleanDayHistory(raw) {
    const out = {};
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return out;
    Object.keys(raw).forEach((k) => {
      if (DATE_KEY_RE.test(k) && (raw[k] === 'completed' || raw[k] === 'missed')) out[k] = raw[k];
    });
    return out;
  }

  /** Counts derived purely from a day history. */
  function historyCounts(dayHistory) {
    let longestRun = 0, run = 0, prevDayNum = null, played = 0, won = 0;
    Object.keys(dayHistory || {}).filter((k) => DATE_KEY_RE.test(k)).sort().forEach((k) => {
      const val = dayHistory[k];
      if (val === 'completed' || val === 'missed') played += 1;
      if (val === 'completed') {
        won += 1;
        const dNum = cycleKeyToDay(k);
        run = (prevDayNum !== null && dNum === prevDayNum + 1) ? run + 1 : 1;
        prevDayNum = dNum;
        if (run > longestRun) longestRun = run;
      } else {
        run = 0;
        prevDayNum = null;
      }
    });
    return { played, won, longestRun };
  }

  /**
   * One-time baseline for totals recorded before day-by-day history existed (old versions kept bare
   * counters). Stored on the game so later recomputes can go *down* again (Undo, relabelling).
   */
  function ensureStatsBaseline(game) {
    if (!game) return game;
    const b = game.statsBaseline;
    if (b && typeof b === 'object') {
      game.statsBaseline = {
        played: Math.max(0, parseInt(b.played, 10) || 0),
        won: Math.max(0, parseInt(b.won, 10) || 0),
        best: Math.max(0, parseInt(b.best, 10) || 0)
      };
      return game;
    }
    const c = historyCounts(game.dayHistory);
    const storedBest = Math.max(0, parseInt(game.bestStreak, 10) || 0);
    game.statsBaseline = {
      played: Math.max(0, (parseInt(game.totalPlayed, 10) || 0) - c.played),
      won: Math.max(0, (parseInt(game.totalWon, 10) || 0) - c.won),
      best: storedBest > c.longestRun ? storedBest : 0
    };
    return game;
  }

  /** Returns `base` if unused, otherwise base-2, base-3 … (never loops forever). */
  function uniqueId(base, seen) {
    const b = String(base || 'game');
    if (!seen.has(b)) return b;
    let n = 2;
    while (seen.has(`${b}-${n}`)) n += 1;
    return `${b}-${n}`;
  }

  /**
   * Recomputes status, current/best streak and totals from `game.dayHistory` (the single source of
   * truth) plus the game's legacy baseline. History is never pruned.
   */
  function recomputeGameStatsFromHistory(game, refDate) {
    if (!game) return game;
    if (!game.dayHistory || typeof game.dayHistory !== 'object') game.dayHistory = {};

    const todayKey = getCycleDateKey(game.resetTime, game.resetZone, refDate);
    const todayOutcome = game.dayHistory[todayKey];
    if (todayOutcome === 'completed' || todayOutcome === 'missed') {
      game.status = todayOutcome;
    } else if (game.status !== 'completed' && game.status !== 'missed') {
      game.status = 'ready';
    }

    let currentStreak = 0;
    if (todayOutcome !== 'missed') {
      let cursor = todayOutcome === 'completed' ? todayKey : addDaysToCycleKey(todayKey, -1);
      while (game.dayHistory[cursor] === 'completed') {
        currentStreak += 1;
        cursor = addDaysToCycleKey(cursor, -1);
      }
    }

    ensureStatsBaseline(game);
    const c = historyCounts(game.dayHistory);
    const base = game.statsBaseline;
    game.streak = currentStreak;
    game.bestStreak = Math.max(c.longestRun, currentStreak, base.best);
    game.totalPlayed = c.played + base.played;
    game.totalWon = c.won + base.won;
    return game;
  }

  /** Index of history log rows by "<gameId>|<date>" for O(1) calendar lookups. */
  function indexHistoryLogs(logs) {
    const map = new Map();
    (logs || []).forEach((l) => {
      if (!l || typeof l.date !== 'string') return;
      const key = `${l.gameId || ('name:' + l.gameName)}|${l.date}`;
      if (!map.has(key)) map.set(key, l);
    });
    return map;
  }

  // Emoji squares / circles used by Wordle-style share grids.
  const SHARE_GLYPH_RE = /[\u2B1B\u2B1C\u{1F7E5}-\u{1F7EB}\u{1F7E0}-\u{1F7E4}\u{1F534}\u{1F535}\u26AB\u26AA]/u;
  const SCORE_FRACTION_RE = /\b(?:[0-9]{1,2}|x)\s*\/\s*[0-9]{1,2}\b/i;

  /**
   * Heuristic: does clipboard text look like a daily-game share result (rather than a random copy)?
   * True for emoji result grids, "3/6"-style scores, or text naming the game or containing its URL host.
   */
  function looksLikeShareResult(text, game) {
    const t = String(text || '').trim();
    if (!t || t.length > 4000) return false;
    if (SHARE_GLYPH_RE.test(t)) return true;
    const lower = t.toLowerCase();
    const name = String((game && game.name) || '').trim().toLowerCase();
    if (name.length >= 3 && lower.includes(name)) return true;
    try {
      const host = new URL(String((game && game.url) || '')).hostname.replace(/^www\./, '').toLowerCase();
      if (host && lower.includes(host)) return true;
    } catch (e) {}
    return SCORE_FRACTION_RE.test(t) && /\n|#|day|puzzle|guess/i.test(t);
  }

  // Strict variant for automatic logging (copy/share captured inside a game). Mirrors
  // ShareResultDetector.kt; both run app/src/test/resources/share_cases.json.
  const STRICT_GLYPH_RE = /[\u2B1B\u2B1C\u26AB\u26AA\u2705\u274C\u{1F7E0}-\u{1F7EB}\u{1F534}\u{1F535}]/u;
  // Explicit ASCII classes instead of \s / \b / \d so JS, the JVM tests and Android's ICU regex
  // engine (where \s and \b are Unicode-aware) all behave identically. NBSP counts as a space.
  const SINGLE_URL_RE = /^https?:\/\/[^ \t\n\r\f\v\u00A0]+$/i;
  const STRICT_FRACTION_RE = /(?<![0-9/])(?:[0-9]{1,2}|x)[ \t\u00A0]*\/[ \t\u00A0]*[0-9]{1,2}(?![0-9/])/i;
  const STRICT_CONTEXT_RE = /\n|#[ \u00A0]?[0-9]|(?<![A-Za-z0-9_])(?:day|puzzle|guess|guesses|tries|try|attempts?|mistakes?|score|points?|streak)(?![A-Za-z0-9_])/i;
  const STRICT_COUNTED_RE = /#[ \u00A0]?[0-9]+|(?<![A-Za-z0-9_])[0-9]+[ \t\u00A0]*(?:guess|guesses|tries|attempts?|mistakes?|points?|pts|moves?|swaps?|seconds?|secs?|minutes?|mins?)(?![A-Za-z0-9_])/i;
  const STRICT_HELP_RE = /(?<![A-Za-z0-9_])(?:how to play|you have[ \t ]+[0-9]+|you get[ \t ]+[0-9]+|in[ \t ]+[0-9]+[ \t ]+(?:tries|guesses|attempts|chances)|guess the|find the|can you|each guess|tap to|rules)(?![A-Za-z0-9_])/i;
  const STRICT_PUZZLE_NO_RE = /#[  ]?[0-9]/;
  function isStrictShareResult(text, game) {
    const t = String(text || '').trim();
    if (t.length < 3 || t.length > 4000) return false;
    if (SINGLE_URL_RE.test(t)) return false;
    if (STRICT_GLYPH_RE.test(t)) return true;
    const lower = t.toLowerCase();
    const name = String((game && game.name) || '').trim().toLowerCase();
    let host = '';
    try { host = new URL(String((game && game.url) || '')).hostname.replace(/^www\./, '').toLowerCase(); } catch (e) {}
    const mentionsGame = (name.length >= 3 && lower.includes(name)) || (host && lower.includes(host));
    // Rules / help text ("Guess the Wordle in 6 tries") names the game and a count but isn't a result.
    if (STRICT_HELP_RE.test(t) && !STRICT_PUZZLE_NO_RE.test(t)) return false;
    if (STRICT_FRACTION_RE.test(t) && (STRICT_CONTEXT_RE.test(t) || mentionsGame)) return true;
    return Boolean(mentionsGame && STRICT_COUNTED_RE.test(t));
  }

  // A share result that says the puzzle was NOT solved ("X/6", "Next time", Connections with four
  // mistakes). Auto-logging records these as Missed (with the score) instead of Solved.
  const FAILED_FRACTION_RE = /(?<![0-9A-Za-z_/])x[ \t ]*\/[ \t ]*[0-9]{1,2}(?![0-9/])/i;
  const FAILED_PHRASE_RE = /(?<![A-Za-z0-9_])(?:next time|better luck|not solved|unsolved|did not solve|didn[’']t solve|failed|out of (?:guesses|tries|lives))(?![A-Za-z0-9_])/i;
  const GROUP_SQUARES = new Set(['\u{1F7E8}', '\u{1F7E9}', '\u{1F7E6}', '\u{1F7EA}']); // yellow green blue purple
  function isFailedShareResult(text) {
    const t = String(text || '').trim();
    if (!t) return false;
    if (FAILED_FRACTION_RE.test(t) || FAILED_PHRASE_RE.test(t)) return true;
    // Framed-style rows: every attempt red (🟥 / ❌) and no green anywhere means it was never solved.
    const cellsAll = Array.from(t);
    const reds = cellsAll.filter((c) => c === '\u{1F7E5}' || c === '❌').length;
    if (reds >= 3 && !cellsAll.some((c) => c === '\u{1F7E9}' || c === '✅')) return true;
    // Connections-style grid: rows of four group colours; a mixed row is a mistake, four ends the game.
    let mixedRows = 0;
    t.split('\n').forEach((line) => {
      const cells = Array.from(line.replace(/[ \t ️]/g, ''));
      if (cells.length === 4 && cells.every((c) => GROUP_SQUARES.has(c)) && new Set(cells).size > 1) mixedRows += 1;
    });
    return mixedRows >= 4;
  }

  const SCORE_RE = /(^|[^\d/])(\d{1,2}|[xX])\s*\/\s*(\d{1,2})(?![\d/])/;

  /** "3/6" -> { n: 3, max: 6 }; "X/6" -> { n: null, max: 6, fail: true }; null when there's no N/M score. */
  function parseScore(text) {
    const m = SCORE_RE.exec(String(text || ''));
    if (!m) return null;
    const max = Number(m[3]);
    if (!(max >= 2 && max <= 20)) return null;
    if (/x/i.test(m[2])) return { n: null, max, fail: true };
    const n = Number(m[2]);
    if (n < 1 || n > max) return null;
    return { n, max, fail: false };
  }

  /** "1m 05s" / "42s" / "1h 03m" for a play time in ms. */
  function formatDuration(ms) {
    const t = Math.max(0, Math.round(Number(ms) / 1000) || 0);
    const h = Math.floor(t / 3600);
    const m = Math.floor((t % 3600) / 60);
    const sec = t % 60;
    if (h) return `${h}h ${String(m).padStart(2, '0')}m`;
    if (m) return `${m}m ${String(sec).padStart(2, '0')}s`;
    return `${sec}s`;
  }

  /**
   * Score statistics for one game's logged results ({outcome, score, timeMs} rows): win rate and, when
   * the scores are "N/M" guesses (Wordle-style), the average and the guess distribution for the most
   * common M. Fails ("X/6", or a Missed row with an N/M score) count in an "X" bar. Solved rows with a
   * play time give the fastest and average time.
   */
  function scoreStats(rows) {
    const list = Array.isArray(rows) ? rows.filter((r) => r && typeof r === 'object') : [];
    let won = 0;
    let missed = 0;
    const parsed = [];
    const times = [];
    for (const r of list) {
      const ok = r.outcome === 'Completed';
      if (ok) won += 1; else if (r.outcome === 'Missed') missed += 1; else continue;
      if (ok && Number(r.timeMs) > 0) times.push(Number(r.timeMs));
      const sc = parseScore(r.score);
      if (!sc) continue;
      parsed.push({ max: sc.max, n: sc.fail || !ok ? null : sc.n });
    }
    const played = won + missed;
    const out = {
      won, missed, played, winRate: played ? Math.round((won / played) * 100) : null, avg: null, max: null, dist: [],
      fastestMs: times.length ? Math.min(...times) : null,
      avgMs: times.length ? Math.round(times.reduce((a, b) => a + b, 0) / times.length) : null,
      timed: times.length
    };
    if (parsed.length < 2) return out;
    const counts = new Map();
    parsed.forEach((p) => counts.set(p.max, (counts.get(p.max) || 0) + 1));
    let max = 0;
    let best = -1;
    counts.forEach((c, k) => { if (c > best || (c === best && k < max)) { best = c; max = k; } });
    const same = parsed.filter((p) => p.max === max);
    const solved = same.filter((p) => p.n !== null);
    out.max = max;
    out.avg = solved.length ? Math.round((solved.reduce((a, p) => a + p.n, 0) / solved.length) * 10) / 10 : null;
    for (let i = 1; i <= max; i++) out.dist.push({ label: String(i), count: solved.filter((p) => p.n === i).length });
    const fails = same.length - solved.length;
    if (fails) out.dist.push({ label: 'X', count: fails });
    return out;
  }

  /**
   * Personal records a new solved row sets against the game's earlier rows: best score (fewest
   * guesses for the same M), fastest time, and longest streak. Needs at least one earlier comparable
   * result, so a first solve isn't a "record".
   */
  function newRecords(previousRows, row, prevBestStreak) {
    const out = [];
    if (!row || row.outcome !== 'Completed') return out;
    const prev = (Array.isArray(previousRows) ? previousRows : []).filter((r) => r && r.outcome === 'Completed');
    const sc = parseScore(row.score);
    if (sc && !sc.fail) {
      const earlier = prev.map((r) => parseScore(r.score)).filter((p) => p && !p.fail && p.max === sc.max);
      if (earlier.length && sc.n < Math.min(...earlier.map((p) => p.n))) out.push({ kind: 'score', value: `${sc.n}/${sc.max}` });
    }
    const t = Number(row.timeMs);
    if (t > 0) {
      const earlierT = prev.map((r) => Number(r.timeMs)).filter((x) => x > 0);
      if (earlierT.length && t < Math.min(...earlierT)) out.push({ kind: 'time', value: formatDuration(t) });
    }
    const streak = Number(row.streak) || 0;
    if (streak >= 3 && streak > (Number(prevBestStreak) || 0)) out.push({ kind: 'streak', value: streak });
    return out;
  }

  /** A game's personal bests from its rows: { bestScore: "2/6", fastestMs, bestStreak }. */
  function personalBests(rows, bestStreak) {
    const solved = (Array.isArray(rows) ? rows : []).filter((r) => r && r.outcome === 'Completed');
    let best = null;
    solved.forEach((r) => {
      const p = parseScore(r.score);
      if (p && !p.fail && (!best || p.n / p.max < best.n / best.max || (p.n / p.max === best.n / best.max && p.n < best.n))) best = p;
    });
    const times = solved.map((r) => Number(r.timeMs)).filter((x) => x > 0);
    return { bestScore: best ? `${best.n}/${best.max}` : null, fastestMs: times.length ? Math.min(...times) : null, bestStreak: Number(bestStreak) || 0 };
  }

  const ACHIEVEMENTS = [
    { id: 'first', icon: '⭐', title: 'First solve', desc: 'Solve your first puzzle', metric: 'solves', target: 1 },
    { id: 'solves10', icon: '🧩', title: 'Getting started', desc: 'Solve 10 puzzles', metric: 'solves', target: 10 },
    { id: 'solves100', icon: '💯', title: 'Puzzler', desc: 'Solve 100 puzzles', metric: 'solves', target: 100 },
    { id: 'solves500', icon: '🏅', title: 'Puzzle master', desc: 'Solve 500 puzzles', metric: 'solves', target: 500 },
    { id: 'solves1000', icon: '👑', title: 'Legend', desc: 'Solve 1,000 puzzles', metric: 'solves', target: 1000 },
    { id: 'streak7', icon: '🔥', title: 'On a roll', desc: 'Reach a 7-day streak', metric: 'streak', target: 7 },
    { id: 'streak30', icon: '📅', title: 'Monthly habit', desc: 'Reach a 30-day streak', metric: 'streak', target: 30 },
    { id: 'streak100', icon: '🚀', title: 'Centurion', desc: 'Reach a 100-day streak', metric: 'streak', target: 100 },
    { id: 'streak365', icon: '🏆', title: 'Year of puzzles', desc: 'Reach a 365-day streak', metric: 'streak', target: 365 },
    { id: 'perfectWeek', icon: '✨', title: 'Perfect week', desc: 'Solve every game you track, 7 days in a row', metric: 'perfectDays', target: 7 },
    { id: 'variety', icon: '🎨', title: 'Variety pack', desc: 'Solve 5 different games in one day', metric: 'variety', target: 5 },
    { id: 'lucky', icon: '🍀', title: 'Two-guess wonder', desc: 'Solve a guessing game in 2 or fewer', metric: 'lucky', target: 1 },
    { id: 'speed', icon: '⚡', title: 'Speed run', desc: 'Solve a puzzle in under a minute', metric: 'speed', target: 1 },
    { id: 'collector', icon: '📚', title: 'Collector', desc: 'Track 10 games', metric: 'games', target: 10 }
  ];

  /** Longest run of consecutive dates on which every eligible game was solved. */
  function perfectRun(games) {
    const list = (Array.isArray(games) ? games : []).filter((g) => g && g.dayHistory && typeof g.dayHistory === 'object');
    if (!list.length) return 0;
    const first = new Map();
    const done = new Map();
    list.forEach((g) => {
      const dates = Object.keys(g.dayHistory).filter((d) => DATE_KEY_RE.test(d)).sort();
      if (!dates.length) return;
      first.set(g, cycleKeyToDay(dates[0]));
      done.set(g, new Set(dates.filter((d) => g.dayHistory[d] === 'completed').map(cycleKeyToDay)));
    });
    const allDays = new Set();
    done.forEach((set) => set.forEach((d) => allDays.add(d)));
    const days = [...allDays].sort((a, b) => a - b);
    let best = 0;
    let run = 0;
    let prev = null;
    for (const d of days) {
      const eligible = [...first.keys()].filter((g) => first.get(g) <= d);
      const ok = eligible.length > 0 && eligible.every((g) => done.get(g).has(d));
      run = ok ? (prev !== null && d === prev + 1 && run > 0 ? run + 1 : 1) : 0;
      prev = d;
      if (run > best) best = run;
    }
    return best;
  }

  /** Every achievement with { earned, progress, target } from the games list and the result log. */
  function computeAchievements(games, logs) {
    const gs = Array.isArray(games) ? games.filter((g) => g && typeof g === 'object') : [];
    const rows = Array.isArray(logs) ? logs.filter((l) => l && typeof l === 'object') : [];
    const solvedRows = rows.filter((r) => r.outcome === 'Completed');
    const byDay = new Map();
    solvedRows.forEach((r) => {
      const key = r.date;
      if (!byDay.has(key)) byDay.set(key, new Set());
      byDay.get(key).add(r.gameId || r.gameName);
    });
    const metrics = {
      solves: solvedRows.length,
      streak: Math.max(0, ...gs.map((g) => Math.max(Number(g.bestStreak) || 0, Number(g.streak) || 0)), ...rows.map((r) => Number(r.streak) || 0)),
      perfectDays: perfectRun(gs.filter((g) => !g.archived)),
      variety: Math.max(0, ...[...byDay.values()].map((set) => set.size)),
      lucky: solvedRows.some((r) => { const p = parseScore(r.score); return p && !p.fail && p.max >= 5 && p.n <= 2; }) ? 1 : 0,
      speed: solvedRows.some((r) => Number(r.timeMs) > 0 && Number(r.timeMs) < 60000) ? 1 : 0,
      games: gs.length
    };
    return ACHIEVEMENTS.map((a) => {
      const progress = Math.min(a.target, metrics[a.metric] || 0);
      return { id: a.id, icon: a.icon, title: a.title, desc: a.desc, target: a.target, progress, earned: progress >= a.target };
    });
  }

  /**
   * A month's recap from the result log: results, solves, win rate, longest streak reached, best
   * result (fewest guesses relative to the maximum), most-played game and total play time.
   */
  function monthlyRecap(logs, year, month) {
    const prefix = `${year}-${String(month + 1).padStart(2, '0')}-`;
    const rows = (Array.isArray(logs) ? logs : []).filter((r) => r && typeof r.date === 'string' && r.date.startsWith(prefix));
    const solved = rows.filter((r) => r.outcome === 'Completed');
    const counts = new Map();
    rows.forEach((r) => counts.set(r.gameName || '', (counts.get(r.gameName || '') || 0) + 1));
    let topGame = null;
    counts.forEach((c, name) => { if (name && (!topGame || c > topGame.count)) topGame = { name, count: c }; });
    let best = null;
    solved.forEach((r) => {
      const p = parseScore(r.score);
      if (!p || p.fail) return;
      const ratio = p.n / p.max;
      if (!best || ratio < best.ratio || (ratio === best.ratio && p.n < best.n)) best = { ratio, n: p.n, game: r.gameName || '', score: `${p.n}/${p.max}` };
    });
    const timeMs = solved.reduce((a, r) => a + (Number(r.timeMs) > 0 ? Number(r.timeMs) : 0), 0);
    return {
      results: rows.length,
      solved: solved.length,
      winRate: rows.length ? Math.round((solved.length / rows.length) * 100) : null,
      bestStreak: Math.max(0, ...rows.map((r) => Number(r.streak) || 0)),
      bestResult: best ? { game: best.game, score: best.score } : null,
      topGame,
      days: new Set(rows.map((r) => r.date)).size,
      timeMs
    };
  }

  return {
    scoreStats,
    parseScore,
    formatDuration,
    newRecords,
    personalBests,
    computeAchievements,
    perfectRun,
    monthlyRecap,
    ACHIEVEMENTS,
    isFailedShareResult,
    looksLikeShareResult,
    isStrictShareResult,
    isValidTimeZone,
    isNamedZone,
    ensureStatsBaseline,
    historyCounts,
    uniqueId,
    zoneLabel,
    DATE_KEY_RE,
    HHMM_RE,
    normalizeZone,
    parseHHMM,
    sanitizeResetTime,
    getCycleDateKey,
    getNextResetDate,
    getMsUntilNextReset,
    getLocalResetHourMinute,
    cycleKeyToDay,
    addDaysToCycleKey,
    scheduleKeyShift,
    shiftDayHistory,
    cleanDayHistory,
    recomputeGameStatsFromHistory,
    indexHistoryLogs
  };
});
