/*
 * Dailies — pure hub UI-glue helpers (no DOM access).
 *
 * Loaded by index.html right after hub-core.js, and require()-able from Node so UI-glue
 * helpers (sorting, game normalization, restore/undo state transitions, log deduplication,
 * streak-as-of calculation) are unit tested directly (see app/src/test/js/hub-ui-helpers.test.js).
 */
(function (root, factory) {
  const core = (typeof module === 'object' && module.exports) ? require('./hub-core.js') : root.HubCore;
  const api = factory(core);
  if (typeof module === 'object' && module.exports) {
    module.exports = api;
  } else {
    root.HubUiHelpers = api;
  }
})(typeof self !== 'undefined' ? self : this, function (HubCore) {
  'use strict';

  const HUB_SORTS = ['custom', 'reset', 'alpha', 'least'];
  const REMINDER_MINUTES = [0, 30, 60, 120, 180, 360];
  const defaultNextResetCache = new Map();

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

  function normalizeReminderMinutes(v) {
    const n = Number(v);
    return v !== undefined && v !== null && v !== '' && REMINDER_MINUTES.includes(n) ? n : -1;
  }

  /** Drops sentence punctuation stuck to a link in shared text ("…/connections)." -> "…/connections"). */
  function trimUrlPunctuation(u) {
    let out = String(u || '');
    for (;;) {
      const last = out.slice(-1);
      if (/[.,;:!?'"\]}>]/.test(last)) { out = out.slice(0, -1); continue; }
      // A closing bracket is part of the link only if it closes one opened inside it (Wikipedia-style).
      if (last === ')' && (out.match(/\(/g) || []).length < (out.match(/\)/g) || []).length) { out = out.slice(0, -1); continue; }
      return out;
    }
  }

  function extractUrlCandidateFromText(raw) {
    const s = String(raw || '').trim();
    if (!s) return '';
    const httpMatch = s.match(/https?:\/\/[^\s"'<>]+/i);
    if (httpMatch) return trimUrlPunctuation(httpMatch[0]);
    const domainMatch = s.match(/\b([a-z0-9-]+\.)+[a-z]{2,}(\/[^\s"'<>]*)?/i);
    if (domainMatch) return trimUrlPunctuation(domainMatch[0]);
    return s;
  }

  function cleanGameUrl(rawUrl) {
    let c = extractUrlCandidateFromText(rawUrl);
    if (!c) return '';
    if (!/^https?:\/\//i.test(c)) c = 'https://' + c;
    try {
      const u = new URL(c);
      if (u.protocol !== 'http:' && u.protocol !== 'https:') return '';
      if (!u.hostname || !u.hostname.includes('.')) return '';
    } catch (e) {
      return '';
    }
    try { return new URL(c).href; } catch (e) { return c; }
  }

  /** Strict http(s) URL validator for persisted secondary links (altUrls, embeds). */
  function cleanHttpUrl(raw) {
    if (typeof raw !== 'string') return '';
    const s = raw.trim();
    if (!/^https?:\/\//i.test(s)) return '';
    try {
      const u = new URL(s);
      if (u.protocol !== 'http:' && u.protocol !== 'https:') return '';
      if (!u.hostname || !u.hostname.includes('.')) return '';
      return u.href;
    } catch (e) {
      return '';
    }
  }

  function cleanUrlList(arr, max) {
    if (!Array.isArray(arr)) return undefined;
    const out = [];
    const seen = new Set();
    for (const item of arr) {
      const c = cleanHttpUrl(item);
      if (!c || seen.has(c)) continue;
      seen.add(c);
      out.push(c);
      if (out.length >= max) break;
    }
    return out.length ? out : undefined;
  }

  function normalizeUrlKey(rawUrl) {
    try {
      let c = extractUrlCandidateFromText(rawUrl);
      if (!c) return '';
      if (!/^https?:\/\//i.test(c)) c = 'https://' + c;
      const u = new URL(c);
      return u.hostname.replace(/^(www|m)\./i, '').toLowerCase()
        + u.pathname.replace(/\/index\.html?$/i, '').replace(/\/+$/, '');
    } catch (e) {
      return '';
    }
  }

  function isImageIcon(icon) {
    const s = String(icon || '').trim();
    return /^https?:\/\//i.test(s) || /^data:image\//i.test(s);
  }

  function getDefaultFaviconUrl(rawUrl) {
    try {
      const u = new URL(cleanGameUrl(rawUrl));
      return `https://www.google.com/s2/favicons?sz=128&domain_url=${encodeURIComponent(u.origin)}`;
    } catch (e) {
      return '';
    }
  }

  function localTimeZoneId() {
    try { return Intl.DateTimeFormat().resolvedOptions().timeZone || ''; } catch (e) { return ''; }
  }

  /**
   * Cached ms until next reset for `g` at `now` (coerces numeric timestamps to Date).
   * Pass an optional Map `cache` to isolate cache state; defaults to a module-level Map.
   */
  function msUntilNextResetCached(g, now, cache) {
    const d = now === undefined ? new Date() : (now instanceof Date ? now : new Date(now));
    const store = cache || defaultNextResetCache;
    const sig = `${g.resetTime}|${g.resetZone}|${d.getTimezoneOffset()}|${localTimeZoneId()}`;
    let c = store.get(g.id);
    if (!c || c.sig !== sig || d.getTime() >= c.at) {
      c = { sig, at: HubCore.getNextResetDate(g.resetTime, g.resetZone, d).getTime() };
      store.set(g.id, c);
    }
    return Math.max(0, c.at - d.getTime());
  }

  /** Sorts `list` according to `mode` ('custom' | 'reset' | 'alpha' | 'least'). */
  function hubOrder(list, mode, now, cache) {
    const validMode = HUB_SORTS.includes(mode) ? mode : 'custom';
    const d = now === undefined ? new Date() : (now instanceof Date ? now : new Date(now));
    return HubCore.sortGames(Array.isArray(list) ? list : [], validMode, {
      msUntilReset: (g) => msUntilNextResetCached(g, d, cache),
      playCount: (g) => Number(g.totalPlayed) || 0
    });
  }

  /** Normalizes a persisted/imported game object, preserving validated altUrls (<=4) and embeds (<=8). */
  function normalizeGameObject(g, idx, refDate) {
    if (!g || typeof g !== 'object') return null;
    const cleanedUrl = cleanGameUrl(g.url);
    if (!cleanedUrl) return null;
    try {
      const u = new URL(cleanedUrl);
      if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
    } catch (e) {
      return null;
    }
    const resetTime = HubCore.sanitizeResetTime(g.resetTime);
    const resetZone = HubCore.normalizeZone(g.resetZone);
    const cleanHistory = HubCore.cleanDayHistory(g.dayHistory);
    const altUrls = cleanUrlList(g.altUrls, 4);
    const embeds = cleanUrlList(g.embeds, 8);

    const norm = {
      id: String(g.id || ('game-' + idx + '-' + Date.now().toString(36))).trim(),
      name: String(g.name || 'Daily Game').trim().slice(0, 80) || 'Daily Game',
      url: cleanedUrl,
      icon: isImageIcon(g.icon) ? String(g.icon).trim() : getDefaultFaviconUrl(cleanedUrl),
      category: String(g.category || 'Custom Daily'),
      resetTime,
      resetZone,
      maxTries: String(g.maxTries || 'Daily Puzzle'),
      scoreFormatDeclined: typeof g.scoreFormatDeclined === 'string' ? g.scoreFormatDeclined.slice(0, 40) : '',
      desc: String(g.desc || ''),
      notifyEnabled: g.notifyEnabled !== false,
      reminderMinutes: normalizeReminderMinutes(g.reminderMinutes),
      status: (g.status === 'completed' || g.status === 'missed') ? g.status : 'ready',
      todayScore: typeof g.todayScore === 'string' ? g.todayScore : '',
      streak: Math.max(0, parseInt(g.streak, 10) || 0),
      bestStreak: Math.max(0, parseInt(g.bestStreak, 10) || 0),
      totalPlayed: Math.max(0, parseInt(g.totalPlayed, 10) || 0),
      totalWon: Math.max(0, parseInt(g.totalWon, 10) || 0),
      lastCycleKey: /^\d{4}-\d{2}-\d{2}$/.test(String(g.lastCycleKey || ''))
        ? String(g.lastCycleKey)
        : HubCore.getCycleDateKey(resetTime, resetZone, refDate),
      dayHistory: cleanHistory,
      addedCycleKey: HubCore.DATE_KEY_RE.test(String(g.addedCycleKey || '')) ? String(g.addedCycleKey) : undefined,
      statsBaseline: (g.statsBaseline && typeof g.statsBaseline === 'object') ? g.statsBaseline : undefined,
      archived: g.archived === true ? true : undefined,
      dismissedResetSuggestion: typeof g.dismissedResetSuggestion === 'string' ? g.dismissedResetSuggestion.slice(0, 60) : undefined,
      notes: typeof g.notes === 'string' && g.notes.trim() ? g.notes.slice(0, 2000) : undefined,
      playTime: (g.playTime && HubCore.DATE_KEY_RE.test(String(g.playTime.key || '')) && Number(g.playTime.ms) > 0)
        ? { key: String(g.playTime.key), ms: Math.min(Number(g.playTime.ms), 6 * 3600000) } : undefined,
      altUrls,
      embeds
    };
    if (!norm.archived) delete norm.archived;
    if (!norm.dismissedResetSuggestion) delete norm.dismissedResetSuggestion;
    if (!norm.notes) delete norm.notes;
    if (!norm.playTime) delete norm.playTime;
    if (!norm.statsBaseline) delete norm.statsBaseline;
    if (!norm.addedCycleKey) delete norm.addedCycleKey;
    if (!norm.altUrls) delete norm.altUrls;
    if (!norm.embeds) delete norm.embeds;
    HubCore.ensureStatsBaseline(norm);
    HubCore.recomputeGameStatsFromHistory(norm, refDate);
    return norm;
  }

  function rowPriority(r) {
    const completed = r && r.outcome === 'Completed' ? 2 : 0;
    const hasScore = r && String(r.score || '').trim().length > 0 ? 1 : 0;
    return completed + hasScore;
  }

  /**
   * Deduplicates history log rows by (gameId || gameName, date), preferring 'Completed' over
   * 'Missed' and rows with score text over empty ones.
   */
  function dedupeHistoryLogs(logs) {
    if (!Array.isArray(logs)) return [];
    const chosen = new Map();
    const order = [];
    for (let i = 0; i < logs.length; i++) {
      const r = logs[i];
      if (!r || typeof r !== 'object') continue;
      const gKey = r.gameId || r.gameName || '';
      const dKey = typeof r.date === 'string' ? r.date : '';
      if (!gKey || !dKey) {
        order.push({ unique: true, row: r });
        continue;
      }
      const k = `${gKey}|${dKey}`;
      if (!chosen.has(k)) {
        const slot = { row: r };
        chosen.set(k, slot);
        order.push(slot);
      } else {
        const cur = chosen.get(k);
        const curP = rowPriority(cur.row);
        const newP = rowPriority(r);
        if (newP > curP || (newP === curP && (Number(r.loggedAt) || 0) > (Number(cur.row.loggedAt) || 0))) {
          cur.row = r;
        }
      }
    }
    return order.map((s) => s.row);
  }

  /**
   * Moves a game's day keys when its reset schedule changes, deduplicates colliding log rows,
   * recomputes log streaks, and keeps `todayScore` in sync with the new current cycle row.
   * Mutates `g` in place and returns `{ shift, changed, logs }`.
   */
  function applyScheduleChangeState(g, from, to, logs, now) {
    const list = Array.isArray(logs) ? logs : [];
    if (!g || typeof g !== 'object' || !from || typeof from !== 'object' || !to || typeof to !== 'object') {
      return { shift: 0, changed: false, logs: list };
    }
    if (!HubCore.HHMM_RE.test(String(to.resetTime || '')) || !HubCore.HHMM_RE.test(String(from.resetTime || ''))) {
      return { shift: 0, changed: false, logs: list };
    }
    const cleanFrom = { resetTime: HubCore.sanitizeResetTime(from.resetTime), resetZone: HubCore.normalizeZone(from.resetZone) };
    const cleanTo = { resetTime: HubCore.sanitizeResetTime(to.resetTime), resetZone: HubCore.normalizeZone(to.resetZone) };
    const at = now || new Date();
    const shift = HubCore.scheduleKeyShift(cleanFrom, cleanTo, at);
    g.resetTime = cleanTo.resetTime;
    g.resetZone = cleanTo.resetZone;
    const mineRows = list.filter((l) => l && (l.gameId ? l.gameId === g.id : l.gameName === g.name));
    const moved = HubCore.rescheduleDays(g.dayHistory, mineRows, cleanFrom, cleanTo, at);
    const changed = moved.dates.some((d, i) => d !== mineRows[i].date)
      || JSON.stringify(moved.dayHistory) !== JSON.stringify(g.dayHistory || {});
    let nextLogs = list;
    const todayKey = HubCore.getCycleDateKey(cleanTo.resetTime, cleanTo.resetZone, at);
    if (shift || changed) {
      g.dayHistory = moved.dayHistory;
      const clamp = (k) => (k > todayKey ? todayKey : k);
      if (HubCore.DATE_KEY_RE.test(String(g.lastCycleKey || ''))) {
        g.lastCycleKey = clamp(HubCore.addDaysToCycleKey(g.lastCycleKey, shift));
      }
      if (HubCore.DATE_KEY_RE.test(String(g.addedCycleKey || ''))) {
        g.addedCycleKey = clamp(HubCore.addDaysToCycleKey(g.addedCycleKey, shift));
      }
      mineRows.forEach((l, i) => { l.date = moved.dates[i]; });
      nextLogs = dedupeHistoryLogs(list);
      HubCore.recomputeLogStreaks(g, nextLogs);
    }
    const todayRow = nextLogs.find((l) => l && (l.gameId ? l.gameId === g.id : l.gameName === g.name) && l.date === todayKey);
    const todayOutcome = g.dayHistory && g.dayHistory[todayKey];
    if ((todayOutcome === 'completed' || todayOutcome === 'missed') && todayRow && typeof todayRow.score === 'string') {
      g.todayScore = todayRow.score;
    } else {
      g.todayScore = '';
    }
    return { shift, changed: Boolean(shift || changed), logs: nextLogs };
  }

  /** Computes the consecutive 'completed' streak ending on `dateKey` from `dayHistory`. */
  function streakAsOfDate(dayHistory, dateKey) {
    if (!dayHistory || typeof dayHistory !== 'object' || !HubCore.DATE_KEY_RE.test(String(dateKey || ''))) return 0;
    if (dayHistory[dateKey] !== 'completed') return 0;
    let streak = 0;
    let cursor = dateKey;
    while (dayHistory[cursor] === 'completed') {
      streak += 1;
      cursor = HubCore.addDaysToCycleKey(cursor, -1);
    }
    return streak;
  }

  /**
   * Groups history log rows by day (`row.date`) in order of first appearance and packs whole days
   * onto pages up to `pageSize` (default 8). A single day's entries always stay together on the
   * same page even when that day alone has more than `pageSize` entries.
   */
  function paginateHistoryByDay(rows, pageSize) {
    const limit = Math.max(1, Number(pageSize) || 8);
    if (!Array.isArray(rows) || rows.length === 0) return [{ rows: [], start: 0 }];
    const dayMap = new Map();
    const dayOrder = [];
    for (let i = 0; i < rows.length; i++) {
      const r = rows[i];
      if (!r || typeof r !== 'object') continue;
      const d = typeof r.date === 'string' && r.date ? r.date : `__idx_${i}`;
      let bucket = dayMap.get(d);
      if (!bucket) {
        bucket = [];
        dayMap.set(d, bucket);
        dayOrder.push(bucket);
      }
      bucket.push(r);
    }
    if (dayOrder.length === 0) return [{ rows: [], start: 0 }];
    const pages = [];
    let cur = [];
    let offset = 0;
    for (let i = 0; i < dayOrder.length; i++) {
      const dayRows = dayOrder[i];
      if (cur.length > 0 && cur.length + dayRows.length > limit) {
        pages.push({ rows: cur, start: offset });
        offset += cur.length;
        cur = [];
      }
      for (let j = 0; j < dayRows.length; j++) cur.push(dayRows[j]);
    }
    if (cur.length > 0) pages.push({ rows: cur, start: offset });
    return pages.length ? pages : [{ rows: [], start: 0 }];
  }

  return {
    HUB_SORTS,
    REMINDER_MINUTES,
    maxOf,
    minOf,
    normalizeReminderMinutes,
    trimUrlPunctuation,
    extractUrlCandidateFromText,
    cleanGameUrl,
    cleanHttpUrl,
    normalizeUrlKey,
    isImageIcon,
    getDefaultFaviconUrl,
    msUntilNextResetCached,
    hubOrder,
    normalizeGameObject,
    dedupeHistoryLogs,
    applyScheduleChangeState,
    streakAsOfDate,
    paginateHistoryByDay
  };
});
