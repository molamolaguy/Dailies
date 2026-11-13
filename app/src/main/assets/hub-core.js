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

  /**
   * Moves a game's days to a new reset schedule. Results that carry the time they were logged go to
   * the day that time falls on under the new schedule; the rest move by the overall shift. Nothing
   * lands after today's key, and when two days merge a solve wins. Returns { shift, dayHistory,
   * dates } where dates[i] is the new key for rows[i].
   */
  function rescheduleDays(dayHistory, rows, from, to, now) {
    const at = now || new Date();
    const shift = scheduleKeyShift(from, to, at);
    const todayKey = getCycleDateKey(to.resetTime, to.resetZone, at);
    const clamp = (k) => (k > todayKey ? todayKey : k);
    const moved = new Map();
    const dates = (Array.isArray(rows) ? rows : []).map((r) => {
      const old = r && DATE_KEY_RE.test(String(r.date || '')) ? r.date : null;
      if (!old) return r ? r.date : r;
      const key = clamp(Number(r.loggedAt) > 0 && Number.isFinite(Number(r.loggedAt))
        ? getCycleDateKey(to.resetTime, to.resetZone, new Date(Number(r.loggedAt)))
        : addDaysToCycleKey(old, shift));
      if (!moved.has(old)) moved.set(old, key);
      return key;
    });
    const next = {};
    const put = (k, v) => { if (next[k] !== 'completed') next[k] = v; };
    Object.keys(dayHistory || {}).forEach((k) => {
      if (!DATE_KEY_RE.test(k)) return;
      put(moved.has(k) ? moved.get(k) : clamp(addDaysToCycleKey(k, shift)), dayHistory[k]);
    });
    return { shift, dayHistory: next, dates };
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
  //
  // Everything is matched against an ASCII-lower-cased copy with case-sensitive patterns and explicit
  // ASCII classes (no \s, \b, \d or /i), so JS, the JVM tests and Android's ICU engine agree exactly.
  const STRICT_TRIM_RE = /^[ \t\n\r\f\v\u00A0\uFEFF]+|[ \t\n\r\f\v\u00A0\uFEFF]+$/g;
  const strictTrim = (s) => String(s == null ? '' : s).replace(STRICT_TRIM_RE, '');
  const asciiLower = (s) => String(s == null ? '' : s).replace(/[A-Z]/g, (c) => c.toLowerCase());
  const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\\/-]/g, '\\$&');
  const GRID_CELL = '(?:[\\u2B1B\\u2B1C\\u26AB\\u26AA\\u2705\\u274C\\u{1F7E0}-\\u{1F7EB}\\u{1F534}\\u{1F535}]|[0-9]\\uFE0F?\\u20E3)';
  // Two result squares / circles / keycaps side by side: a share grid ("🟩🟩⬛", "5️⃣7️⃣", "🟥 🟩").
  const STRICT_GRID_RE = new RegExp(`${GRID_CELL}\\uFE0F?[ ]?${GRID_CELL}`, 'u');
  // A single one only counts with the game's name or a puzzle number ("✅ milk" is a shopping list).
  const STRICT_GLYPH_RE = new RegExp(GRID_CELL, 'u');
  const SINGLE_URL_RE = /^https?:\/\/[^ \t\n\r\f\v\u00A0]+$/;
  const STRICT_FRACTION_RE = /(?<![0-9/.])([0-9]{1,2}|x)[ \t\u00A0]*\/[ \t\u00A0]*([0-9]{1,2})(?![0-9/])/g;
  const STRICT_CONTEXT_RE = /#[ \u00A0]?[0-9]|(?<![a-z0-9_])(?:puzzle|guess|guesses|guessed|tries|attempts?|mistakes?|streak)(?![a-z0-9_])/;
  const STRICT_COUNTED_RE = /(?<![a-z0-9_])[0-9]+[ \t\u00A0]*(?:guess|guesses|tries|attempts?|mistakes?|points?|pts|moves?|swaps?|seconds?|secs?|minutes?|mins?|words?|clues?|hints?|strokes?|shots?|steps?)(?![a-z0-9_])|(?<![a-z0-9_])(?:guessed|solved|got it)[ \t\u00A0]+in[ \t\u00A0]+[0-9]|(?<![a-z0-9_])(?:score|combo)[ \t\u00A0]*:[ \t\u00A0]*-?[0-9]|(?<![a-z0-9_])[0-9]+[ \t\u00A0]+(?:under|over)[ \t\u00A0]+par(?![a-z0-9_])|(?<![0-9.,])[0-9][0-9,]{2,}[ \t\u00A0]*\/[ \t\u00A0]*[0-9][0-9,]{2,}(?![0-9])|(?<![0-9.])[0-9]{1,2}\.[0-9][ \t\u00A0]*\/[ \t\u00A0]*[0-9]{1,2}(?![0-9])|\n[ \t\u00A0]*[0-9][0-9,]*[ \t\u00A0]*(?:\n|$)/;
  // A solve time like "0:42" or "1:05:12" (not a clock time such as "9:14 am").
  const STRICT_TIME_RE = /(?<![0-9:\/])[0-9]{1,2}:[0-5][0-9](?::[0-5][0-9])?(?![0-9:])(?![ \t\u00A0]*[ap]\.?m(?![a-z]))/;
  const STRICT_HELP_RE = /(?<![a-z0-9_])(?:how to play|you have[ \t\u00A0]+[0-9]+|you get[ \t\u00A0]+[0-9]+|in[ \t\u00A0]+[0-9]+[ \t\u00A0]+(?:tries|guesses|attempts|chances)|guess the|find the|can you|each guess|tap to|rules|allowed)(?![a-z0-9_])/;
  const STRICT_PUZZLE_NO_RE = /#[ \u00A0]?[0-9]{2}/;
  // Results scored in other units (Krillion's "4,250m" depth, "87%", "1,200 xp").
  const STRICT_MEASURED_RE = /(?<![a-z0-9_])[0-9][0-9,.]*[ \t\u00A0]*(?:m|meters?|metres?|km|ft|feet|%|xp|pts|points?|depth)(?![a-z0-9_])/;
  // A rank instead of a number ("I got to Genius in today's Spelling Bee!", "Rank: Mayor").
  const STRICT_RANK_RE = /(?<![a-z0-9_])(?:i got to|i reached|rank[ \t\u00A0]*:)[ \t\u00A0]*[a-z]|\(perfect\)/;
  const STRICT_SOLVED_RE = /(?<![a-z0-9_])(?:solved|completed|finished|beat|scored)(?![a-z0-9_])/;
  const STRICT_EMOJI_RE = /[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}⭐]/u;
  const NAME_PREFIX_RE = /^(?:the|nyt|daily)[ ]+/;
  const LINE_START_RE = /^(?:$|[0-9]+(?![0-9]*[:.\/])|[ \t\u00A0]*#|[ \t\u00A0]+[0-9][0-9,]{2,}(?![0-9,]*[:.\/]|[0-9,]*[ \t\u00A0]*m(?![a-z]))|[ \t\u00A0]+[-\u2013\u2014][ \t\u00A0]+)/;
  const GENERIC_WORDS = new Set(['the', 'daily', 'game', 'games', 'puzzle', 'puzzles', 'play', 'today', 'www', 'html', 'index']);

  /** The site's own name from its host: "krillion" for krillion.io, "bbc" for www.bbc.co.uk. */
  function siteLabel(host) {
    const parts = asciiLower(host).split('.').filter(Boolean);
    if (parts.length < 2) return '';
    let label = parts[parts.length - 2];
    if (parts.length >= 3 && /^(co|com|org|net|ac|gov|edu)$/.test(label)) label = parts[parts.length - 3];
    return /^[a-z0-9-]+$/.test(label) ? label : '';
  }

  /** Host (no "www.") and path (no trailing "/") of a game's link, both ASCII-lower-cased. */
  function gameHostPath(url) {
    try {
      const u = new URL(String(url || ''));
      if (!/^https?:$/.test(u.protocol) || u.hostname.startsWith('[')) return { host: '', path: '' };
      return { host: asciiLower(u.hostname).replace(/^www\./, ''), path: asciiLower(u.pathname).replace(/\/+$/, '') };
    } catch (e) { return { host: '', path: '' }; }
  }

  /**
   * How clearly a (lower-cased) text names the game: 2 = clearly (a line that starts with the name
   * and a number, "Zip #245"; a name of several words; the game's own site), 1 = in passing (a
   * one-word name such as "Queens" or "Zip" as a word, the site's name, or a link on a site shared by
   * several games), 0 = not at all.
   */
  function gameMention(lower, name, url) {
    const raw = asciiLower(strictTrim(name)).replace(/[ \t\u00A0]+/g, ' ');
    const keys = [];
    const add = (k) => { k = strictTrim(k); if (k.length >= 3 && !keys.includes(k)) keys.push(k); };
    add(raw);
    add(raw.replace(NAME_PREFIX_RE, ''));
    add(raw.split(/[·:|–—]| - /)[0]);
    add(raw.split(/[·:|–—]| - /)[0].replace(NAME_PREFIX_RE, ''));
    // "Krillion Daily Dive" is shared as "Krillion …".
    const first = raw.replace(NAME_PREFIX_RE, '').split(' ')[0];
    if (first.length >= 5 && /^[a-z0-9]+$/.test(first)) add(first);
    let level = 0;
    for (const k of keys) {
      if (!new RegExp(`(?<![a-z0-9])${escapeRe(k)}(?![a-z])`).test(lower)) continue;
      level = Math.max(level, k === raw && raw.includes(' ') && raw.length >= 6 ? 2 : 1);
    }
    if (level === 1) {
      const lineStart = lower.split('\n').some((line) => {
        const l = strictTrim(line).replace(/^#/, '').replace(NAME_PREFIX_RE, '');
        // "Zip #245", "Wordle 1,612 3/6", "Krillion" on its own line, "Foodguessr - 13 Nov" (not "Wordle 12:30").
        return keys.some((k) => l.startsWith(k) && LINE_START_RE.test(l.slice(k.length)));
      });
      if (lineStart) level = 2;
    }
    const { host, path } = gameHostPath(url);
    // A path that names the game means the site has several games (nytimes.com/games/wordle,
    // linkedin.com/games/zip): the site's name, or a link to the site in general, doesn't say which.
    const words = raw.split(/[^a-z0-9]+/).filter((w) => w.length >= 3 && !GENERIC_WORDS.has(w));
    const shared = path.split('/').some((seg) => words.some((w) => seg.includes(w)));
    if (host) {
      if (!shared && lower.includes(host)) level = 2;
      else if (shared && lower.includes(host + path)) level = Math.max(level, 1);
      const label = shared ? '' : siteLabel(host);
      if (label.length >= 4 && new RegExp(`(?<![a-z0-9])${escapeRe(label)}(?![a-z0-9])`).test(lower)) level = Math.max(level, 1);
    }
    return level;
  }

  /** A guess fraction that could be a result: "3/6", "x/6" (not "24/7", "1/2 cup" or a date like "11/13/2026"). */
  function hasStrictFraction(lower) {
    STRICT_FRACTION_RE.lastIndex = 0;
    let m;
    while ((m = STRICT_FRACTION_RE.exec(lower))) {
      const max = Number(m[2]);
      if (max >= 3 && (m[1] === 'x' || Number(m[1]) <= max)) return true;
    }
    return false;
  }

  function isStrictShareResult(text, game) {
    const t = strictTrim(text);
    if (t.length < 3 || t.length > 4000) return false;
    const lower = asciiLower(t);
    if (SINGLE_URL_RE.test(lower)) return false;
    if (STRICT_GRID_RE.test(t)) return true;
    const mention = gameMention(lower, game && game.name, game && game.url);
    const puzzleNo = STRICT_PUZZLE_NO_RE.test(lower);
    if (STRICT_GLYPH_RE.test(t) && (mention > 0 || puzzleNo)) return true;
    // Rules / help text ("Guess the Wordle in 6 tries") names the game and a count but isn't a result.
    if (STRICT_HELP_RE.test(lower) && !puzzleNo) return false;
    if (hasStrictFraction(lower) && (mention > 0 || STRICT_CONTEXT_RE.test(lower))) return true;
    if (mention === 0) return false;
    const scored = STRICT_COUNTED_RE.test(lower) || STRICT_MEASURED_RE.test(lower) || STRICT_TIME_RE.test(lower)
      || STRICT_RANK_RE.test(lower) || (STRICT_EMOJI_RE.test(t) && puzzleNo);
    if (!scored) return false;
    // Named only in passing ("My zip code is 90210, 5 minutes away"): also needs a puzzle number
    // or a "solved".
    return mention === 2 || puzzleNo || STRICT_SOLVED_RE.test(lower);
  }

  /** Whether a text names the game at all (used for clipboard text found after the fact). */
  function mentionsGame(text, game) {
    const lower = asciiLower(strictTrim(text));
    return gameMention(lower, game && game.name, game && game.url) > 0 || STRICT_PUZZLE_NO_RE.test(lower);
  }

  // A share result that says the puzzle was NOT solved ("X/6", "Next time", Connections with four
  // mistakes). Auto-logging records these as Missed (with the score) instead of Solved.
  const FAILED_FRACTION_RE = /(?<![0-9A-Za-z_/])x[ \t\u00A0]*\/[ \t\u00A0]*[0-9]{1,2}(?![0-9/])/i;
  const FAILED_PHRASE_RE = /(?<![A-Za-z0-9_])(?:next time|better luck|not solved|unsolved|did not solve|didn[’']t solve|failed(?![ \t\u00A0]+to(?![A-Za-z]))|out of (?:guesses|tries|lives))(?![A-Za-z0-9_])/i;
  const GROUP_SQUARES = new Set(['\u{1F7E8}', '\u{1F7E9}', '\u{1F7E6}', '\u{1F7EA}']); // yellow green blue purple
  function isFailedShareResult(text) {
    const t = String(text || '').trim();
    if (!t) return false;
    if (FAILED_FRACTION_RE.test(t) || FAILED_PHRASE_RE.test(t)) return true;
    // Framed-style rows: every attempt red (🟥 / ❌) and no green anywhere means it was never solved.
    const cellsAll = Array.from(t);
    const reds = cellsAll.filter((c) => c === '\u{1F7E5}' || c === '❌').length;
    if (reds >= 3 && !cellsAll.some((c) => c === '\u{1F7E9}' || c === '✅')) return true;
    // Quordle / Octordle: each board is a keycap number, or 🟥 when it wasn't solved.
    if (reds >= 1 && /[0-9]\uFE0F?\u20E3/.test(t)) return true;
    // Connections-style grid: rows of four group colours; a mixed row is a mistake, four ends the game.
    let mixedRows = 0;
    t.split('\n').forEach((line) => {
      const cells = Array.from(line.replace(/[ \t\u00A0️]/g, ''));
      if (cells.length === 4 && cells.every((c) => GROUP_SQUARES.has(c)) && new Set(cells).size > 1) mixedRows += 1;
    });
    return mixedRows >= 4;
  }

  const SCORE_RE = /(^|[^\d/.\-])(\d{1,2}|[xX])\s*\/\s*(\d{1,2})(?![\d/:])/;

  /** "3/6" -> { n: 3, max: 6 }; "X/6" -> { n: null, max: 6, fail: true }; null when there's no N/M score. */
  function parseScore(text) {
    const m = SCORE_RE.exec(String(text || ''));
    if (!m) return null;
    const max = Number(m[3]);
    if (!(max >= 3 && max <= 20)) return null;
    if (/x/i.test(m[2])) return { n: null, max, fail: true };
    const n = Number(m[2]);
    if (n < 1 || n > max) return null;
    return { n, max, fail: false };
  }

  // ---- Scores beyond "N/M" guesses: times, points and counts (1.3.0) ----
  // "1:42" / "01:02:03" (not a clock time like "9:14 AM").
  const TIME_COLON_RE = /(?<![0-9:.\/])([0-9]{1,2}):([0-5][0-9])(?::([0-5][0-9]))?(?![0-9:])(?![ \t\u00A0]*[ap]\.?m\b)/i;
  // "1m 42s", "42s", "42.5 seconds", "1h 3m 5s".
  const TIME_UNITS_RE = /(?<![A-Za-z0-9_.])(?:([0-9]{1,2})[ \t\u00A0]*h(?:ours?|rs?)?[ \t\u00A0,]*)?(?:([0-9]{1,3})[ \t\u00A0]*m(?:in(?:ute)?s?)?[ \t\u00A0,]*)?([0-9]{1,4}(?:\.[0-9]+)?)[ \t\u00A0]*s(?:ec(?:ond)?s?)?(?![A-Za-z0-9_])/i;
  // "1h 3m" (no seconds).
  const TIME_HM_RE = /(?<![A-Za-z0-9_.])([0-9]{1,2})[ \t\u00A0]*h(?:ours?|rs?)?[ \t\u00A0,]*([0-9]{1,2})[ \t\u00A0]*m(?:in(?:ute)?s?)?(?![A-Za-z0-9_])/i;
  const TIME_MINUTES_RE = /(?<![A-Za-z0-9_.])([0-9]{1,3})[ \t\u00A0]*min(?:ute)?s?(?![A-Za-z0-9_])/i;
  const POINTS_RE = /(?<![A-Za-z0-9_#.,\-\u2212])([-\u2212]?)([0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]+)?|[0-9]{1,7}(?:\.[0-9]+)?)[ \t\u00A0]*(pts|points?|xp|m|meters?|metres?|km|%)(?![A-Za-z0-9_])/i;
  // "38,412/50,000" (TimeGuessr, FoodGuessr): a points total out of a maximum.
  const POINTS_OF_RE = /(?<![0-9.,\/])([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{3,7})[ \t\u00A0]*\/[ \t\u00A0]*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{3,7})(?![0-9\/])/;
  // A unit-less "m" is minutes ("58m"), unless it reads as a distance (Krillion's "4,250m" depth).
  const DISTANCE_CONTEXT_RE = /depth|deep|dive|metre|meter|distance|krillion/i;
  const GOLF_CONTEXT_RE = /\u26F3|golf|strokes?|(?<![A-Za-z])par(?![A-Za-z])/i;
  const SCORE_LABEL_RE = /(?<![A-Za-z0-9_])(?:score|points|total)[ \t\u00A0]*[:=]?[ \t\u00A0]*([-\u2212]?)([0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]+)?|[0-9]{1,7}(?:\.[0-9]+)?)(?![0-9])/gi;
  const NOT_THIS_SCORE_RE = /(?:high|best|top|record|max|average|avg)[ \t\u00A0]*$/i;
  const COUNT_RE = /(?<![A-Za-z0-9_#.,])([0-9]{1,4})[ \t\u00A0]*(guess(?:es)?|tries|try|attempts?|moves?|mistakes?|swaps?|clicks?|steps?|strokes?|shots?|hints?)(?![A-Za-z0-9_])/i;
  // "FE Guess 512 - guessed in 4" (not "solved in 4 min").
  const GUESSED_IN_RE = /(?<![A-Za-z0-9_])(?:guessed|solved|got it)[ \t\u00A0]+in[ \t\u00A0]+([0-9]{1,3})(?![0-9:.,\/]|[ \t\u00A0]*(?:s|secs?|seconds?|m|mins?|minutes?|h|hrs?|hours?)(?![A-Za-z]))/i;
  const BARE_NUMBER_LINE_RE = /^[ \t\u00A0]*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,7})[ \t\u00A0]*$/;

  const num = (s) => Number(String(s).replace(/,/g, ''));
  const signed = (sign, s) => (sign ? -1 : 1) * num(s);

  /**
   * What a result's score is, so like can be compared with like:
   *   guesses "3/6"          -> { kind: 'guesses', n, max, group: 'g6', rank: n (lower is better) }
   *   time "1:42", "1m 42s"  -> { kind: 'time', ms, group: 'time', rank: ms (lower is better) }
   *   count "45 guesses"     -> { kind: 'count', n, unit, group: 'c:guesses', rank: n (lower is better) }
   *   points "2,450 pts", "Score: 850", "4,250m", or a line that is only a number ("60")
   *                          -> { kind: 'points', value, unit, group: 'p:pts', rank: -value (higher is better) }
   * Each has a `label` for display. Null when the text has no score, or for a failed guess score.
   */
  function parseResultValue(text) {
    const t = String(text || '');
    if (!t.trim()) return null;
    const g = parseScore(t);
    if (g) return g.fail ? null : { kind: 'guesses', n: g.n, max: g.max, group: `g${g.max}`, rank: g.n, label: `${g.n}/${g.max}` };
    let m = TIME_COLON_RE.exec(t);
    if (m) {
      const ms = (m[3] !== undefined ? (Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3])) : (Number(m[1]) * 60 + Number(m[2]))) * 1000;
      if (ms > 0) return { kind: 'time', ms, group: 'time', rank: ms, label: formatClock(ms) };
    }
    m = TIME_UNITS_RE.exec(t);
    if (m) {
      const ms = Math.round(((Number(m[1]) || 0) * 3600 + (Number(m[2]) || 0) * 60 + Number(m[3])) * 1000);
      if (ms > 0) return { kind: 'time', ms, group: 'time', rank: ms, label: formatClock(ms) };
    }
    m = TIME_HM_RE.exec(t);
    if (m) {
      const ms = (Number(m[1]) * 3600 + Number(m[2]) * 60) * 1000;
      if (ms > 0) return { kind: 'time', ms, group: 'time', rank: ms, label: formatClock(ms) };
    }
    m = POINTS_OF_RE.exec(t);
    if (m && num(m[1]) <= num(m[2])) {
      const value = num(m[1]);
      return { kind: 'points', value, unit: 'pts', group: 'p:pts', rank: -value, label: pointsLabel(value, 'pts') };
    }
    m = COUNT_RE.exec(t);
    if (m) {
      const unit = m[2].toLowerCase().replace(/^(guess)(es)?$/, 'guesses').replace(/^(tries|try)$/, 'tries')
        .replace(/^attempts?$/, 'attempts').replace(/^moves?$/, 'moves').replace(/^mistakes?$/, 'mistakes')
        .replace(/^strokes?$/, 'strokes').replace(/^shots?$/, 'shots').replace(/^hints?$/, 'hints');
      const n = Number(m[1]);
      return { kind: 'count', n, unit, group: `c:${unit}`, rank: n, label: `${n} ${unit}` };
    }
    m = GUESSED_IN_RE.exec(t);
    if (m) { const n = Number(m[1]); if (n > 0) return { kind: 'count', n, unit: 'guesses', group: 'c:guesses', rank: n, label: `${n} guesses` }; }
    m = POINTS_RE.exec(t);
    if (m) {
      const unitRaw = m[3].toLowerCase();
      const unit = /^(pts|points?)$/.test(unitRaw) ? 'pts' : (/^(m|meters?|metres?)$/.test(unitRaw) ? 'm' : unitRaw);
      const value = signed(m[1], m[2]);
      // "58m" is a time; "4,250m" or "60m depth" is a distance.
      if (unitRaw === 'm' && !m[1] && /^[0-9]{1,3}$/.test(m[2]) && !DISTANCE_CONTEXT_RE.test(t)) {
        const ms = value * 60000;
        if (ms > 0) return { kind: 'time', ms, group: 'time', rank: ms, label: formatClock(ms) };
      }
      return { kind: 'points', value, unit, group: `p:${unit}`, rank: -value, label: pointsLabel(value, unit) };
    }
    SCORE_LABEL_RE.lastIndex = 0;
    while ((m = SCORE_LABEL_RE.exec(t))) {
      // "High score: 900 Score: 100" is a 100.
      if (NOT_THIS_SCORE_RE.test(t.slice(0, m.index))) continue;
      const value = signed(m[1], m[2]);
      return { kind: 'points', value, unit: 'pts', group: 'p:pts', rank: -value, label: pointsLabel(value, 'pts') };
    }
    m = TIME_MINUTES_RE.exec(t);
    if (m) { const ms = Number(m[1]) * 60000; if (ms > 0) return { kind: 'time', ms, group: 'time', rank: ms, label: formatClock(ms) }; }
    // A line that is only a number, after the first line (Krillion: "Krillion #117 🦐\n60").
    const lines = t.split(/\r?\n/);
    for (let i = 1; i < lines.length; i++) {
      const b = BARE_NUMBER_LINE_RE.exec(lines[i]);
      if (!b) continue;
      const value = num(b[1]);
      // putt.day and other golf games: a bare number is strokes, and fewer is better.
      if (GOLF_CONTEXT_RE.test(t)) return { kind: 'count', n: value, unit: 'strokes', group: 'c:strokes', rank: value, label: `${value} strokes` };
      return { kind: 'points', value, unit: '', group: 'p:', rank: -value, label: pointsLabel(value, '') };
    }
    return null;
  }

  function pointsLabel(value, unit) {
    const v = Number(value).toLocaleString('en-US');
    if (unit === '%') return `${v}%`;
    if (unit === 'm' || unit === 'km') return `${v}${unit}`;
    if (unit === 'xp') return `${v} XP`;
    if (!unit) return v;
    return `${v} pts`;
  }

  /** "1:42" / "1:02:03" for a game's own time score. */
  function formatClock(ms) {
    const t = Number.isFinite(Number(ms)) ? Math.max(0, Math.round(Number(ms) / 1000) || 0) : 0;
    const h = Math.floor(t / 3600);
    const m = Math.floor((t % 3600) / 60);
    const s = t % 60;
    return h ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`;
  }

  /**
   * The solved rows' scores of the kind this game mostly uses (its most common group), so a game
   * that shows "60" one day and "#117" the next only compares its points.
   */
  function dominantScores(rows) {
    const vals = (Array.isArray(rows) ? rows : [])
      .filter((r) => r && r.outcome === 'Completed')
      .map((r) => parseResultValue(r.score))
      .filter(Boolean);
    if (!vals.length) return { group: null, values: [] };
    const counts = new Map();
    vals.forEach((v) => counts.set(v.group, (counts.get(v.group) || 0) + 1));
    // A tie goes to the kind of the most recent result.
    const top = Math.max(...counts.values());
    let group = null;
    for (let i = vals.length - 1; i >= 0; i--) if (counts.get(vals[i].group) === top) { group = vals[i].group; break; }
    return { group, values: vals.filter((v) => v.group === group) };
  }

  /** Average of a list of same-group scores, as a display label ("52 pts", "1:48", "14 guesses"). */
  function averageLabel(values) {
    if (!values.length) return null;
    const v0 = values[0];
    if (v0.kind === 'time') return formatClock(values.reduce((a, v) => a + v.ms, 0) / values.length);
    if (v0.kind === 'points') return pointsLabel(Math.round(values.reduce((a, v) => a + v.value, 0) / values.length * 10) / 10, v0.unit);
    if (v0.kind === 'count') return `${Math.round(values.reduce((a, v) => a + v.n, 0) / values.length * 10) / 10} ${v0.unit}`;
    return null;
  }

  /** "1m 05s" / "42s" / "1h 03m" for a play time in ms. */
  function formatDuration(ms) {
    const t = Number.isFinite(Number(ms)) ? Math.max(0, Math.round(Number(ms) / 1000) || 0) : 0;
    const h = Math.floor(t / 3600);
    const m = Math.floor((t % 3600) / 60);
    const sec = t % 60;
    if (h) return `${h}h ${String(m).padStart(2, '0')}m`;
    if (m) return `${m}m ${String(sec).padStart(2, '0')}s`;
    return `${sec}s`;
  }

  /**
   * Score statistics for one game's logged results ({outcome, score, timeMs} rows): win rate and, when
   * the scores are "N/M" guesses (Wordle-style), the average for the most common M. Solved rows with
   * a play time give the fastest and average time.
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
      if (ok && Number(r.timeMs) > 0 && Number.isFinite(Number(r.timeMs))) times.push(Number(r.timeMs));
      const sc = parseScore(r.score);
      if (!sc) continue;
      parsed.push({ max: sc.max, n: sc.fail || !ok ? null : sc.n });
    }
    const played = won + missed;
    const out = {
      won, missed, played, winRate: played ? Math.round((won / played) * 100) : null, avg: null, max: null, avgText: null,
      fastestMs: times.length ? Math.min(...times) : null,
      avgMs: times.length ? Math.round(times.reduce((a, b) => a + b, 0) / times.length) : null,
      timed: times.length
    };
    // Times, points and counts: the average of the kind this game mostly scores in.
    const dom = dominantScores(list);
    out.scoreKind = dom.values.length ? dom.values[0].kind : null;
    if (out.scoreKind && out.scoreKind !== 'guesses' && dom.values.length >= 2) out.avgText = averageLabel(dom.values);
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
    // Best score in the same terms: fewest guesses (same N/M), fastest game time, most points.
    const sc = parseResultValue(row.score);
    if (sc) {
      const earlier = prev.map((r) => parseResultValue(r.score)).filter((p) => p && p.group === sc.group);
      if (earlier.length && sc.rank < Math.min(...earlier.map((p) => p.rank))) out.push({ kind: 'score', value: sc.label });
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

  /** A game's personal bests from its rows: { bestScore: "2/6" / "1:42" / "2,450 pts", bestKind, fastestMs, bestStreak }. */
  function personalBests(rows, bestStreak) {
    const solved = (Array.isArray(rows) ? rows : []).filter((r) => r && r.outcome === 'Completed');
    // The best of the kind of score this game mostly uses (guesses, game time, points or a count).
    const dom = dominantScores(solved);
    let best = null;
    dom.values.forEach((v) => { if (!best || v.rank < best.rank) best = v; });
    const times = solved.map((r) => Number(r.timeMs)).filter((x) => x > 0 && Number.isFinite(x));
    return { bestScore: best ? best.label : null, bestKind: best ? best.kind : null, fastestMs: times.length ? Math.min(...times) : null, bestStreak: Number(bestStreak) || 0 };
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

  // ---- Reset-time detection (1.4.0) ----

  const MONTH_WORD = '(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?';
  // A number that is part of a date ("Nov 13th 2026", "13 Nov", "#2026-11-13") isn't a puzzle number.
  const DATE_AFTER_RE = new RegExp(`^(?:st|nd|rd|th)(?![a-z])|^[-./][0-9]|^[ \\t\\u00A0,]+${MONTH_WORD}(?![a-z])`, 'i');
  const DATE_BEFORE_RE = new RegExp(`(?:^|[^a-z])${MONTH_WORD}[ \\t\\u00A0,]*$`, 'i');

  /** The puzzle number in a share text: "#117", or the number after the name ("Wordle 1,234 3/6"). */
  function puzzleNumber(text) {
    const t = String(text || '');
    const notDate = (before, after) => !DATE_AFTER_RE.test(after) && !DATE_BEFORE_RE.test(before);
    const hash = /#[ \u00A0]?([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,7})(?![0-9])/g;
    let m;
    while ((m = hash.exec(t))) {
      if (notDate(t.slice(0, m.index), t.slice(m.index + m[0].length))) return Number(m[1].replace(/,/g, ''));
    }
    const first = t.split(/\r?\n/)[0] || '';
    m = /^([^0-9\n]{2,40}?)[ \u00A0]([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,7})(?![0-9\/:.,%]|[ \u00A0]*(?:\/|m\b|pts|points|%))/i.exec(first);
    // Not a labelled score or time ("Score: 2,450", "Time 42"), a list ("Top 10") or a date.
    if (!m || /[:=]\s*$/.test(m[1]) || /(score|points|total|time|level|streak|moves|guesses|top|best)\s*$/i.test(m[1])) return null;
    if (!notDate(m[1], first.slice(m[0].length))) return null;
    return Number(m[2].replace(/,/g, ''));
  }

  /** {n, at} for each logged row of a game that has a puzzle number and the time it was logged. */
  function resetObservations(rows) {
    return (Array.isArray(rows) ? rows : [])
      .filter((r) => r && Number(r.loggedAt) > 0)
      .map((r) => ({ n: puzzleNumber(r.score), at: Number(r.loggedAt) }))
      .filter((o) => o.n !== null && Number.isFinite(o.n));
  }

  /** True when, with this reset time, every puzzle number falls on its own day (number − day is constant). */
  function resetFitsObservations(obs, resetTime, resetZone) {
    let offset = null;
    for (const o of obs) {
      let key;
      try { key = getCycleDateKey(resetTime, resetZone, new Date(o.at)); } catch (e) { return false; }
      const d = o.n - cycleKeyToDay(key);
      if (offset === null) offset = d;
      else if (d !== offset) return false;
    }
    return offset !== null;
  }

  // Daily games almost always reset at midnight somewhere: these are tried first.
  const SUGGEST_ZONES = ['local', 'utc', 'America/New_York', 'America/Chicago', 'America/Denver', 'America/Los_Angeles',
    'Europe/London', 'Europe/Paris', 'Europe/Moscow', 'Asia/Kolkata', 'Asia/Shanghai', 'Asia/Tokyo', 'Australia/Sydney', 'Pacific/Auckland'];

  const DAY_MS = 86400000;

  /** On how many different days the rows disagree with the most common (number − day) under this schedule. */
  function misfitDays(obs, resetTime, resetZone) {
    const rows = obs.map((o) => {
      const day = cycleKeyToDay(getCycleDateKey(resetTime, resetZone, new Date(o.at)));
      return { day, off: o.n - day };
    });
    const counts = new Map();
    rows.forEach((r) => counts.set(r.off, (counts.get(r.off) || 0) + 1));
    let mode = null;
    let best = -1;
    counts.forEach((c, k) => { if (c > best) { best = c; mode = k; } });
    return new Set(rows.filter((r) => r.off !== mode).map((r) => r.day)).size;
  }

  /** When this schedule resets over the coming year, so schedules that always agree ("local" in New York and America/New_York) count as one. */
  function scheduleSignature(resetTime, resetZone, from) {
    const out = [];
    for (let k = 0; k <= 26; k++) out.push(getNextResetDate(resetTime, resetZone, new Date(from + k * 14 * DAY_MS)).getTime());
    return out.join(',');
  }

  /**
   * When a game's logged puzzle numbers keep disagreeing with its reset time, the real reset, but only
   * when the evidence leaves exactly one answer. A wrong suggestion moves results to the wrong days,
   * so this errs towards null:
   *   - at least 4 numbered results of 3 different puzzles over 2+ days, and the current schedule
   *     contradicted on 2 or more different days (one late or replayed result isn't enough);
   *   - the results must pin the reset to within 90 minutes, and every candidate that fits (midnight
   *     in a common zone, or a whole hour in the game's zone, local time or UTC) must be the same
   *     schedule for the coming year (so not "UTC" for a game that follows London's clocks).
   */
  function suggestResetTime(rows, current) {
    const obs = resetObservations(rows).sort((x, y) => x.at - y.at);
    if (obs.length < 4 || new Set(obs.map((o) => o.n)).size < 3) return null;
    if (obs[obs.length - 1].at - obs[0].at < 2 * DAY_MS) return null;
    const curTime = (current && current.resetTime) || '00:00';
    const curZone = normalizeZone(current && current.resetZone);
    let curMisfits;
    try { curMisfits = misfitDays(obs, curTime, curZone); } catch (e) { return null; }
    if (curMisfits < 2) return null;
    // Puzzle n is live from B + n days: every result narrows B. Too wide (or inconsistent) means no answer.
    let lo = -Infinity;
    let hi = Infinity;
    obs.forEach((o) => { lo = Math.max(lo, o.at - (o.n + 1) * DAY_MS); hi = Math.min(hi, o.at - o.n * DAY_MS); });
    if (!(hi > lo) || hi - lo > 90 * 60000) return null;
    const fits = [];
    const seen = new Set();
    const consider = (t, z) => {
      const k = `${t} ${z}`;
      if (seen.has(k) || (t === curTime && z === curZone) || (isNamedZone(z) && !isValidTimeZone(z))) return;
      seen.add(k);
      if (resetFitsObservations(obs, t, z)) fits.push({ resetTime: t, resetZone: z });
    };
    SUGGEST_ZONES.forEach((z) => consider('00:00', z));
    [curZone, 'local', 'utc'].forEach((z) => { for (let hr = 0; hr < 24; hr++) consider(`${String(hr).padStart(2, '0')}:00`, z); });
    // Daily games nearly always reset at midnight somewhere, so a single midnight wins over other
    // hours that happen to fit too (midnight New York over 05:00 UTC in November).
    const pick = (list) => {
      if (!list.length) return null;
      const sigs = new Set(list.map((f) => scheduleSignature(f.resetTime, f.resetZone, obs[0].at)));
      if (sigs.size !== 1) return null;
      // All the same schedule: keep the game's own zone if it's one of them, then local time, then UTC.
      return list.find((f) => f.resetZone === curZone) || list.find((f) => f.resetZone === 'local')
        || list.find((f) => f.resetZone === 'utc') || list[0];
    };
    const midnights = fits.filter((f) => f.resetTime === '00:00');
    return midnights.length ? pick(midnights) : pick(fits);
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

  /**
   * The lines of recognised screenshot text that look like a result ("Wordle 1,234 3/6", "Solved in
   * 4 guesses", "#512", "Streak 12"), for the log's score box. Falls back to the first few lines.
   */
  function extractResultText(text) {
    const lines = String(text || '').split(/\r?\n/).map((l) => l.trim()).filter(Boolean).slice(0, 80);
    const looks = (l) => SCORE_RE.test(l) || /#\s?\d{2,}/.test(l)
      || /\b(\d+\s*(guess|guesses|tries|attempts|moves|points|pts|mistakes?|hints?|seconds|sec|s)\b|solved|streak|score|time|you (won|got it|lost)|well done|congrat|splendid|magnificent|impressive|great|phew|genius|next (puzzle|game))/i.test(l)
      || (/\b\d{1,2}:\d{2}\b/.test(l) && !/^\d{1,2}:\d{2}(\s*(am|pm))?$/i.test(l)); // a time, but not the status-bar clock
    const picked = lines.filter(looks).slice(0, 8);
    const out = (picked.length ? picked : lines.slice(0, 6)).join('\n');
    return out.slice(0, 600);
  }

  return {
    extractResultText,
    scoreStats,
    parseResultValue,
    formatClock,
    puzzleNumber,
    resetFitsObservations,
    suggestResetTime,
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
    mentionsGame,
    gameMention,
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
    rescheduleDays,
    cleanDayHistory,
    recomputeGameStatsFromHistory,
    indexHistoryLogs
  };
});
