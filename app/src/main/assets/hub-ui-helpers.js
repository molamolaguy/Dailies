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
      return u.hostname.replace(/^www\./i, '').toLowerCase()
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

  /**
   * Cached ms until next reset for `g` at `now` (coerces numeric timestamps to Date).
   * Pass an optional Map `cache` to isolate cache state; defaults to a module-level Map.
   */
  function msUntilNextResetCached(g, now, cache) {
    const d = now === undefined ? new Date() : (now instanceof Date ? now : new Date(now));
    const store = cache || defaultNextResetCache;
    const sig = `${g.resetTime}|${g.resetZone}|${d.getTimezoneOffset()}`;
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
   * Moves a game's day keys when its reset schedule changes, and deduplicates colliding log rows.
   * Mutates `g` in place and returns `{ shift, changed, logs }`.
   */
  function applyScheduleChangeState(g, from, to, logs, now) {
    const at = now || new Date();
    const shift = HubCore.scheduleKeyShift(from, to, at);
    g.resetTime = to.resetTime;
    g.resetZone = to.resetZone;
    const list = Array.isArray(logs) ? logs : [];
    const mineRows = list.filter((l) => l && (l.gameId ? l.gameId === g.id : l.gameName === g.name));
    const moved = HubCore.rescheduleDays(g.dayHistory, mineRows, from, to, at);
    const changed = moved.dates.some((d, i) => d !== mineRows[i].date)
      || JSON.stringify(moved.dayHistory) !== JSON.stringify(g.dayHistory || {});
    let nextLogs = list;
    if (shift || changed) {
      g.dayHistory = moved.dayHistory;
      const todayKey = HubCore.getCycleDateKey(to.resetTime, to.resetZone, at);
      const clamp = (k) => (k > todayKey ? todayKey : k);
      if (HubCore.DATE_KEY_RE.test(String(g.lastCycleKey || ''))) {
        g.lastCycleKey = clamp(HubCore.addDaysToCycleKey(g.lastCycleKey, shift));
      }
      if (HubCore.DATE_KEY_RE.test(String(g.addedCycleKey || ''))) {
        g.addedCycleKey = clamp(HubCore.addDaysToCycleKey(g.addedCycleKey, shift));
      }
      mineRows.forEach((l, i) => { l.date = moved.dates[i]; });
      nextLogs = dedupeHistoryLogs(list);
    }
    return { shift, changed: Boolean(shift || changed), logs: nextLogs };
  }

  /**
   * Pure state transition for single-game backup Restore ('replace' | 'merge').
   * Mutates `state.games` and `state.historyLogs` and returns `{ target, before, added, newGame }` or null.
   */
  function applyRestoreGameState(state, backupGame, backupLogs, mode, refDate) {
    if (!state || !backupGame) return null;
    const rows = (Array.isArray(backupLogs) ? backupLogs : [])
      .filter((l) => l && (l.gameId ? l.gameId === backupGame.id : l.gameName === backupGame.name));
    let target = state.games.find((x) => x.id === backupGame.id)
      || state.games.find((x) => normalizeUrlKey(x.url) === normalizeUrlKey(backupGame.url));
    const before = {
      rows: state.historyLogs
        .filter((l) => target && l && (l.gameId ? l.gameId === target.id : l.gameName === target.name))
        .map((r) => Object.assign({}, r)),
      game: target ? JSON.parse(JSON.stringify(target)) : null
    };
    let added = 0;
    if (!target) {
      target = normalizeGameObject(JSON.parse(JSON.stringify(backupGame)), state.games.length, refDate);
      if (!target) return null;
      if (state.games.some((x) => x.id === target.id)) {
        target.id = HubCore.uniqueId(target.id, new Set(state.games.map((x) => x.id)));
      }
      state.games.push(target);
    }
    const mine = (l) => Boolean(l && (l.gameId ? l.gameId === target.id : l.gameName === target.name));
    if (mode === 'replace') {
      state.historyLogs = state.historyLogs.filter((l) => !mine(l));
      target.dayHistory = {};
    }
    const have = new Set(state.historyLogs.filter(mine).map((l) => l.date));
    rows.forEach((r) => {
      if (have.has(r.date)) return;
      state.historyLogs.push(Object.assign({}, r, { gameId: target.id, gameName: target.name }));
      have.add(r.date);
      added += 1;
    });
    if (!target.dayHistory || typeof target.dayHistory !== 'object') target.dayHistory = {};
    Object.entries(backupGame.dayHistory || {}).forEach(([k, v]) => {
      if (HubCore.DATE_KEY_RE.test(k) && (v === 'completed' || v === 'missed') && (mode === 'replace' || !target.dayHistory[k])) {
        target.dayHistory[k] = v;
      }
    });
    state.historyLogs.sort((a, b) => String(b.date).localeCompare(String(a.date)));
    const todayKey = HubCore.getCycleDateKey(target.resetTime, target.resetZone, refDate);
    if (mode === 'replace') {
      target.status = target.dayHistory[todayKey] || 'ready';
      const todayRow = state.historyLogs.find((l) => mine(l) && l.date === todayKey);
      target.todayScore = (target.status === 'completed' || target.status === 'missed') && todayRow && typeof todayRow.score === 'string'
        ? todayRow.score : '';
    } else if (!target.todayScore && (target.dayHistory[todayKey] === 'completed' || target.dayHistory[todayKey] === 'missed')) {
      const todayRow = state.historyLogs.find((l) => mine(l) && l.date === todayKey);
      if (todayRow && typeof todayRow.score === 'string') target.todayScore = todayRow.score;
    }
    HubCore.recomputeGameStatsFromHistory(target, refDate);
    return { target, before, added, newGame: !before.game };
  }

  /**
   * Pure state transition for undoing a single-game backup Restore.
   * Restores `historyLogs`, `dayHistory`, `status`, and `todayScore` from `change.before.game`.
   */
  function restoreRestoredGameState(state, change, refDate) {
    if (!state || !change) return false;
    const game = state.games.find((x) => x.id === change.gameId);
    if (!game) return false;
    state.historyLogs = state.historyLogs
      .filter((l) => l && (l.gameId ? l.gameId !== game.id : l.gameName !== game.name))
      .concat((change.before && change.before.rows) || []);
    state.historyLogs.sort((a, b) => String(b.date).localeCompare(String(a.date)));
    if (change.newGame) {
      state.games = state.games.filter((x) => x.id !== game.id);
    } else if (change.before && change.before.game) {
      const prev = change.before.game;
      game.dayHistory = JSON.parse(JSON.stringify(prev.dayHistory || {}));
      game.status = (prev.status === 'completed' || prev.status === 'missed') ? prev.status : 'ready';
      game.todayScore = typeof prev.todayScore === 'string' ? prev.todayScore : '';
      HubCore.recomputeGameStatsFromHistory(game, refDate);
    }
    return true;
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
    applyRestoreGameState,
    restoreRestoredGameState,
    streakAsOfDate
  };
});
