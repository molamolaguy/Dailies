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
    // Offsets from either side of any transition near this wall time give both candidates.
    const c1 = guess - zoneOffsetMs(guess - 12 * 3600000, tz);
    const c2 = guess - zoneOffsetMs(guess + 12 * 3600000, tz);
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
    // Repeated fall-back hour: JS picks the earlier instant, java.util.Calendar the later one.
    const later = new Date(t.getTime() + 3600000);
    if (later.getHours() === t.getHours() && later.getMinutes() === t.getMinutes() && later.getDate() === t.getDate()) {
      return later;
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
    const probe = new Date(getNextResetDate(to.resetTime, to.resetZone, refDate).getTime() + 60000);
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
    if (STRICT_FRACTION_RE.test(t) && (STRICT_CONTEXT_RE.test(t) || mentionsGame)) return true;
    return Boolean(mentionsGame && STRICT_COUNTED_RE.test(t));
  }

  return {
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
