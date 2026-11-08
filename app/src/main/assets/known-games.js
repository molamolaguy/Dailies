/*
 * Dailies — built-in catalogue of popular daily games and their reset schedules.
 *
 * When a link is added, the hub matches it here first. A known entry's reset time beats the
 * scraper's keyword guess. Entries with `verified: true` were checked against published reset
 * times (Nov 2026); the others are the app's earlier presets and are best-known values that the
 * user can still edit.
 *
 * Only 'local' and 'utc' schedules are representable. Games that reset on a fixed regional clock
 * (e.g. NYT Mini at 10 PM ET, Immaculate Grid at 9 AM ET) are intentionally not listed with a time.
 *
 * Loaded by index.html (window.KnownGames) and require()-able from Node for tests.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) {
    module.exports = api;
  } else {
    root.KnownGames = api;
  }
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  // host: bare hostname (no www.). path: optional path prefix that must match.
  const CATALOG = [
    { host: 'nytimes.com', path: '/games/wordle', name: 'Wordle', category: 'Word & Association',
      resetTime: '00:00', resetZone: 'local', maxTries: '6 guesses', verified: true,
      desc: 'Guess the five-letter word in six tries.' },
    { host: 'nytimes.com', path: '/games/connections', name: 'Connections', category: 'Word & Association',
      resetTime: '00:00', resetZone: 'local', maxTries: '4 mistakes', verified: true,
      desc: 'Group sixteen words into four hidden categories.' },
    { host: 'nytimes.com', path: '/games/strands', name: 'Strands', category: 'Word & Association',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Find all theme words', verified: true,
      desc: 'Find the theme words and the spangram in a letter grid.' },
    { host: 'contexto.me', name: 'Contexto', category: 'Word & Association',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Fewest guesses', verified: true,
      desc: 'Find the secret word using semantic closeness.' },
    { host: 'globle-game.com', name: 'Globle', category: 'Geography & Maps',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Fewest guesses', verified: true,
      desc: 'Find the mystery country using proximity hints.' },
    { host: 'nerdlegame.com', name: 'Nerdle', category: 'Math & Logic',
      resetTime: '00:00', resetZone: 'utc', maxTries: '6 equations', verified: true,
      desc: 'Daily 8-tile maths equation puzzle (resets at midnight UTC unless changed in Nerdle’s settings).' },
    { host: 'wafflegame.net', name: 'Waffle', category: 'Word & Association',
      resetTime: '00:00', resetZone: 'utc', maxTries: '15 swaps', verified: true,
      desc: 'Rearrange letters into 6 intersecting words (resets at midnight UTC unless changed in Waffle’s settings).' },

    // Earlier app presets (not independently verified).
    { host: 'fe-guess.com', name: 'FE Guess', category: 'Gaming & RPG',
      resetTime: '00:00', resetZone: 'utc', maxTries: '8 guesses', verified: false,
      desc: 'Daily Fire Emblem character guessing game across 14 mainline releases.' },
    { host: 'framed.wtf', name: 'Framed', category: 'Movies & Media',
      resetTime: '00:00', resetZone: 'utc', maxTries: '6 frames', verified: false,
      desc: 'Identify the movie from 6 progressively clearer still frames.' },
    { host: 'pokedoku.com', name: 'PokeDoku', category: 'Gaming & RPG',
      resetTime: '00:00', resetZone: 'local', maxTries: '9 shots (3x3 grid)', verified: false,
      desc: 'Daily 3x3 Pokemon grid matching types, regions, and evolutions.' },
    { host: 'travle.earth', name: 'Travle', category: 'Geography & Maps',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Shortest path', verified: false,
      desc: 'Connect two countries overland in as few borders as possible.' },
    { host: 'krillion.io', name: 'Krillion · The Daily Dive', category: 'Word & Association',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Daily Dive', verified: false,
      desc: 'Daily Dive puzzle and themed word association packs.' }
  ];

  function parse(url) {
    try {
      let s = String(url || '').trim();
      if (!/^https?:\/\//i.test(s)) s = 'https://' + s;
      const u = new URL(s);
      return { host: u.hostname.replace(/^www\./i, '').toLowerCase(), path: u.pathname.toLowerCase() };
    } catch (e) {
      return null;
    }
  }

  /** Best catalogue match for a game URL (longest path prefix wins), or null. */
  function find(url) {
    const p = parse(url);
    if (!p) return null;
    let best = null;
    CATALOG.forEach((entry) => {
      const hostOk = p.host === entry.host || p.host.endsWith('.' + entry.host);
      if (!hostOk) return;
      if (entry.path && !(p.path === entry.path || p.path.startsWith(entry.path + '/'))) return;
      if (!best || (entry.path || '').length > (best.path || '').length) best = entry;
    });
    return best ? Object.assign({}, best) : null;
  }

  return { CATALOG, find };
});
