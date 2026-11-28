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
  // 1.8.12: small pure helpers below are called for every logged result (achievements, stats), tens
  // of thousands of times with a long history, mostly with the same few inputs: their answers are kept.
  function memo(fn, max) {
    const cache = new Map();
    return function (key) {
      let v = cache.get(key);
      if (v === undefined && !cache.has(key)) {
        v = fn(key);
        if (cache.size >= (max || 20000)) cache.clear();
        cache.set(key, v);
      }
      return v;
    };
  }
  const normalizeZoneMemo = memo((raw) => normalizeZoneUncached(raw), 500);
  function normalizeZone(zone) {
    return normalizeZoneMemo(String(zone || '').trim());
  }
  function normalizeZoneUncached(raw) {
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

  const parseHHMMMemo = memo((text) => {
    const parts = text.split(':');
    const h = Math.min(23, Math.max(0, parseInt(parts[0] || '0', 10) || 0));
    const m = Math.min(59, Math.max(0, parseInt(parts[1] || '0', 10) || 0));
    return Object.freeze({ h, m });
  }, 500);
  function parseHHMM(resetTime) {
    return parseHHMMMemo(String(resetTime || '00:00'));
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
  const resetOnDayMemo = memo((k) => {
    const [resetTime, zone, y, mo, d, dayOffset] = k.split('|'); // (a local key ends with the phone's zone)
    const ref = zone === 'utc' ? new Date(Date.UTC(+y, +mo, +d, 12)) : new Date(+y, +mo, +d, 12);
    return resetOnDayUncached(resetTime, zone, ref, +dayOffset).getTime();
  });
  // The phone's own time zone, looked at no more than once a second: a local reset depends on it (the
  // phone may travel while the hub is open).
  let localZoneSig = '';
  let localZoneOffset = NaN;
  let localZoneAt = 0;
  function localZone() {
    const now = Date.now();
    const off = new Date().getTimezoneOffset();
    if (off !== localZoneOffset || now - localZoneAt > 1000 || now < localZoneAt) {
      localZoneAt = now;
      localZoneOffset = off;
      try { localZoneSig = Intl.DateTimeFormat().resolvedOptions().timeZone || ''; } catch (e) { localZoneSig = ''; }
      localZoneSig += `@${off}`;
    }
    return localZoneSig;
  }
  function resetOnDay(resetTime, zone, ref, dayOffset) {
    const z = normalizeZone(zone) === 'utc' ? 'utc' : 'local';
    const key = z === 'utc'
      ? `${resetTime}|utc|${ref.getUTCFullYear()}|${ref.getUTCMonth()}|${ref.getUTCDate()}|${dayOffset}`
      : `${resetTime}|local|${ref.getFullYear()}|${ref.getMonth()}|${ref.getDate()}|${dayOffset}|${localZone()}`;
    return new Date(resetOnDayMemo(key));
  }
  function resetOnDayUncached(resetTime, zone, ref, dayOffset) {
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

  const cycleKeyToDayMemo = memo((key) => {
    const p = key.split('-').map((n) => parseInt(n, 10));
    if (p.length !== 3 || p.some((n) => isNaN(n))) return null;
    return Math.round(Date.UTC(p[0], p[1] - 1, p[2]) / 86400000);
  });
  function cycleKeyToDay(key) {
    return cycleKeyToDayMemo(String(key || ''));
  }

  const addDaysMemo = memo((k) => {
    const at = k.lastIndexOf('|');
    return addDaysToCycleKeyUncached(k.slice(0, at), Number(k.slice(at + 1)));
  });
  function addDaysToCycleKey(key, deltaDays) {
    return addDaysMemo(`${String(key || '')}|${Number(deltaDays) || 0}`);
  }
  function addDaysToCycleKeyUncached(key, deltaDays) {
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
    } else {
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

  /**
   * Sorts history log rows newest-first by cycle `date` descending, then `loggedAt` descending.
   * Preserves relative order of ties. Mutates and returns `logs` (or [] if not an array).
   */
  function sortHistoryLogs(logs) {
    if (!Array.isArray(logs)) return [];
    return logs.sort((a, b) => {
      const da = a && typeof a.date === 'string' ? a.date : '';
      const db = b && typeof b.date === 'string' ? b.date : '';
      if (db !== da) return db.localeCompare(da);
      return (Number(b && b.loggedAt) || 0) - (Number(a && a.loggedAt) || 0);
    });
  }

  /**
   * Recomputes `.streak` on every log row belonging to `game` in chronological order using
   * `game.dayHistory` (consecutive 'completed' days ending on each row's cycle date, 0 when
   * missed or absent), and sorts `logs` newest-first. Returns `logs`.
   */
  function recomputeLogStreaks(game, logs) {
    if (!Array.isArray(logs)) return [];
    if (!game || typeof game !== 'object') return sortHistoryLogs(logs);
    const hist = game.dayHistory && typeof game.dayHistory === 'object' ? game.dayHistory : {};
    const mine = logs.filter((l) => l && (l.gameId ? l.gameId === game.id : l.gameName === game.name));
    if (mine.length) {
      const dates = [...new Set(mine.map((l) => l.date).filter((d) => DATE_KEY_RE.test(String(d))))].sort();
      const streakByDate = new Map();
      let run = 0;
      let prevDay = null;
      for (const d of dates) {
        if (hist[d] !== 'completed') {
          run = 0;
          prevDay = null;
          streakByDate.set(d, 0);
          continue;
        }
        const dayNum = cycleKeyToDay(d);
        if (prevDay !== null && dayNum === prevDay + 1) {
          run += 1;
        } else {
          // Walk backwards through any earlier completed days in dayHistory that lack log rows.
          let back = 1;
          let cursor = addDaysToCycleKey(d, -1);
          while (hist[cursor] === 'completed') {
            back += 1;
            cursor = addDaysToCycleKey(cursor, -1);
          }
          run = back;
        }
        prevDay = dayNum;
        streakByDate.set(d, run);
      }
      mine.forEach((l) => {
        if (streakByDate.has(l.date)) l.streak = streakByDate.get(l.date);
        else if (l.outcome !== 'Completed') l.streak = 0;
      });
    }
    return sortHistoryLogs(logs);
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
      const host = new URL(String((game && game.url) || '')).hostname.replace(/^(www|m)\./, '').toLowerCase();
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
    // "Krillion Daily Dive" is shared as "Krillion …"; "Far Flung" is shared as "Farflung …".
    const stripped = raw.replace(NAME_PREFIX_RE, '');
    const first = stripped.split(' ')[0];
    if (first.length >= 5 && /^[a-z0-9]+$/.test(first)) add(first);
    const joined = stripped.replace(/ /g, '');
    if (joined !== stripped && joined.length >= 6 && /^[a-z0-9]+$/.test(joined)) add(joined);
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

  const STRICT_NON_DAILY_RE = /(?<![a-z0-9_\/.-])(unlimited|practice|replay|unranked|free[ \t\u00A0]+play|training[ \t\u00A0]+mode|custom[ \t\u00A0]+(?:puzzle|game|grid)|archive)(?![a-z0-9_\/.-])/g;
  const STRICT_INFINITY_ROUND_RE = /(?:^|[ \t\u00A0#])\u221E(?:[ \t\u00A0·:\-]|\n|$)/;

  function isNonDailyResult(t, lower, name, url) {
    if (STRICT_INFINITY_ROUND_RE.test(t) && String(name == null ? '' : name).indexOf('\u221E') < 0) return true;
    const gameCtx = `${asciiLower(name)} ${asciiLower(url)}`;
    STRICT_NON_DAILY_RE.lastIndex = 0;
    let m;
    while ((m = STRICT_NON_DAILY_RE.exec(lower))) {
      if (!gameCtx.includes(m[1])) return true;
    }
    return false;
  }

  function isStrictShareResult(text, game) {
    const t = strictTrim(String(text == null ? '' : text).replace(/\r\n/g, '\n'));
    if (t.length < 3 || t.length > 4000) return false;
    const lower = asciiLower(t);
    if (SINGLE_URL_RE.test(lower)) return false;
    if (isNonDailyResult(t, lower, game && game.name, game && game.url)) return false;
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
    const lower = asciiLower(strictTrim(String(text == null ? '' : text).replace(/\r\n/g, '\n')));
    return gameMention(lower, game && game.name, game && game.url) > 0 || STRICT_PUZZLE_NO_RE.test(lower);
  }

  /**
   * Strict attribution check for out-of-band clipboard captures: requires the text to explicitly
   * mention this game (name, host, or site label) without falling back to a bare `#12` puzzle number.
   */
  function mentionsGameExplicitly(text, gameName, gameUrl) {
    const lower = asciiLower(strictTrim(String(text == null ? '' : text).replace(/\r\n/g, '\n')));
    return gameMention(lower, gameName, gameUrl) >= 1;
  }

  // A share result that says the puzzle was NOT solved ("X/6", "Next time", Connections with four
  // mistakes). Auto-logging records these as Missed (with the score) instead of Solved.
  const FAILED_FRACTION_RE = /(?<![0-9A-Za-z_/])x[ \t\u00A0]*\/[ \t\u00A0]*[0-9]{1,2}(?![0-9/])/i;
  const FAILED_PHRASE_RE = /(?<![A-Za-z0-9_])(?:next time|better luck|not solved|unsolved|did not solve|didn[’']t solve|failed(?![ \t\u00A0]+to(?![A-Za-z]))|out of (?:guesses|tries|lives)|you lost|game over|perdiste|has perdido|perdu|echec|échec|verloren|leider nicht|hai perso|não conseguiu)(?![A-Za-z0-9_])/i;
  const GROUP_SQUARES = new Set(['\u{1F7E8}', '\u{1F7E9}', '\u{1F7E6}', '\u{1F7EA}']); // yellow green blue purple
  // Fraction games where higher N/M is better (Waffle stars, GeoGrid/PokeDoku/Immaculate Grid/NintenDoku/etc.).
  const HIGHER_FRACTION_CONTEXT_RE = /#waffle\b|wafflegame|geogrid|boxofficega|moviegrid|immaculate\s*grid|pokedoku|nintendoku|crossover|\bscore[ \t\u00A0]*:[ \t\u00A0]*[0-9]{1,2}[ \t\u00A0]*\/[ \t\u00A0]*[0-9]{1,2}\b|\bstars?\b/i;
  // Leading date patterns ("Played 11/13", "Date: 11/13/2026", "11/13/2026") that should not masquerade as N/M scores.
  const LEADING_DATE_RE = /\b(?:played|date|on)[ \t\u00A0:]+[0-9]{1,2}\/[0-9]{1,2}(?:\/[0-9]{2,4})?\b|\b[0-9]{1,2}\/[0-9]{1,2}\/[0-9]{2,4}\b/gi;
  // Guess-based 0/M (e.g. "Wordle #1234 0/6") when not in a higher-is-better score/grid context.
  const ZERO_FRACTION_RE = /(?:^|[^\d/.\-])0\s*\/\s*(\d{1,2})(?![\d/:,])/;
  function stripNonScoreDates(text) {
    return String(text || '').replace(LEADING_DATE_RE, ' ');
  }

  function isFailedShareResult(text) {
    const t = String(text || '').trim();
    if (!t) return false;
    if (FAILED_FRACTION_RE.test(t) || FAILED_PHRASE_RE.test(t)) return true;
    const cleaned = stripNonScoreDates(t);
    const zm = ZERO_FRACTION_RE.exec(cleaned);
    if (zm) {
      const zMax = Number(zm[1]);
      if (zMax >= 3 && zMax <= 99 && !HIGHER_FRACTION_CONTEXT_RE.test(t)) return true;
    }
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

  const SCORE_RE = /(^|[^\d/.\-])(\d{1,2}|[xX])\s*\/\s*(\d{1,2})(?![\d/:,])/;
  const SCORE_GLOBAL_RE = /(^|[^\d/.\-])(\d{1,2}|[xX])\s*\/\s*(\d{1,2})(?![\d/:,])/g;
  // A slash fraction that is followed by a non-guess unit ("42/42 words", "12/15 points", "1/2 Found") or is clearly a date ("Squaredle 11/13:").
  const SCORE_AFTER_NON_GUESS_RE = /^[ \t\u00A0]*(?:words?|pts|points?|%)/i;

  /** "3/6" -> { n: 3, max: 6 }; "X/6" -> { n: null, max: 6, fail: true }; null when there's no N/M score. */
  function parseScore(text) {
    const raw = String(text || '');
    const cleaned = stripNonScoreDates(raw);
    SCORE_GLOBAL_RE.lastIndex = 0;
    let m;
    while ((m = SCORE_GLOBAL_RE.exec(cleaned))) {
      const max = Number(m[3]);
      if (!(max >= 3 && max <= 99)) continue;
      const after = cleaned.slice(m.index + m[0].length);
      if (/x/i.test(m[2])) return { n: null, max, fail: true };
      const n = Number(m[2]);
      if (n < 0 || n > max) continue;
      // Skip "11/13:" style header dates (not followed by guess grids/scores) and "42/42 words" / "12/15 points" progress/point tallies.
      if (max <= 31 && n >= 1 && n <= 12 && /^:/.test(after) && !HIGHER_FRACTION_CONTEXT_RE.test(raw)) continue;
      if (SCORE_AFTER_NON_GUESS_RE.test(after)) continue;
      if (n === 0) {
        if (HIGHER_FRACTION_CONTEXT_RE.test(raw)) return { n: 0, max, fail: false };
        return { n: null, max, fail: true };
      }
      return { n, max, fail: false };
    }
    return null;
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
  // "38,412/50,000" (TimeGuessr, FoodGuessr) or "265/500 km*" (Farflung): a total out of a maximum.
  const POINTS_OF_RE = /(?<![0-9.,\/])([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,7})[ \t\u00A0]*\/[ \t\u00A0]*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{3,7})(?![0-9\/])(?:[ \t\u00A0]*(pts|points?|xp|km|m|meters?|metres?)(?![A-Za-z0-9_]))?/i;
  // A unit-less "m" is minutes ("58m"), unless it reads as a distance (Krillion's "4,250m" depth).
  const DISTANCE_CONTEXT_RE = /depth|deep|dive|metre|meter|distance|krillion/i;
  const GOLF_CONTEXT_RE = /\u26F3|golf|strokes?|(?<![A-Za-z])par(?![A-Za-z])/i;
  const SCORE_LABEL_RE = /(?<![A-Za-z0-9_])(?:score|points|total)[ \t\u00A0]*[:=]?[ \t\u00A0]*([-\u2212]?)([0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]+)?|[0-9]{1,7}(?:\.[0-9]+)?)(?![0-9])/gi;
  const NOT_THIS_SCORE_RE = /(?:high|best|top|record|max|average|avg)[ \t\u00A0]*$/i;
  const COUNT_RE = /(?<![A-Za-z0-9_#.,\/])([0-9]{1,4})[ \t\u00A0]*(guess(?:es)?|tries|try|attempts?|moves?|mistakes?|swaps?|clicks?|steps?|strokes?|shots?|hints?|words?|clues?)(?![A-Za-z0-9_])/i;
  // "FE Guess 512 - guessed in 4" (not "solved in 4 min").
  const GUESSED_IN_RE = /(?<![A-Za-z0-9_])(?:guessed|solved|got it)[ \t\u00A0]+in[ \t\u00A0]+([0-9]{1,3})(?![0-9:.,\/]|[ \t\u00A0]*(?:s|secs?|seconds?|m|mins?|minutes?|h|hrs?|hours?)(?![A-Za-z]))/i;
  const BARE_NUMBER_LINE_RE = /^[ \t\u00A0]*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,7})[ \t\u00A0]*$/;

  const num = (s) => Number(String(s).replace(/,/g, ''));
  const signed = (sign, s) => (sign ? -1 : 1) * num(s);

  /**
   * What a result's score is, so like can be compared with like:
   *   guesses "3/6"          -> { kind: 'guesses', n, max, group: 'g6', rank: n (lower is better) }
   *   higher fractions       -> { kind: 'points', value: n, max, group: 'hi:9', rank: -n (higher is better) }
   *   time "1:42", "1m 42s"  -> { kind: 'time', ms, group: 'time', rank: ms (lower is better) }
   *   count "45 guesses"     -> { kind: 'count', n, unit, group: 'c:guesses', rank: n (lower is better) }
   *   points "2,450 pts", "Score: 850", "4,250m", or a line that is only a number ("60")
   *                          -> { kind: 'points', value, unit, group: 'p:pts', rank: -value (higher is better) }
   * Each has a `label` for display. Null when the text has no score, or for a failed guess score.
   */
  function parseResultValue(text) {
    const t = String(text || '');
    if (!t.trim()) return null;
    const cleaned = stripNonScoreDates(t);
    let m = POINTS_OF_RE.exec(cleaned);
    if (m && num(m[1]) <= num(m[2])) {
      const value = num(m[1]);
      const uRaw = (m[3] || '').toLowerCase();
      const unit = uRaw === 'km' ? 'km' : (/^(m|meters?|metres?)$/.test(uRaw) ? 'm' : (uRaw === 'xp' ? 'xp' : 'pts'));
      return { kind: 'points', value, unit, group: `p:${unit}`, rank: -value, label: pointsLabel(value, unit) };
    }
    const g = parseScore(cleaned);
    if (g) {
      if (g.fail) return null;
      if (HIGHER_FRACTION_CONTEXT_RE.test(t)) {
        return { kind: 'points', value: g.n, max: g.max, unit: '', group: `hi:${g.max}`, rank: -g.n, label: `${g.n}/${g.max}` };
      }
      return { kind: 'guesses', n: g.n, max: g.max, group: `g${g.max}`, rank: g.n, label: `${g.n}/${g.max}` };
    }
    m = TIME_COLON_RE.exec(cleaned);
    if (m) {
      const ms = (m[3] !== undefined ? (Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3])) : (Number(m[1]) * 60 + Number(m[2]))) * 1000;
      if (ms > 0) return { kind: 'time', ms, group: 'time', rank: ms, label: formatClock(ms) };
    }
    m = TIME_UNITS_RE.exec(cleaned);
    if (m) {
      const ms = Math.round(((Number(m[1]) || 0) * 3600 + (Number(m[2]) || 0) * 60 + Number(m[3])) * 1000);
      if (ms > 0) return { kind: 'time', ms, group: 'time', rank: ms, label: formatClock(ms) };
    }
    m = TIME_HM_RE.exec(cleaned);
    if (m) {
      const ms = (Number(m[1]) * 3600 + Number(m[2]) * 60) * 1000;
      if (ms > 0) return { kind: 'time', ms, group: 'time', rank: ms, label: formatClock(ms) };
    }
    m = COUNT_RE.exec(cleaned);
    if (m) {
      const unit = m[2].toLowerCase().replace(/^(guess)(es)?$/, 'guesses').replace(/^(tries|try)$/, 'tries')
        .replace(/^attempts?$/, 'attempts').replace(/^moves?$/, 'moves').replace(/^mistakes?$/, 'mistakes')
        .replace(/^swaps?$/, 'swaps').replace(/^clicks?$/, 'clicks').replace(/^steps?$/, 'steps')
        .replace(/^strokes?$/, 'strokes').replace(/^shots?$/, 'shots').replace(/^hints?$/, 'hints')
        .replace(/^words?$/, 'words').replace(/^clues?$/, 'clues');
      const n = Number(m[1]);
      return { kind: 'count', n, unit, group: `c:${unit}`, rank: n, label: `${n} ${unit}` };
    }
    m = GUESSED_IN_RE.exec(cleaned);
    if (m) { const n = Number(m[1]); if (n > 0) return { kind: 'count', n, unit: 'guesses', group: 'c:guesses', rank: n, label: `${n} guesses` }; }
    m = POINTS_RE.exec(cleaned);
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
    while ((m = SCORE_LABEL_RE.exec(cleaned))) {
      // "High score: 900 Score: 100" is a 100.
      if (NOT_THIS_SCORE_RE.test(cleaned.slice(0, m.index))) continue;
      const value = signed(m[1], m[2]);
      return { kind: 'points', value, unit: 'pts', group: 'p:pts', rank: -value, label: pointsLabel(value, 'pts') };
    }
    m = TIME_MINUTES_RE.exec(cleaned);
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
  function maxOf(iter, init) {
    let best = init;
    for (const v of iter) { if (v > best) best = v; }
    return best;
  }
  function minOf(iter, init) {
    let best = init;
    for (const v of iter) { if (v < best) best = v; }
    return best;
  }

  function dominantScores(rows) {
    const entries = [];
    const list = Array.isArray(rows) ? rows : [];
    for (let i = 0; i < list.length; i++) {
      const r = list[i];
      if (!r || r.outcome !== 'Completed') continue;
      const v = parseResultValue(r.score);
      if (!v) continue;
      entries.push({ v, date: typeof r.date === 'string' ? r.date : '', loggedAt: Number(r.loggedAt) || 0, idx: i });
    }
    if (!entries.length) return { group: null, values: [] };
    const counts = new Map();
    entries.forEach((e) => counts.set(e.v.group, (counts.get(e.v.group) || 0) + 1));
    // A tie goes to the kind of the most recent result (largest date, then loggedAt, then earliest index in newest-first order).
    const top = maxOf(counts.values(), -Infinity);
    let bestEntry = null;
    for (const e of entries) {
      if (counts.get(e.v.group) !== top) continue;
      if (!bestEntry
        || e.date > bestEntry.date
        || (e.date === bestEntry.date && e.loggedAt > bestEntry.loggedAt)
        || (e.date === bestEntry.date && e.loggedAt === bestEntry.loggedAt && e.idx < bestEntry.idx)) {
        bestEntry = e;
      }
    }
    const group = bestEntry.v.group;
    return { group, values: entries.filter((e) => e.v.group === group).map((e) => e.v) };
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
      fastestMs: times.length ? minOf(times, Infinity) : null,
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
      let bestPrevRank = Infinity;
      let hasEarlier = false;
      for (const r of prev) {
        const p = parseResultValue(r.score);
        if (p && p.group === sc.group) {
          hasEarlier = true;
          if (p.rank < bestPrevRank) bestPrevRank = p.rank;
        }
      }
      if (hasEarlier && sc.rank < bestPrevRank) out.push({ kind: 'score', value: sc.label });
    }
    const t = Number(row.timeMs);
    if (t > 0) {
      let bestPrevTime = Infinity;
      let hasEarlierT = false;
      for (const r of prev) {
        const x = Number(r.timeMs);
        if (x > 0) {
          hasEarlierT = true;
          if (x < bestPrevTime) bestPrevTime = x;
        }
      }
      if (hasEarlierT && t < bestPrevTime) out.push({ kind: 'time', value: formatDuration(t) });
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
    return { bestScore: best ? best.label : null, bestKind: best ? best.kind : null, fastestMs: times.length ? minOf(times, Infinity) : null, bestStreak: Number(bestStreak) || 0 };
  }

  // ---- Achievements (1.5.0: two tiers each) ----
  // Each tier is earned when its metric reaches the target (or, with `below`, drops under it).
  const HOUR = 3600000;
  const ACHIEVEMENTS = [
    { id: 'first', icon: 'star', title: 'First solve', tiers: [
      { desc: 'Solve your first puzzle', metric: 'solves', target: 1 },
      { desc: 'Solve a puzzle in 5 different games', metric: 'gamesSolved', target: 5 }] },
    { id: 'started', icon: 'puzzle', title: 'Getting started', tiers: [
      { desc: 'Solve 10 puzzles', metric: 'solves', target: 10 },
      { desc: 'Solve 100 puzzles', metric: 'solves', target: 100 }] },
    { id: 'master', icon: 'medal', title: 'Puzzle master', tiers: [
      { desc: 'Solve 500 puzzles', metric: 'solves', target: 500 },
      { desc: 'Solve 1,000 puzzles', metric: 'solves', target: 1000 }] },
    { id: 'roll', icon: 'flame', title: 'On a roll', tiers: [
      { desc: 'Reach a 7-day streak', metric: 'streak', target: 7 },
      { desc: 'Reach a 30-day streak', metric: 'streak', target: 30 }] },
    { id: 'centurion', icon: 'rocket', title: 'Centurion', tiers: [
      { desc: 'Reach a 100-day streak', metric: 'streak', target: 100 },
      { desc: 'Reach a 365-day streak', metric: 'streak', target: 365 }] },
    { id: 'perfectWeek', icon: 'sparkle', title: 'Perfect week', tiers: [
      { desc: 'Solve every game you track, 7 days in a row', metric: 'perfectDays', target: 7 },
      { desc: 'Solve every game you track, 30 days in a row', metric: 'perfectDays', target: 30 }] },
    { id: 'variety', icon: 'palette', title: 'Variety pack', tiers: [
      { desc: 'Solve 5 different games in one day', metric: 'variety', target: 5 },
      { desc: 'Solve 10 different games in one day', metric: 'variety', target: 10 }] },
    { id: 'lucky', icon: 'clover', title: 'Two-guess wonder', tiers: [
      { desc: 'Solve a guessing game in 2 or fewer guesses', metric: 'lucky', target: 1 },
      { desc: 'Solve a guessing game in 2 or fewer guesses 10 times', metric: 'lucky', target: 10 }] },
    { id: 'speed', icon: 'bolt', title: 'Speed run', tiers: [
      { desc: 'Solve a puzzle in under a minute', metric: 'fastest', target: 60000, below: true },
      { desc: 'Solve a puzzle in under 20 seconds', metric: 'fastest', target: 20000, below: true }] },
    { id: 'collector', icon: 'layers', title: 'Collector', tiers: [
      { desc: 'Track 10 games', metric: 'games', target: 10 },
      { desc: 'Track 25 games', metric: 'games', target: 25 }] },
    { id: 'earlyBird', icon: 'sunrise', title: 'Early bird', tiers: [
      { desc: 'Solve a game within an hour of its reset', metric: 'early', target: 1 },
      { desc: 'Solve a game within an hour of its reset 25 times', metric: 'early', target: 25 }] },
    { id: 'nightOwl', icon: 'moon', title: 'Night owl', tiers: [
      { desc: 'Solve a game in the last hour before its reset', metric: 'late', target: 1 },
      { desc: 'Solve a game in the last hour before its reset 25 times', metric: 'late', target: 25 }] },
    { id: 'holeInOne', icon: 'bullseye', title: 'Hole in one', tiers: [
      { desc: 'Solve a guessing game on the first guess', metric: 'holeInOne', target: 1 },
      { desc: 'Solve a guessing game on the first guess 3 times', metric: 'holeInOne', target: 3 }] },
    { id: 'records', icon: 'trophy', title: 'Record breaker', tiers: [
      { desc: 'Set a personal best', metric: 'records', target: 1 },
      { desc: 'Set 25 personal bests', metric: 'records', target: 25 }] },
    { id: 'cleanSweep', icon: 'sweep', title: 'Clean sweep', tiers: [
      { desc: 'Solve every game you track on one day (at least 3 games)', metric: 'sweeps', target: 1 },
      { desc: 'Have 50 clean-sweep days', metric: 'sweeps', target: 50 }] },
    { id: 'bounceBack', icon: 'rebound', title: 'Bounce back', tiers: [
      { desc: 'Solve a game the day after missing it', metric: 'bounces', target: 1 },
      { desc: 'Bounce back 10 times', metric: 'bounces', target: 10 }] },
    { id: 'regular', icon: 'calendar', title: 'Regular', tiers: [
      { desc: 'Solve something on 30 different days', metric: 'days', target: 30 },
      { desc: 'Solve something on 365 different days', metric: 'days', target: 365 }] },
    { id: 'marathon', icon: 'hourglass', title: 'Marathon', tiers: [
      { desc: 'Play for 10 hours in the game player', metric: 'playMs', target: 10 * HOUR, scale: HOUR, unit: 'hours' },
      { desc: 'Play for 50 hours in the game player', metric: 'playMs', target: 50 * HOUR, scale: HOUR, unit: 'hours' }] },
    { id: 'weekend', icon: 'weekend', title: 'Weekend warrior', tiers: [
      { desc: 'Solve on both days of 4 weekends', metric: 'weekends', target: 4 },
      { desc: 'Solve on both days of 26 weekends', metric: 'weekends', target: 26 }] }
  ];
  /** "roll#2": tier 2 of On a roll. Earned keys are kept in the hub's state (state.achievements). */
  const tierKey = (id, tier) => `${id}#${tier}`;
  const TIER1_KEYS = ACHIEVEMENTS.map((a) => tierKey(a.id, 1));
  const ALL_TIER_KEYS = ACHIEVEMENTS.flatMap((a) => a.tiers.map((t, i) => tierKey(a.id, i + 1)));

  /**
   * Secret achievements (1.7.0): hidden ("???") until earned; finding them all unlocks Ruby. One
   * tier each; their keys are "secret:<id>" (kept in state.achievements like tier keys).
   */
  const SECRET_ACHIEVEMENTS = [
    { id: 'firstInLine', icon: 'bolt1', title: 'First in line', desc: 'Solve a puzzle within 5 minutes of it coming out', metric: 'firstInLine', target: 1 },
    { id: 'nightShift', icon: 'crescent', title: 'Night shift', desc: 'Solve a puzzle between 2 and 4 AM', metric: 'nightShift', target: 1 },
    { id: 'clutch', icon: 'shield', title: 'Clutch', desc: 'Solve a guessing game on its very last guess 5 times', metric: 'clutch', target: 5 },
    { id: 'dawnPatrol', icon: 'dawn', title: 'Dawn patrol', desc: 'Solve 5 different games before 8 AM on one day', metric: 'dawnPatrol', target: 5 },
    { id: 'deepDive', icon: 'compass', title: 'Deep dive', desc: 'Play for 2 hours in one day', metric: 'deepDive', target: 2 * HOUR }
    // 'challenger' (finish the daily challenge 7 days in a row) went with the daily challenge in 1.8.2.
  ];
  const SECRET_KEYS = SECRET_ACHIEVEMENTS.map((a) => `secret:${a.id}`);

  /** What the secret achievements measure. Times of day are the phone's local time when a result was logged. */
  function secretMetrics(games, logs) {
    const gs = Array.isArray(games) ? games.filter((g) => g && typeof g === 'object') : [];
    const byId = new Map(gs.map((g) => [g.id, g]));
    const solved = (Array.isArray(logs) ? logs : []).filter((r) => r && r.outcome === 'Completed');
    let firstInLine = 0;
    let nightShift = 0;
    const dawn = new Map();
    const playByDay = new Map();
    solved.forEach((r) => {
      const at = Number(r.loggedAt);
      if (Number(r.timeMs) > 0 && typeof r.date === 'string') playByDay.set(r.date, (playByDay.get(r.date) || 0) + Number(r.timeMs));
      if (!(at > 0) || !Number.isFinite(at)) return;
      const d = new Date(at);
      const h = d.getHours();
      if (h >= 2 && h < 4) nightShift += 1;
      if (h < 8) {
        const day = `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
        if (!dawn.has(day)) dawn.set(day, new Set());
        dawn.get(day).add(r.gameId || r.gameName || '');
      }
      const g = byId.get(r.gameId);
      if (!g) return;
      try {
        const start = getNextResetDate(g.resetTime, g.resetZone, new Date(at - 20 * HOUR)).getTime();
        if (start <= at && at - start < 5 * 60000) firstInLine += 1;
      } catch (e) {}
    });
    const clutch = solved.map((r) => parseScore(r.score)).filter((p) => p && !p.fail && p.max >= 4 && p.n === p.max).length;
    let dawnPatrol = 0;
    for (const s of dawn.values()) { if (s.size > dawnPatrol) dawnPatrol = s.size; }
    return {
      firstInLine,
      nightShift,
      clutch,
      dawnPatrol,
      deepDive: maxOf(playByDay.values(), 0)
    };
  }

  /** Each secret achievement with whether it's earned (its details stay hidden in the hub until then). */
  function computeSecrets(games, logs) {
    const m = secretMetrics(games, logs);
    return SECRET_ACHIEVEMENTS.map((a) => ({
      id: a.id, key: `secret:${a.id}`, icon: a.icon, title: a.title, desc: a.desc,
      earned: (m[a.metric] || 0) >= a.target
    }));
  }

  // Before 1.5.0 each achievement was one badge; each maps to the tiers it now stands for.
  const LEGACY_ACHIEVEMENTS = {
    first: ['first#1'], solves10: ['started#1'], solves100: ['started#1', 'started#2'],
    solves500: ['master#1'], solves1000: ['master#1', 'master#2'],
    streak7: ['roll#1'], streak30: ['roll#1', 'roll#2'], streak100: ['centurion#1'], streak365: ['centurion#1', 'centurion#2'],
    perfectWeek: ['perfectWeek#1'], variety: ['variety#1'], lucky: ['lucky#1'], speed: ['speed#1'], collector: ['collector#1']
  };
  /** Earned keys in the 1.5.0 form; old ids become their tiers, keys from newer versions are kept. */
  function migrateAchievementKeys(list) {
    const out = new Set();
    (Array.isArray(list) ? list : []).forEach((k) => {
      if (typeof k !== 'string') return;
      if (k.includes('#') || /^secret:[A-Za-z]+$/.test(k)) out.add(k);
      else (LEGACY_ACHIEVEMENTS[k] || []).forEach((x) => out.add(x));
    });
    return [...out];
  }

  /** Each tracked day (sorted) with whether every game eligible that day was solved, and how many were eligible. */
  function trackedDays(games) {
    const list = (Array.isArray(games) ? games : []).filter((g) => g && g.dayHistory && typeof g.dayHistory === 'object');
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
    return [...allDays].sort((a, b) => a - b).map((d) => {
      const eligible = [...first.keys()].filter((g) => first.get(g) <= d);
      return { day: d, eligible: eligible.length, ok: eligible.length > 0 && eligible.every((g) => done.get(g).has(d)) };
    });
  }

  /** Longest run of consecutive dates on which every eligible game was solved. */
  function perfectRun(games, days) {
    let best = 0;
    let run = 0;
    let prev = null;
    (days || trackedDays(games)).forEach(({ day, ok }) => {
      run = ok ? (prev !== null && day === prev + 1 && run > 0 ? run + 1 : 1) : 0;
      prev = day;
      if (run > best) best = run;
    });
    return best;
  }

  /** The numbers the achievements are measured by, from the games list and the result log. */
  function achievementMetrics(games, logs) {
    const gs = Array.isArray(games) ? games.filter((g) => g && typeof g === 'object') : [];
    const rows = Array.isArray(logs) ? logs.filter((l) => l && typeof l === 'object') : [];
    const solvedRows = rows.filter((r) => r.outcome === 'Completed');
    const gameKey = (r) => r.gameId || r.gameName || '';
    const byId = new Map(gs.map((g) => [g.id, g]));
    const byDay = new Map();
    solvedRows.forEach((r) => {
      if (!byDay.has(r.date)) byDay.set(r.date, new Set());
      byDay.get(r.date).add(gameKey(r));
    });
    const guess = solvedRows.map((r) => parseScore(r.score)).filter((p) => p && !p.fail);
    const times = solvedRows.map((r) => Number(r.timeMs)).filter((x) => x > 0 && Number.isFinite(x));
    // Early bird / night owl: results logged within an hour after / before the game's reset.
    let early = 0;
    let late = 0;
    solvedRows.forEach((r) => {
      const at = Number(r.loggedAt);
      const g = byId.get(r.gameId);
      if (!g || !(at > 0) || !Number.isFinite(at)) return;
      try {
        const next = getNextResetDate(g.resetTime, g.resetZone, new Date(at)).getTime();
        if (next - at <= HOUR) late += 1;
        const start = getNextResetDate(g.resetTime, g.resetZone, new Date(at - 20 * HOUR)).getTime();
        if (start <= at && at - start < HOUR) early += 1;
      } catch (e) {}
    });
    // Personal bests: a solve that beat every earlier comparable result of the same game.
    let records = 0;
    const perGame = new Map();
    solvedRows.forEach((r) => { const k = gameKey(r); if (!perGame.has(k)) perGame.set(k, []); perGame.get(k).push(r); });
    perGame.forEach((list) => {
      const best = new Map();
      list.slice().sort((a, b) => String(a.date).localeCompare(String(b.date)) || (Number(a.loggedAt) || 0) - (Number(b.loggedAt) || 0))
        .forEach((r) => {
          const v = parseResultValue(r.score);
          if (!v) return;
          if (best.has(v.group) && v.rank < best.get(v.group)) records += 1;
          if (!best.has(v.group) || v.rank < best.get(v.group)) best.set(v.group, v.rank);
        });
    });
    // Days with a solve (from the log and the games' histories), weekends with both days solved.
    const solvedDays = new Set(solvedRows.map((r) => r.date).filter((d) => DATE_KEY_RE.test(String(d))));
    let bounces = 0;
    gs.forEach((g) => {
      const h = g.dayHistory && typeof g.dayHistory === 'object' ? g.dayHistory : {};
      Object.keys(h).forEach((k) => {
        if (!DATE_KEY_RE.test(k)) return;
        if (h[k] === 'completed') solvedDays.add(k);
        if (h[k] === 'missed' && h[addDaysToCycleKey(k, 1)] === 'completed') bounces += 1;
      });
    });
    let weekends = 0;
    solvedDays.forEach((k) => {
      const [y, m, d] = k.split('-').map(Number);
      if (new Date(Date.UTC(y, m - 1, d)).getUTCDay() === 6 && solvedDays.has(addDaysToCycleKey(k, 1))) weekends += 1;
    });
    const active = gs.filter((g) => !g.archived);
    const activeDays = trackedDays(active);
    let maxStreak = 0;
    for (const g of gs) {
      const s = Math.max(Number(g.bestStreak) || 0, Number(g.streak) || 0);
      if (s > maxStreak) maxStreak = s;
    }
    for (const r of rows) {
      const s = Number(r.streak) || 0;
      if (s > maxStreak) maxStreak = s;
    }
    let variety = 0;
    for (const set of byDay.values()) { if (set.size > variety) variety = set.size; }
    return {
      solves: solvedRows.length,
      gamesSolved: new Set(solvedRows.map(gameKey)).size,
      streak: maxStreak,
      perfectDays: perfectRun(active, activeDays),
      variety,
      lucky: guess.filter((p) => p.max >= 5 && p.n <= 2).length,
      holeInOne: guess.filter((p) => p.max >= 3 && p.n === 1).length,
      fastest: times.length ? minOf(times, Infinity) : Infinity,
      games: gs.length,
      early,
      late,
      records,
      sweeps: activeDays.filter((d) => d.ok && d.eligible >= 3).length,
      bounces,
      days: solvedDays.size,
      playMs: times.reduce((a, b) => a + b, 0),
      weekends
    };
  }

  /**
   * Every achievement with the tier the data earns (0, 1 or 2), the earned tier keys, and the next
   * tier to go for ({ desc, progress, target }, or null when both are earned).
   */
  function computeAchievements(games, logs) {
    const m = achievementMetrics(games, logs);
    return ACHIEVEMENTS.map((a) => {
      const met = a.tiers.map((t) => (t.below ? m[t.metric] < t.target : (m[t.metric] || 0) >= t.target));
      const tier = met[1] && met[0] ? 2 : (met[0] ? 1 : 0);
      const next = a.tiers[tier] || null;
      // Shown in the tier's own unit (Marathon in whole hours, rounded).
      const progressOf = (t) => (t.below ? (m[t.metric] < t.target ? 1 : 0)
        : Math.round(Math.min(t.target, m[t.metric] || 0) / (t.scale || 1)));
      const targetOf = (t) => (t.below ? 1 : Math.round(t.target / (t.scale || 1)));
      return {
        id: a.id, icon: a.icon, title: a.title, tier, tiers: a.tiers.length,
        keys: a.tiers.slice(0, tier).map((t, i) => tierKey(a.id, i + 1)),
        desc: (next || a.tiers[a.tiers.length - 1]).desc,
        next: next ? { desc: next.desc, progress: progressOf(next), target: targetOf(next), below: Boolean(next.below), unit: next.unit || '' } : null,
        // Each tier with whether it's earned and its progress, for the details sheet.
        tierList: a.tiers.map((t, i) => ({ desc: t.desc, earned: met[i] && (i === 0 || met[0]), progress: progressOf(t), target: targetOf(t), below: Boolean(t.below), unit: t.unit || '' })),
        earned: tier >= 1
      };
    });
  }

  /** Earned tier keys from computeAchievements(). */
  function earnedAchievementKeys(list) {
    return (Array.isArray(list) ? list : []).flatMap((a) => a.keys || []);
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
    // Allow 60 min DST slack in the coarse pre-filter; resetFitsObservations() checks exact zone rules.
    let lo = -Infinity;
    let hi = Infinity;
    obs.forEach((o) => { lo = Math.max(lo, o.at - (o.n + 1) * DAY_MS); hi = Math.min(hi, o.at - o.n * DAY_MS); });
    const DST_SLACK = 60 * 60000;
    if (hi + DST_SLACK <= lo || hi - lo > 90 * 60000 + DST_SLACK) return null;
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
    return periodRecap(logs, `${prefix}01`, `${prefix}31`);
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

  // ---- 1.6.0: recaps for any period, tough days, hub sorting, today's combined share, colour themes ----

  /** Monday of the week holding `key` (YYYY-MM-DD), and the Sunday after it. Null for invalid keys. */
  function weekBounds(key) {
    const k = String(key || '');
    if (!DATE_KEY_RE.test(k) || cycleKeyToDay(k) === null) return null;
    const [y, m, d] = k.split('-').map(Number);
    const dt = new Date(Date.UTC(y, m - 1, d));
    if (dt.getUTCFullYear() !== y || dt.getUTCMonth() !== m - 1 || dt.getUTCDate() !== d) return null;
    const dow = dt.getUTCDay(); // 0 = Sunday
    const from = addDaysToCycleKey(k, -((dow + 6) % 7));
    return { from, to: addDaysToCycleKey(from, 6) };
  }

  /**
   * A recap of the result log between two day keys (inclusive): results, solves, win rate, longest
   * streak reached, best guess result, most-played game, best game (most solves, then win rate),
   * distinct games, days played and total play time.
   */
  function periodRecap(logs, fromKey, toKey) {
    const rows = (Array.isArray(logs) ? logs : []).filter((r) => r && typeof r.date === 'string' && r.date >= fromKey && r.date <= toKey);
    const solved = rows.filter((r) => r.outcome === 'Completed');
    const per = new Map();
    rows.forEach((r) => {
      const key = r.gameId || r.gameName || '';
      if (!key) return;
      const e = per.get(key) || { name: r.gameName || key, latestDate: r.date || '', results: 0, solved: 0 };
      if (r.gameName && (!e.latestDate || (r.date && r.date >= e.latestDate))) {
        e.name = r.gameName;
        if (r.date) e.latestDate = r.date;
      }
      e.results += 1;
      if (r.outcome === 'Completed') e.solved += 1;
      per.set(key, e);
    });
    let topGame = null;
    let bestGame = null;
    per.forEach((e) => {
      if (!topGame || e.results > topGame.count) topGame = { name: e.name, count: e.results };
      const rate = e.solved / e.results;
      if (e.solved && (!bestGame || e.solved > bestGame.solved || (e.solved === bestGame.solved && rate > bestGame.rate))) {
        bestGame = { name: e.name, solved: e.solved, results: e.results, rate };
      }
    });
    let best = null;
    solved.forEach((r) => {
      const p = parseScore(r.score);
      if (!p || p.fail) return;
      const ratio = p.n / p.max;
      if (!best || ratio < best.ratio || (ratio === best.ratio && p.n < best.n)) best = { ratio, n: p.n, game: r.gameName || '', score: `${p.n}/${p.max}` };
    });
    const timeMs = solved.reduce((a, r) => a + (Number(r.timeMs) > 0 ? Number(r.timeMs) : 0), 0);
    let bestStreak = 0;
    for (const r of rows) {
      const s = Number(r.streak) || 0;
      if (s > bestStreak) bestStreak = s;
    }
    return {
      from: fromKey,
      to: toKey,
      results: rows.length,
      solved: solved.length,
      winRate: rows.length ? Math.round((solved.length / rows.length) * 100) : null,
      bestStreak,
      bestResult: best ? { game: best.game, score: best.score } : null,
      topGame,
      bestGame: bestGame ? { name: bestGame.name, solved: bestGame.solved, results: bestGame.results } : null,
      games: per.size,
      days: new Set(rows.map((r) => r.date)).size,
      timeMs
    };
  }

  /** How a recap compares with the one before it: change in solves and in win rate (points). */
  function recapChange(cur, prev) {
    if (!cur || !prev || !prev.results) return null;
    return {
      solved: cur.solved - prev.solved,
      winRate: cur.winRate !== null && prev.winRate !== null ? cur.winRate - prev.winRate : null
    };
  }

  function median(sorted) {
    const n = sorted.length;
    return n % 2 ? sorted[(n - 1) / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
  }

  /**
   * How a game is scored, learned from its results (1.8.3): "6 guesses", "Fastest time", "Most points"
   * or "Fewest moves". Looks at the latest 20 results with a readable score (failed N/M grids count
   * for the maximum); needs at least three, at least 70% of them of one kind. Null when unsure.
   */
  function learnScoreFormat(rows) {
    const parsed = [];
    (Array.isArray(rows) ? rows : []).slice().sort((a, b) => (Number(b && b.loggedAt) || 0) - (Number(a && a.loggedAt) || 0)).forEach((r) => {
      if (parsed.length >= 20 || !r) return;
      const text = String(r.score || '');
      const g = parseScore(text);
      if (g) { parsed.push({ kind: 'guesses', max: g.max }); return; }
      const v = parseResultValue(text);
      if (!v) return;
      if (v.kind === 'time') parsed.push({ kind: 'time' });
      else if (v.kind === 'points') parsed.push({ kind: 'points' });
      else if (v.kind === 'count') parsed.push({ kind: `count:${v.unit}` });
    });
    if (parsed.length < 3) return null;
    const tally = new Map();
    parsed.forEach((p) => tally.set(p.kind, (tally.get(p.kind) || 0) + 1));
    const [kind, n] = [...tally.entries()].sort((a, b) => b[1] - a[1])[0];
    if (n / parsed.length < 0.7) return null;
    if (kind === 'guesses') {
      const maxes = new Map();
      parsed.filter((p) => p.kind === 'guesses').forEach((p) => maxes.set(p.max, (maxes.get(p.max) || 0) + 1));
      const max = [...maxes.entries()].sort((a, b) => b[1] - a[1])[0][0];
      return { kind: 'guesses', label: `${max} guesses` };
    }
    if (kind === 'time') return { kind, label: 'Fastest time' };
    if (kind === 'points') return { kind, label: 'Most points' };
    const unit = kind.slice('count:'.length);
    return { kind: 'count', label: `Fewest ${unit}` };
  }

  /** A score format the app filled in by itself (not one the user typed): "Daily Puzzle", "6 guesses", "60s target", "100 pts". */
  function isGenericScoreFormat(text) {
    const t = String(text || '').trim();
    return !t || /^daily puzzle$/i.test(t) || /^\d{1,2} guesses$/i.test(t) || /^\d+s target$/i.test(t) || /^\d+ pts$/i.test(t)
      || /^(fastest time|most points|fewest [a-z]+)$/i.test(t);
  }

  /**
   * Checking game pages for changes (1.8.5): what a background pass found, compared with the games.
   * results: [{ id, url, finalUrl, parsed: { name, description, faviconUrl, resetTime, resetZone, timeFound,
   * zoneFound, metricType, par, category } }]. Only changes worth asking about are returned, and nothing
   * the user already said no to (declined: { [gameId]: { [field]: value } }):
   *  - name and description when the page's differ (ignoring case and spacing);
   *  - the icon when the game uses its site's icon (not an emoji or one of its own) and the site's changed;
   *  - the reset time / time zone only when the page states them;
   *  - the score type only while the game still has the "Daily Puzzle" placeholder (not one learned or typed);
   *  - the category only while the game is "Custom Daily";
   *  - the link when the site moved to another address (a different host).
   * Games in the built-in list are left alone (their details are checked by hand).
   * opts: { isKnown(url), category(nativeCategory) }. Returns [{ id, name, changes: [{ field, from, to }] }].
   */
  /** How a suggested value is remembered once declined (so the same suggestion isn't made again). */
  function recheckKey(value) {
    return value && typeof value === 'object' ? JSON.stringify(value) : String(value == null ? '' : value).replace(/\s+/g, ' ').trim();
  }
  function recheckChanges(games, results, opts) {
    const o = opts || {};
    const declined = o.declined || {};
    const byId = new Map((Array.isArray(games) ? games : []).filter((g) => g && g.id).map((g) => [g.id, g]));
    const norm = (v) => String(v == null ? '' : v).replace(/\s+/g, ' ').trim();
    const same = (a, b) => norm(a).toLowerCase() === norm(b).toLowerCase();
    const isWeb = (u) => /^https?:\/\//i.test(String(u || ''));
    const host = (u) => { try { return new URL(u).hostname.replace(/^(www|m)\./i, '').toLowerCase(); } catch (e) { return ''; } };
    const hostName = (u) => host(u).split('.')[0].split(/[-_]/).filter(Boolean).map((w) => w[0].toUpperCase() + w.slice(1)).join(' ');
    const out = [];
    const seen = new Set();
    (Array.isArray(results) ? results : []).forEach((r) => {
      const g = r && byId.get(r.id);
      const p = r && r.parsed;
      if (!g || !p || seen.has(g.id) || g.archived) return;
      // The page must still be the game's (the link wasn't changed since the pass).
      if (r.url && norm(r.url) !== norm(g.url)) return;
      if (typeof o.isKnown === 'function' && o.isKnown(g.url)) return;
      seen.add(g.id);
      const no = declined[g.id] || {};
      const changes = [];
      const add = (field, from, to) => {
        if (no[field] === recheckKey(to)) return;
        changes.push({ field, from, to });
      };
      // A name made up from the address or the generic fallback ("Daily Game") is no news.
      const name = norm(p.name);
      if (name && name.length <= 60 && !/^daily game$/i.test(name) && !same(name, g.name) && !same(name, hostName(r.finalUrl || g.url))) add('name', g.name || '', name);
      const desc = norm(p.description);
      if (desc && desc.length >= 12 && !same(desc, g.desc)) add('desc', g.desc || '', desc);
      const icon = String(p.faviconUrl || '').trim();
      if (isWeb(icon) && isWeb(g.icon) && icon !== g.icon && !/^https?:\/\/(www\.)?google\.com\/s2\/favicons/i.test(icon)) add('icon', g.icon, icon);
      if (p.timeFound) {
        const time = sanitizeResetTime(p.resetTime);
        const zone = p.zoneFound ? normalizeZone(p.resetZone) : normalizeZone(g.resetZone);
        if (time !== sanitizeResetTime(g.resetTime) || zone !== normalizeZone(g.resetZone)) {
          add('reset', { time: sanitizeResetTime(g.resetTime), zone: normalizeZone(g.resetZone) }, { time, zone });
        }
      }
      // Only over the placeholder: a format learned from results (1.8.3) or typed in knows better.
      if ((norm(p.targetFormat) || (p.metricType && Number(p.par) > 0)) && (!norm(g.maxTries) || /^daily puzzle$/i.test(norm(g.maxTries)))) {
        const par = Number(p.par);
        const fmt = norm(p.targetFormat)
          || (p.metricType === 'tries' ? `${par} guesses` : (p.metricType === 'time' ? `${par}s target` : `${par} pts`));
        if (fmt && !same(fmt, g.maxTries)) add('maxTries', g.maxTries || '', fmt);
      }
      if (p.category && typeof o.category === 'function' && (g.category || 'Custom Daily') === 'Custom Daily') {
        const cat = o.category(p.category);
        if (cat && cat !== 'Custom Daily') add('category', g.category || 'Custom Daily', cat);
      }
      const moved = String(r.finalUrl || '').trim();
      if (isWeb(moved) && host(moved) && host(g.url) && host(moved) !== host(g.url)) add('url', g.url, moved);
      if (changes.length) out.push({ id: g.id, name: g.name || hostName(g.url) || 'Game', changes });
    });
    return out;
  }

  /**
   * Days a game went much worse than usual: Map date -> { label, typical }. Uses the kind of score the
   * game mostly logs and needs five of them. Guesses: two or more above the median; times and counts:
   * at least 1.75x the median; points: half the median or less. At most the worst 15% of days.
   */
  function toughDays(rows) {
    const out = new Map();
    const vals = (Array.isArray(rows) ? rows : [])
      .filter((r) => r && r.outcome === 'Completed' && typeof r.date === 'string')
      .map((r) => ({ date: r.date, v: parseResultValue(r.score) }))
      .filter((x) => x.v);
    if (vals.length < 5) return out;
    const counts = new Map();
    vals.forEach((x) => counts.set(x.v.group, (counts.get(x.v.group) || 0) + 1));
    let group = null;
    counts.forEach((c, g) => { if (group === null || c > counts.get(group)) group = g; });
    const same = vals.filter((x) => x.v.group === group);
    if (same.length < 5) return out;
    const kind = same[0].v.kind;
    const med = median(same.map((x) => x.v.rank).sort((a, b) => a - b));
    const round1 = (x) => Math.round(x * 10) / 10;
    const typical = kind === 'guesses' ? `${round1(med)}/${same[0].v.max}`
      : kind === 'time' ? formatClock(med)
      : kind === 'count' ? `${round1(med)} ${same[0].v.unit}`
      : pointsLabel(round1(-med), same[0].v.unit);
    const tough = [];
    same.forEach((x) => {
      const r = x.v.rank;
      let severity = 0;
      if (kind === 'guesses') severity = r - med >= 2 ? (r - med) / x.v.max : 0;
      else if (kind === 'time') severity = med > 0 && r >= med * 1.75 && r - med >= 30000 ? r / med : 0;
      else if (kind === 'count') severity = med > 0 && r >= med * 1.75 && r - med >= 2 ? r / med : 0;
      else if (kind === 'points') severity = -med > 0 && x.v.value <= -med * 0.5 ? (-med) / Math.max(1, x.v.value) : 0;
      if (severity > 0) tough.push({ date: x.date, label: x.v.label, severity });
    });
    tough.sort((a, b) => b.severity - a.severity);
    tough.slice(0, Math.max(1, Math.floor(same.length * 0.15))).forEach((t) => out.set(t.date, { label: t.label, typical }));
    return out;
  }

  /**
   * The hub's games in the chosen order: 'custom' (as arranged), 'reset' (next reset first),
   * 'alpha' (A–Z) or 'least' (fewest days played first). Ties keep the custom order.
   */
  function sortGames(games, mode, opts) {
    const list = (Array.isArray(games) ? games : []).map((g, i) => ({ g, i }));
    const o = opts || {};
    const byName = (a, b) => String(a.g.name || '').localeCompare(String(b.g.name || ''), undefined, { sensitivity: 'base' });
    let cmp = null;
    if (mode === 'alpha') cmp = byName;
    else if (mode === 'reset' && typeof o.msUntilReset === 'function') cmp = (a, b) => o.msUntilReset(a.g) - o.msUntilReset(b.g);
    else if (mode === 'least' && typeof o.playCount === 'function') cmp = (a, b) => o.playCount(a.g) - o.playCount(b.g);
    if (!cmp) return list.map((x) => x.g);
    return list.sort((a, b) => cmp(a, b) || a.i - b.i).map((x) => x.g);
  }

  // Lines that are only emoji squares, circles, keycaps, hearts and stars (a game's grid).
  const GRID_ONLY_STRIP_RE = /[0-9#*]️?⃣|[⬛⬜⚫⚪✅❌⭐❤\u{1F7E0}-\u{1F7EB}\u{1F534}\u{1F535}\u{1F499}-\u{1F49C}\u{1F5A4}\u{1F90D}\u{1F90E}\u{1F31F}\u{1F7E5}]|[\s️‍]/gu;
  const GRID_ONLY_CELL_RE = /[0-9#*]️?⃣|[⬛⬜⚫⚪✅❌⭐❤\u{1F7E0}-\u{1F7EB}\u{1F534}\u{1F535}\u{1F499}-\u{1F49C}\u{1F5A4}\u{1F90D}\u{1F90E}\u{1F31F}]/gu;
  function isGridLine(line) {
    const l = String(line || '');
    if (!l.trim()) return false;
    const cells = l.match(GRID_ONLY_CELL_RE);
    return Boolean(cells && cells.length >= 2 && !l.replace(GRID_ONLY_STRIP_RE, ''));
  }

  /**
   * Today's results as one message: a heading ("Dailies · Fri 13 Nov — 5/7 solved"), then each game's
   * result (its name added when the text doesn't say it), then missed games, with a spacer line between them. entries:
   * [{ name, url, outcome: 'completed'|'missed', text }]. opts: { dateLabel, total, grids (default true) }.
   */
  function combinedShareText(entries, opts) {
    const o = opts || {};
    const list = (Array.isArray(entries) ? entries : []).filter((e) => e && (e.outcome === 'completed' || e.outcome === 'missed'));
    const solved = list.filter((e) => e.outcome === 'completed').length;
    const total = Number(o.total) > 0 ? Number(o.total) : list.length;
    const head = `Dailies${o.dateLabel ? ` · ${o.dateLabel}` : ''} — ${solved}/${total} solved`;
    const blocks = [];
    const missedNames = [];
    list.forEach((e) => {
      let text = String(e.text || '').replace(/\r\n/g, '\n').trim();
      if (!text) {
        if (e.outcome === 'missed') missedNames.push(e.name);
        else blocks.push(`✓ ${e.name}`);
        return;
      }
      let lines = text.split('\n');
      if (o.grids === false) {
        const kept = lines.filter((l) => !isGridLine(l));
        lines = (kept.some((l) => l.trim()) ? kept : lines.slice(0, 1));
        // Collapse the blank lines a removed grid leaves behind.
        lines = lines.filter((l, i) => l.trim() || (i > 0 && lines[i - 1].trim() && i < lines.length - 1));
      }
      text = lines.join('\n').trim();
      if (!mentionsGame(text, { name: e.name, url: e.url || '' })) text = `${e.outcome === 'missed' ? '✗' : '✓'} ${e.name}\n${text}`;
      blocks.push(text);
    });
    if (missedNames.length) blocks.push(`✗ ${missedNames.join(', ')}`);
    // 1.8.5: a spacer line between games, so where one result ends and the next starts is clear.
    return head + '\n\n' + blocks.join(`\n\n${RESULTS_SPACER}\n\n`);
  }
  const RESULTS_SPACER = '───────────';

  // ---- Colour themes: Material-style tones from one seed colour (OKLCH, kept inside sRGB) ----
  const THEME_PRESETS = {
    ocean: '#1E88C8',
    forest: '#2E8B57',
    sunset: '#F06A3C',
    rose: '#D9467A',
    teal: '#0F9D9A',
    amber: '#D98A16',
    crimson: '#C82838',
    cobalt: '#3B5BDB',
    sage: '#5F8D6B',
    plum: '#9C36B5',
    slate: '#5C728A'
  };

  /** Deterministic PRNG (mulberry32) for shuffling pre-made themes per epoch without repeats. */
  function shuffleForEpoch(len, epoch) {
    let a = ((epoch * 0x9E3779B1) ^ 0x85EBCA6B) >>> 0;
    const next = () => {
      a = (a + 0x6D2B79F5) >>> 0;
      let t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
    const arr = Array.from({ length: len }, (_, i) => i);
    for (let i = len - 1; i > 0; i--) {
      const j = Math.floor(next() * (i + 1));
      const tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
    }
    return arr;
  }

  function pickDailyTheme(dateKey, candidates) {
    const list = Array.isArray(candidates) ? candidates.filter(Boolean) : [];
    if (list.length === 0) return 'iris';
    if (list.length === 1) return list[0];
    const day = cycleKeyToDay(dateKey);
    if (!Number.isFinite(day)) return list[0];
    const n = list.length;
    const epoch = Math.floor(day / n);
    const pos = ((day % n) + n) % n;
    const bag = shuffleForEpoch(n, epoch);
    const prevBag = shuffleForEpoch(n, epoch - 1);
    if (bag[0] === prevBag[n - 1]) {
      const tmp = bag[0]; bag[0] = bag[1]; bag[1] = tmp;
    }
    return list[bag[pos]];
  }

  function hexToRgb(hex) {
    const m = /^#?([0-9a-f]{6})$/i.exec(String(hex || '').trim());
    if (!m) return null;
    const v = parseInt(m[1], 16);
    return [(v >> 16) & 255, (v >> 8) & 255, v & 255];
  }
  const toLinear = (c) => { const x = c / 255; return x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
  const fromLinear = (c) => (c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055);

  function hexToOklch(hex) {
    const rgb = hexToRgb(hex);
    if (!rgb) return null;
    const [r, g, b] = rgb.map(toLinear);
    const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
    const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
    const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
    const L = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s;
    const A = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s;
    const B = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;
    return { L, C: Math.hypot(A, B), H: ((Math.atan2(B, A) * 180) / Math.PI + 360) % 360 };
  }

  function oklchToLinear(L, C, H) {
    const a = C * Math.cos((H * Math.PI) / 180);
    const b = C * Math.sin((H * Math.PI) / 180);
    const l = Math.pow(L + 0.3963377774 * a + 0.2158037573 * b, 3);
    const m = Math.pow(L - 0.1055613458 * a - 0.0638541728 * b, 3);
    const s = Math.pow(L - 0.0894841775 * a - 1.2914855480 * b, 3);
    return [
      4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
      -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
      -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
    ];
  }

  /** "#RRGGBB" for an OKLCH colour, lowering its chroma until it fits in sRGB. */
  function oklchToHex(L, C, H) {
    for (let c = C; c >= 0; c -= 0.004) {
      const lin = oklchToLinear(L, c, H);
      if (lin.every((v) => v >= -0.0005 && v <= 1.0005)) {
        return '#' + lin.map((v) => Math.round(Math.min(1, Math.max(0, fromLinear(Math.min(1, Math.max(0, v))))) * 255)
          .toString(16).padStart(2, '0')).join('').toUpperCase();
      }
    }
    const g = Math.round(Math.min(1, Math.max(0, fromLinear(Math.pow(L, 3)))) * 255).toString(16).padStart(2, '0').toUpperCase();
    return `#${g}${g}${g}`;
  }

  /**
   * The hub's colour roles (--md-*) for a seed colour, in the dark or light theme. Null for a colour
   * that isn't "#RRGGBB". Grey seeds give a neutral theme.
   */
  function themeTokens(seedHex, light) {
    const seed = hexToOklch(seedHex);
    if (!seed) return null;
    const h = seed.H;
    const c = Math.min(seed.C, 0.2);
    const t = (L, C, H) => oklchToHex(L, Math.min(C, c), H === undefined ? h : H);
    const tert = (h + 60) % 360;
    const rgba = (hex, a) => { const v = hexToRgb(hex); return `rgba(${v[0]}, ${v[1]}, ${v[2]}, ${a})`; };
    if (light) {
      const primary = t(0.5, 0.16);
      return {
        '--md-surface': t(0.985, 0.008), '--md-container-low': t(0.966, 0.011), '--md-container': t(0.95, 0.015),
        '--md-container-high': t(0.926, 0.017), '--md-container-highest': t(0.902, 0.019),
        '--md-primary': primary, '--md-on-primary': '#FFFFFF',
        '--md-primary-container': t(0.91, 0.06), '--md-on-primary-container': t(0.27, 0.1),
        '--md-secondary-container': t(0.92, 0.035), '--md-tertiary': t(0.5, 0.1, tert), '--md-tertiary-container': t(0.91, 0.05, tert),
        '--md-outline': rgba(t(0.4, 0.02), 0.16), '--md-glow': rgba(primary, 0.1)
      };
    }
    const primary = t(0.83, 0.11);
    return {
      '--md-surface': t(0.165, 0.012), '--md-container-low': t(0.195, 0.014), '--md-container': t(0.225, 0.017),
      '--md-container-high': t(0.265, 0.02), '--md-container-highest': t(0.305, 0.022),
      '--md-primary': primary, '--md-on-primary': t(0.32, 0.12),
      '--md-primary-container': t(0.4, 0.11), '--md-on-primary-container': t(0.93, 0.05),
      '--md-secondary-container': t(0.37, 0.035), '--md-tertiary': t(0.82, 0.09, tert), '--md-tertiary-container': t(0.38, 0.07, tert),
      '--md-outline': rgba(primary, 0.16), '--md-glow': rgba(primary, 0.14)
    };
  }

  // ---- The same game behind a different link (1.8.13, tightened) ----
  const ENTRY_SEG_RE = /^(?:game|play|daily|today|app|start|home|main|welcome|menu|index|play-now|daily-game|daily-puzzle|todays-puzzle|todays-game)$/;
  const INDEX_FILE_RE = /\/index\.(?:html?|php|asp|aspx|jsp)$/i;
  const DEFAULT_EN_PREFIX_RE = /^\/en(?:-(?:us|gb))?(?=\/|$)/;

  /** A link as { host, path }: host without "www." / "m.", path without index.html or a trailing "/". */
  function gameLinkKey(rawUrl) {
    try {
      let c = String(rawUrl || '').trim();
      if (!c) return null;
      if (!/^https?:\/\//i.test(c)) c = 'https://' + c;
      const u = new URL(c);
      if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
      const host = u.hostname.toLowerCase().replace(/^(www|m)\./, '');
      if (!host.includes('.')) return null;
      const path = decodeURIComponent(u.pathname).toLowerCase().replace(INDEX_FILE_RE, '').replace(/\/+$/, '');
      return { host, path };
    } catch (e) {
      return null;
    }
  }

  /**
   * Canonical link { host, path } after stripping default `/en` locale prefixes and trailing entry/menu
   * segments (`/game`, `/play`, `/daily`, `/today`, etc.) so `globle-game.com` and `globle-game.com/game`
   * resolve to the same key while sibling paths (`nytimes.com/games/wordle` vs `/games/connections`) stay distinct.
   */
  function canonicalGameLink(rawUrl) {
    const k = gameLinkKey(rawUrl);
    if (!k) return null;
    const p = k.path.replace(DEFAULT_EN_PREFIX_RE, '');
    const segs = p.split('/').filter(Boolean);
    while (segs.length > 0 && ENTRY_SEG_RE.test(segs[segs.length - 1])) {
      segs.pop();
    }
    return { host: k.host, path: segs.length ? '/' + segs.join('/') : '' };
  }

  function canonicalUrlKey(rawUrl) {
    const c = canonicalGameLink(rawUrl);
    return c ? `${c.host}${c.path}` : '';
  }

  const sameLink = (a, b) => Boolean(a && b && a.host === b.host && a.path === b.path);
  /** One link is a page of the other (site.com and site.com/section, /games and /games/wordle). */
  const nestedLink = (a, b) => Boolean(a && b && a.host === b.host && a.path !== b.path
    && (a.path === '' || b.path === '' || b.path.startsWith(a.path + '/') || a.path.startsWith(b.path + '/')));
  /** Same site or one's sub-site (play.site.com, site.com). */
  const relatedHost = (a, b) => Boolean(a && b && (a.host === b.host || a.host.endsWith('.' + b.host) || b.host.endsWith('.' + a.host)));
  const GENERIC_NAME = /^(daily|game|games|puzzle|puzzles|play|home|menu|today|daily ?game|daily ?puzzle|word ?game|untitled|sudoku|crossword|crosswords|jigsaw|trivia|quiz|solitaire|mahjong|minesweeper|nonogram|kakuro|wordsearch|chess|anagram|bingo)$/;
  const MODE_QUALIFIER_RE = /\b(quote|classic|artwork|keywords|splash|ability|emoji|episode|oneframe|capitals|mini|maxi|micro|deluxe|sequence|rescue|chill|extreme|instant|speed|bilingual|sports|junior|kids|pro|plus)\b/i;

  /** A name reduced to what identifies the game: "The NYT Wordle - Play Online" -> "wordle", keeping distinct mode suffixes ("LoLdle - Quote" -> "loldlequote"). */
  function gameNameKey(name) {
    let raw = String(name || '').normalize('NFKD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
    const parts = raw.split(/\s[-–—|:·]\s|\s[|·]|[|·]\s/);
    let n = parts[0] || '';
    if (parts.length > 1) {
      const qual = parts[1].trim();
      if (qual.split(/\s+/).length <= 3 && MODE_QUALIFIER_RE.test(qual)) {
        n += ' ' + qual;
      }
    }
    n = n.replace(/\b(the|nyt|daily|play|online|free|unlimited|game|puzzle|official)\b/g, ' ');
    n = n.replace(/[^a-z0-9]+/g, '');
    return n.length >= 4 && !GENERIC_NAME.test(n) ? n : '';
  }

  function rawGameUrls(g) {
    return [g && g.url].concat(Array.isArray(g && g.altUrls) ? g.altUrls : []).filter((u) => typeof u === 'string' && u.trim());
  }
  /** Every link a game is known by: its own, and any it was reached from or found on. */
  function gameLinks(g) {
    return rawGameUrls(g).map(gameLinkKey).filter(Boolean);
  }
  function canonicalGameLinks(g) {
    return rawGameUrls(g).map(canonicalGameLink).filter(Boolean);
  }
  function urlMentionsBrand(urls, brandKey) {
    if (!brandKey || brandKey.length < 4) return false;
    return urls.some((k) => k && (`${k.host}${k.path}`).replace(/[^a-z0-9]+/g, '').includes(brandKey));
  }

  /**
   * The game in `games` that a new link refers to, or null.
   * `cand`: { url, typedUrl, finalUrl, name, embeds: [frame urls on its page] }.
   * `opts`: { sameKnownGame?: (urlA, urlB) => boolean, includeArchived?: boolean }.
   * `sure: true` matches are definite duplicates (blocked automatically across all entry points);
   * `sure: false` matches are ambiguous hints (e.g. a portal root page before any title is known).
   */
  function findSameGame(cand, games, opts) {
    const o = opts || {};
    const list = (Array.isArray(games) ? games : []).filter((g) => g && g.url && (o.includeArchived || !g.archived));
    const rawMine = [cand && cand.url, cand && cand.typedUrl, cand && cand.finalUrl].filter((u) => typeof u === 'string' && u.trim());
    const own = gameLinkKey(cand && cand.url);
    const typed = gameLinkKey(cand && cand.typedUrl);
    const final = gameLinkKey(cand && cand.finalUrl);
    const mine = [own, typed, final].filter(Boolean);
    if (!mine.length) return null;

    const ownCanon = canonicalGameLink(cand && cand.url);
    const typedCanon = canonicalGameLink(cand && cand.typedUrl);
    const finalCanon = canonicalGameLink(cand && cand.finalUrl);
    const mineCanon = [ownCanon, typedCanon, finalCanon].filter(Boolean);
    const embedsCanon = (Array.isArray(cand && cand.embeds) ? cand.embeds : []).map(canonicalGameLink).filter(Boolean);
    const embeds = (Array.isArray(cand && cand.embeds) ? cand.embeds : []).map(gameLinkKey).filter(Boolean);
    const pick = (test) => list.find(test);

    // 1. Exact or entry-canonical URL match (e.g. globle-game.com vs globle-game.com/game, krillion.io vs krillion.io/play).
    let g = pick((x) => {
      const xCanon = canonicalGameLink(x.url);
      return sameLink(xCanon, ownCanon) || sameLink(xCanon, typedCanon);
    });
    if (g) return { game: g, reason: 'same', sure: true };

    g = pick((x) => canonicalGameLinks(x).some((k) => mineCanon.some((m) => sameLink(k, m))));
    if (g) {
      const viaRedirect = finalCanon && !sameLink(finalCanon, ownCanon) && canonicalGameLinks(g).some((k) => sameLink(k, finalCanon));
      return { game: g, reason: viaRedirect ? 'redirect' : 'same', sure: true };
    }

    // 2. Built-in catalogue alias match (e.g. octordle.com vs britannica.com/games/octordle, oec.world vs games.oec.world).
    if (typeof o.sameKnownGame === 'function') {
      g = pick((x) => rawGameUrls(x).some((xu) => rawMine.some((mu) => o.sameKnownGame(xu, mu))));
      if (g) return { game: g, reason: 'same', sure: true };
    }

    // 3. Wrapper page embedding an existing game in an iframe (or vice versa): definite duplicate.
    g = pick((x) => canonicalGameLinks(x).some((k) => embedsCanon.some((e) => sameLink(k, e)))
      || (Array.isArray(x.embeds) && x.embeds.map(canonicalGameLink).some((e) => mineCanon.some((m) => sameLink(e, m)))));
    if (g) return { game: g, reason: 'embedded', sure: true };

    // 4. Same/related site with the same distinct game name, or a mirror URL carrying the game's brand name.
    const nk = gameNameKey(cand && cand.name);
    if (nk) {
      g = pick((x) => gameNameKey(x.name) === nk && gameLinks(x).some((k) => mine.some((m) => relatedHost(k, m))));
      if (g) return { game: g, reason: 'same', sure: true };

      g = pick((x) => gameNameKey(x.name) === nk && (urlMentionsBrand(gameLinks(x), nk) || urlMentionsBrand(mine, nk)));
      if (g) return { game: g, reason: 'name', sure: true, relatedSite: false };
    }

    // 5. Soft warnings for ambiguous cases (e.g. iframe parent-site or unscraped subpage where titles differ/unknown).
    g = pick((x) => gameLinks(x).some((k) => embeds.some((e) => nestedLink(k, e)))
      || (Array.isArray(x.embeds) && x.embeds.map(gameLinkKey).some((e) => mine.some((m) => nestedLink(e, m)))));
    if (g) return { game: g, reason: 'embedded', sure: false };

    g = pick((x) => {
      if (nk && gameNameKey(x.name) && gameNameKey(x.name) !== nk) return false;
      return gameLinks(x).some((k) => mine.some((m) => nestedLink(k, m)));
    });
    if (g) return { game: g, reason: 'menu', sure: false };

    if (nk) {
      g = pick((x) => gameNameKey(x.name) === nk);
      if (g) return { game: g, reason: 'name', sure: false, relatedSite: false };
    }
    return null;
  }

  // ---- Duplicate detection from a newly added game's result format & style ----
  const RESULT_URL_RE = /https?:\/\/[^\s"'<>)]+|\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+(?:com|org|net|io|app|game|games|earth|wtf|me|world|day|fr|uk|de|ca|au|co|gg|xyz|tv|us|info|fun)(?:\/[^\s"'<>)]*)?/gi;
  const RESULT_HASHTAG_RE = /(?:^|\s)#([a-z][a-z0-9_]{2,30})\b/gi;
  const RESULT_DATE_TEXT_RE = /\b(?:(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?\s+\d{1,2}(?:st|nd|rd|th)?(?:,?\s+\d{4})?|\d{1,2}\s+(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?(?:,?\s+\d{4})?|\d{4}-\d{2}-\d{2}|\d{1,2}[\/.]\d{1,2}[\/.]\d{2,4})\b/gi;
  const RESULT_QUOTE_RE = /(?:"[^"\n]{1,60}"|“[^”\n]{1,60}”|'[^'\n]{2,60}'|‘[^’\n]{2,60}’)/g;
  const RESULT_TILE_RE = /[0-9#*]️?⃣|[⬛⬜⚫⚪✅❌⭐❤💡🎥🎬🎞️🔊🎵🔇🎧⬆️⬇️⬅️➡️↗️↘️↙️↖️\u{1F7E0}-\u{1F7EB}\u{1F534}\u{1F535}\u{1F499}-\u{1F49C}\u{1F5A4}\u{1F90D}\u{1F90E}\u{1F31F}]/gu;
  const RESULT_TRIVIAL_SKELETON_RE = /^(?:(?:puzzle|day|daily|game|score|solved|result|#|<n>|<frac>|<time>|<date>|[:\-–—|·()/\s])+)$/i;

  function classifyTilePalette(sample) {
    if (/[🎥🎬🎞️🔊🎵🔇🎧]/u.test(sample)) return 'media';
    if (/[⬆️⬇️⬅️➡️↗️↘️↙️↖️]/u.test(sample)) return 'squares-dir';
    if (/[🟦🟪]/u.test(sample) && !/[🔵🟣]/u.test(sample)) return 'squares-rainbow';
    if (/[🔴🟠🟡🟢🔵🟣⚫⚪💡]/u.test(sample) && !/[🟩🟨⬛⬜]/u.test(sample)) return 'circles';
    if (/[🟥🟧]/u.test(sample)) return 'heat';
    if (/[0-9#*]️?⃣/u.test(sample)) return 'keycaps';
    return 'squares-bwg';
  }

  function isTileRowLine(line) {
    if (isGridLine(line)) return true;
    const cells = String(line || '').match(RESULT_TILE_RE);
    if (!cells || cells.length < 2) return false;
    // Hybrid rows like `🟨🟧🟥🟩 = 4` or `🟩🟩🟩🟩🟨⬅️ 85%`
    const rest = String(line || '').replace(RESULT_TILE_RE, '').replace(/[\s️‍=0-9+%().,/-]/g, '');
    return rest.length === 0;
  }

  /**
   * Extracts a structural & visual fingerprint from a game's share text:
   * `{ raw, urls, canonUrls, hashtags, brandKey, modeKey, scoreGroup, gridStyle, puzzleNum, puzzleEpoch, skeleton, distinctive }`
   */
  function shareResultSignature(text, cycleDateKey) {
    const raw = String(text || '').replace(/\r\n/g, '\n').trim();
    if (raw.length < 6 || /^auto-marked missed$/i.test(raw)) return null;

    const urls = [];
    let um;
    RESULT_URL_RE.lastIndex = 0;
    while ((um = RESULT_URL_RE.exec(raw)) !== null) {
      const cleaned = um[0].replace(/[.,;:!?)'"\]]+$/, '');
      if (cleaned) urls.push(cleaned);
    }
    const canonUrls = urls.map(canonicalGameLink).filter(Boolean);

    const hashtags = [];
    let hm;
    RESULT_HASHTAG_RE.lastIndex = 0;
    while ((hm = RESULT_HASHTAG_RE.exec(raw)) !== null) {
      const hk = gameNameKey(hm[1]);
      if (hk && !hashtags.includes(hk)) hashtags.push(hk);
    }

    const pv = parseResultValue(raw);
    const sc = parseScore(raw);
    const lines = raw.split('\n').map((l) => l.trim()).filter(Boolean);

    const tileRows = [];
    const textLines = [];
    lines.forEach((l) => {
      if (isTileRowLine(l)) {
        const cells = l.match(RESULT_TILE_RE) || [];
        tileRows.push({ line: l, pure: isGridLine(l), count: cells.length });
      } else {
        textLines.push(l);
      }
    });

    let gridStyle = '';
    if (tileRows.length > 0) {
      const allTileSample = tileRows.map((r) => r.line).join('');
      const palette = classifyTilePalette(allTileSample);
      const pureCounts = tileRows.filter((r) => r.pure).map((r) => r.count);
      const uniformWidth = pureCounts.length >= 2 && pureCounts.every((c) => c === pureCounts[0])
        ? String(pureCounts[0])
        : (pureCounts.length === 1 && tileRows.length === 1 && pureCounts[0] >= 4 ? String(pureCounts[0]) : 'var');
      gridStyle = `${palette}:${uniformWidth}`;
    }

    const scoreGroup = (pv && pv.group)
      || (sc && Number(sc.max) > 0 ? `f:${sc.max}` : '')
      || (tileRows.length >= 2 ? 'grid-only' : '');

    const puzzleNum = puzzleNumber(raw);
    const puzzleEpoch = (puzzleNum !== null && DATE_KEY_RE.test(String(cycleDateKey || '')))
      ? (puzzleNum - cycleKeyToDay(cycleDateKey))
      : null;

    if (!scoreGroup && !gridStyle && !canonUrls.length && puzzleNum === null) return null;

    // Mode qualifier across text lines (so `#LoLdle (Classic)` and `#LoLdle (Quote)` never merge).
    const textJoined = textLines.join(' ');
    const modeMatch = textJoined.match(MODE_QUALIFIER_RE);
    const modeKey = modeMatch ? modeMatch[1].toLowerCase() : '';

    // Header brand from the first 1–2 text lines after removing dates, numbers, scores, and URLs.
    let brandKey = '';
    for (let i = 0; i < Math.min(2, textLines.length) && !brandKey; i++) {
      let h = textLines[i]
        .replace(RESULT_URL_RE, ' ')
        .replace(RESULT_QUOTE_RE, ' ')
        .replace(RESULT_DATE_TEXT_RE, ' ')
        .replace(/\b(?:puzzle|day|no\.?|streak|avg\.?\s*guesses?|guesses?|tries|moves|swaps|score|points|pts|time|mistakes|hints?)\b[:\s]*[0-9.,/%+-]*/gi, ' ')
        .replace(/(?:[0-9xX]+\s*\/\s*[0-9,]+|#\s*[0-9,]+|\([0-9.,%+-]+\)|[+-][0-9]+|\b[0-9]{1,2}:[0-9]{2}\b|\b[0-9][0-9,.]*\b)/g, ' ')
        .trim();
      if (modeKey && !new RegExp(`\\b${modeKey}\\b`, 'i').test(h)) h = `${h} ${modeKey}`;
      brandKey = gameNameKey(h);
    }
    if (!brandKey && hashtags.length === 1) {
      brandKey = modeKey && !hashtags[0].includes(modeKey) ? `${hashtags[0]}${modeKey}` : hashtags[0];
    }

    // Build normalized structural template skeleton across the whole share snippet.
    const skelParts = [];
    let inGrid = false;
    lines.forEach((l) => {
      if (isTileRowLine(l)) {
        if (!inGrid) {
          skelParts.push(`<grid:${gridStyle || 'tiles'}>`);
          inGrid = true;
        }
        return;
      }
      inGrid = false;
      const normLine = l
        .toLowerCase()
        .replace(RESULT_URL_RE, '<url>')
        .replace(RESULT_QUOTE_RE, '<quote>')
        .replace(RESULT_DATE_TEXT_RE, '<date>')
        .replace(/\b[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?\b/g, '<time>')
        .replace(/\b(?:[0-9]{1,3}(?:,[0-9]{3})*|[xX])\s*\/\s*[0-9]{1,3}(?:,[0-9]{3})*\b/g, '<frac>')
        .replace(/[+-]?\b[0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]+)?%?|[+-]?\b[0-9]+(?:\.[0-9]+)?%?/g, '<n>')
        .replace(/\s+/g, ' ')
        .trim();
      if (normLine) skelParts.push(normLine);
    });
    const skeleton = skelParts.join(' | ');
    const textSkeletonOnly = skelParts.filter((p) => !p.startsWith('<grid:')).join(' ');
    const distinctive = Boolean(textSkeletonOnly && !RESULT_TRIVIAL_SKELETON_RE.test(textSkeletonOnly) && textSkeletonOnly.length >= 6);

    return {
      raw,
      urls,
      canonUrls,
      hashtags,
      brandKey,
      modeKey,
      scoreGroup,
      gridStyle,
      puzzleNum,
      puzzleEpoch,
      skeleton,
      distinctive
    };
  }

  function sameResultFormatAndStyle(a, b) {
    if (!a || !b) return false;
    if (a.modeKey && b.modeKey && a.modeKey !== b.modeKey) return false;
    if (a.puzzleEpoch !== null && b.puzzleEpoch !== null && Math.abs(a.puzzleEpoch - b.puzzleEpoch) > 2) return false;
    if (a.gridStyle && b.gridStyle && a.gridStyle !== b.gridStyle) return false;
    if (a.scoreGroup && b.scoreGroup && a.scoreGroup !== b.scoreGroup) {
      // Allow e.g. solved `45 guesses` vs failed message only if one has no numeric group; otherwise groups must match.
      return false;
    }
    return Boolean(a.scoreGroup || a.gridStyle);
  }

  function compatibleMaxTries(maxTries, sig) {
    const m = String(maxTries || '').trim().toLowerCase();
    if (!m || m === 'daily puzzle') return true;
    const numMatch = m.match(/^(\d+)\s+(?:guesses|tries|equations|frames|shots|chances|attempts)\b/);
    if (numMatch && sig.scoreGroup && sig.scoreGroup.startsWith('f:')) {
      return sig.scoreGroup === `f:${numMatch[1]}`;
    }
    if (m === 'fastest time' && sig.scoreGroup && sig.scoreGroup.startsWith('f:')) return false;
    return true;
  }

  /**
   * Checks whether a newly added game's logged share result (`scoreText`) matches the format and
   * style of another game already in `games` (`historyLogs`).
   * Returns `{ game, reason }` (`'result-url' | 'result-brand' | 'result-style' | 'same-result'`) or `null`.
   */
  function findDuplicateByResult(game, scoreText, cycleDateKey, games, historyLogs, opts) {
    const o = opts || {};
    if (!game || !game.id) return null;
    const sig = shareResultSignature(scoreText, cycleDateKey);
    if (!sig) return null;

    const logs = Array.isArray(historyLogs) ? historyLogs : [];
    const maxScoredLogs = Number.isFinite(o.maxScoredLogs) ? o.maxScoredLogs : 3;
    const ownScoredCount = logs.filter((l) => l && l.gameId === game.id && String(l.score || '').trim().length >= 6).length;
    if (ownScoredCount > maxScoredLogs) return null;

    const excluded = new Set(Array.isArray(game.notDuplicateOf) ? game.notDuplicateOf : []);
    const candidates = (Array.isArray(games) ? games : []).filter((g) => {
      if (!g || !g.id || g.id === game.id) return false;
      if (excluded.has(g.id)) return false;
      if (Array.isArray(g.notDuplicateOf) && g.notDuplicateOf.includes(game.id)) return false;
      return true;
    });
    if (!candidates.length) return null;

    // Prefer matching against more established games first.
    const ordered = candidates.slice().sort((a, b) => (Number(b.totalPlayed) || 0) - (Number(a.totalPlayed) || 0));

    for (const other of ordered) {
      const otherNameKey = gameNameKey(other.name);
      const otherModeMatch = String(other.name || '').match(MODE_QUALIFIER_RE);
      const otherMode = otherModeMatch ? otherModeMatch[1].toLowerCase() : '';
      if (sig.modeKey && otherMode && sig.modeKey !== otherMode) continue;

      const otherRows = logs
        .filter((l) => l && (l.gameId ? l.gameId === other.id : l.gameName === other.name) && String(l.score || '').trim())
        .slice(0, 6);
      const otherSigs = otherRows.map((r) => shareResultSignature(r.score, r.date)).filter(Boolean);

      // 1. Embedded URL inside the result matches `other` or one of `other`'s logged results.
      if (sig.canonUrls.length) {
        const otherCanon = canonicalGameLinks(other);
        const urlMatchesGame = otherCanon.some((ok) => sig.canonUrls.some(( cu ) => sameLink(ok, cu)))
          || (typeof o.sameKnownGame === 'function' && rawGameUrls(other).some((ou) => sig.urls.some((su) => o.sameKnownGame(ou, su))));
        const urlMatchesLogs = otherSigs.some((os) => os.canonUrls.some((oc) => sig.canonUrls.some((cu) => sameLink(oc, cu))));
        if (urlMatchesGame || urlMatchesLogs) return { game: other, reason: 'result-url' };
      }

      // 2. Exact non-trivial result snippet already logged on `other`.
      if (sig.raw.length >= 16 && otherSigs.some((os) => os.raw === sig.raw && (!sig.modeKey || !os.modeKey || sig.modeKey === os.modeKey))) {
        return { game: other, reason: 'same-result' };
      }

      // 3. Header brand / hashtag in the result matches `other`'s logged results (with matching style) or `other`'s game card.
      if (sig.brandKey) {
        const brandInOtherLogs = otherSigs.some((os) =>
          (os.brandKey === sig.brandKey || os.hashtags.includes(sig.brandKey)) && sameResultFormatAndStyle(sig, os)
        );
        if (brandInOtherLogs) return { game: other, reason: 'result-brand' };

        if (otherNameKey && sig.brandKey === otherNameKey && compatibleMaxTries(other.maxTries, sig)) {
          const epochConflict = otherSigs.some((os) => sig.puzzleEpoch !== null && os.puzzleEpoch !== null && Math.abs(sig.puzzleEpoch - os.puzzleEpoch) > 2);
          const styleConflict = otherSigs.length > 0 && otherSigs.every((os) => !sameResultFormatAndStyle(sig, os));
          if (!epochConflict && !styleConflict) return { game: other, reason: 'result-brand' };
        }
      }

      // 4. Format + visual style + structural template skeleton match against `other`'s logged results.
      const styleHit = otherSigs.some((os) => {
        if (sig.brandKey && os.brandKey && sig.brandKey !== os.brandKey) return false;
        if (otherNameKey && sig.brandKey && sig.brandKey !== otherNameKey) return false;
        if (!sameResultFormatAndStyle(sig, os)) return false;
        if (!sig.skeleton || sig.skeleton !== os.skeleton) return false;
        // Either a distinctive template (beyond bare "Puzzle #N 3/6") OR matching daily puzzle-number epoch (±1 day).
        if (sig.distinctive) return true;
        return sig.puzzleEpoch !== null && os.puzzleEpoch !== null && Math.abs(sig.puzzleEpoch - os.puzzleEpoch) <= 1;
      });
      if (styleHit) return { game: other, reason: 'result-style' };
    }
    return null;
  }

  /**
   * True when a non-placeholder result snippet is already recorded for `game` on `cycleKey`.
   */
  function hasLoggedResultForCycle(game, cycleKey, historyLogs) {
    if (!game || !cycleKey) return false;
    const isPlaceholder = (s) => /^auto-marked missed$/i.test(s);
    const logs = Array.isArray(historyLogs) ? historyLogs : [];
    const row = logs.find((l) => l && l.date === cycleKey && (l.gameId ? l.gameId === game.id : l.gameName === game.name));
    const rowScore = row && typeof row.score === 'string' ? row.score.trim() : '';
    if (rowScore && !isPlaceholder(rowScore)) return true;
    if (game.lastCycleKey === cycleKey && game.status === 'completed') {
      const todayScore = typeof game.todayScore === 'string' ? game.todayScore.trim() : '';
      if (todayScore && !isPlaceholder(todayScore)) return true;
    }
    return false;
  }

  /**
   * True when `game` is marked completed for `cycleKey` AND already has a logged result recorded.
   */
  function isCompletedWithLoggedResult(game, cycleKey, historyLogs) {
    if (!game || !cycleKey) return false;
    const logs = Array.isArray(historyLogs) ? historyLogs : [];
    const row = logs.find((l) => l && l.date === cycleKey && (l.gameId ? l.gameId === game.id : l.gameName === game.name));
    const completed = (game.dayHistory && game.dayHistory[cycleKey] === 'completed')
      || (game.lastCycleKey === cycleKey && game.status === 'completed')
      || Boolean(row && row.outcome === 'Completed');
    return Boolean(completed && hasLoggedResultForCycle(game, cycleKey, logs));
  }

  return {
    gameLinkKey,
    canonicalGameLink,
    canonicalUrlKey,
    gameNameKey,
    findSameGame,
    shareResultSignature,
    findDuplicateByResult,
    hasLoggedResultForCycle,
    isCompletedWithLoggedResult,
    extractResultText,
    weekBounds,
    periodRecap,
    recapChange,
    toughDays,
    learnScoreFormat,
    isGenericScoreFormat,
    recheckChanges,
    recheckKey,
    sortGames,
    isGridLine,
    combinedShareText,
    THEME_PRESETS,
    pickDailyTheme,
    themeTokens,
    hexToOklch,
    oklchToHex,
    scoreStats,
    dominantScores,
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
    TIER1_KEYS,
    ALL_TIER_KEYS,
    SECRET_ACHIEVEMENTS,
    SECRET_KEYS,
    secretMetrics,
    computeSecrets,
    migrateAchievementKeys,
    achievementMetrics,
    earnedAchievementKeys,
    isFailedShareResult,
    looksLikeShareResult,
    isStrictShareResult,
    mentionsGame,
    mentionsGameExplicitly,
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
    recomputeLogStreaks,
    sortHistoryLogs,
    indexHistoryLogs
  };
});
