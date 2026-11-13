/*
 * Dailies — built-in catalogue of popular daily games and their reset schedules.
 *
 * When a link is added, the hub matches it here first. A known entry's reset time beats the
 * scraper's keyword guess. Entries with `verified: true` were checked against published reset
 * times (Nov 2026); the others are best-known values. If one is wrong, the hub's reset-time check
 * (puzzle numbers vs. when results were logged) suggests the right time after a few results.
 *
 * resetZone is 'local', 'utc' or an IANA zone (e.g. 'America/New_York'). `url` is the address the
 * "Popular games" picker opens; without it, https://<host><path>/.
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
    { host: 'globle-game.com', url: 'https://globle-game.com/game', name: 'Globle', category: 'Geography & Maps',
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
      desc: 'Daily Dive puzzle and themed word association packs.' },

    // 1.4.0: more popular daily games (best-known reset times; see the note at the top).
    { host: 'nytimes.com', path: '/puzzles/spelling-bee', name: 'Spelling Bee', category: 'Word & Association',
      resetTime: '03:00', resetZone: 'America/New_York', maxTries: 'Reach Genius', verified: false,
      desc: 'Make words from seven letters; every word must use the centre letter.' },
    { host: 'nytimes.com', path: '/crosswords/game/mini', name: 'Mini Crossword', category: 'Word & Association',
      resetTime: '22:00', resetZone: 'America/New_York', maxTries: 'Fastest time', verified: false,
      desc: 'The 5x5 daily mini crossword (weekend puzzles come out at 6 PM ET).' },
    { host: 'nytimes.com', path: '/crosswords/game/daily', name: 'The Crossword', category: 'Word & Association',
      resetTime: '22:00', resetZone: 'America/New_York', maxTries: 'Solve the grid', verified: false,
      desc: 'The New York Times daily crossword (weekend puzzles come out at 6 PM ET).' },
    { host: 'nytimes.com', path: '/puzzles/letter-boxed', name: 'Letter Boxed', category: 'Word & Association',
      resetTime: '03:00', resetZone: 'America/New_York', maxTries: 'Fewest words', verified: false,
      desc: 'Connect letters around a square into a chain of words.' },
    { host: 'nytimes.com', path: '/puzzles/tiles', name: 'Tiles', category: 'Custom Daily',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Longest combo', verified: false,
      desc: 'Match patterns across tiles to clear the board.' },
    { host: 'nytimes.com', path: '/puzzles/sudoku', name: 'NYT Sudoku', category: 'Math & Logic',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Easy, medium and hard', verified: false,
      desc: 'Daily sudoku in three difficulties.' },
    { host: 'nytimes.com', path: '/games/pips', name: 'Pips', category: 'Math & Logic',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Fastest time', verified: false,
      desc: 'Place dominoes so every region meets its condition.' },
    { host: 'nytimes.com', path: '/athletic/connections-sports-edition', name: 'Connections: Sports Edition', category: 'Word & Association',
      resetTime: '00:00', resetZone: 'local', maxTries: '4 mistakes', verified: false,
      desc: 'Group sixteen sports words into four categories.' },
    { host: 'linkedin.com', path: '/games/queens', name: 'Queens', category: 'Math & Logic',
      resetTime: '00:00', resetZone: 'America/Los_Angeles', maxTries: 'Fastest time', verified: false,
      desc: 'Place one queen in every row, column and colour region.' },
    { host: 'linkedin.com', path: '/games/tango', name: 'Tango', category: 'Math & Logic',
      resetTime: '00:00', resetZone: 'America/Los_Angeles', maxTries: 'Fastest time', verified: false,
      desc: 'Fill the grid with suns and moons following the rules.' },
    { host: 'linkedin.com', path: '/games/zip', name: 'Zip', category: 'Math & Logic',
      resetTime: '00:00', resetZone: 'America/Los_Angeles', maxTries: 'Fastest time', verified: false,
      desc: 'Draw one path through the numbers in order, filling every cell.' },
    { host: 'linkedin.com', path: '/games/pinpoint', name: 'Pinpoint', category: 'Word & Association',
      resetTime: '00:00', resetZone: 'America/Los_Angeles', maxTries: 'Fewest clues', verified: false,
      desc: 'Guess the category from as few words as possible.' },
    { host: 'linkedin.com', path: '/games/crossclimb', name: 'Crossclimb', category: 'Word & Association',
      resetTime: '00:00', resetZone: 'America/Los_Angeles', maxTries: 'Fastest time', verified: false,
      desc: 'A word ladder of trivia clues.' },
    { host: 'linkedin.com', path: '/games/mini-sudoku', name: 'Mini Sudoku', category: 'Math & Logic',
      resetTime: '00:00', resetZone: 'America/Los_Angeles', maxTries: 'Fastest time', verified: false,
      desc: 'A 6x6 daily sudoku.' },
    { host: 'merriam-webster.com', path: '/games/quordle', also: [{ host: 'm-w.com', path: '/games/quordle' }], name: 'Quordle', category: 'Word & Association',
      resetTime: '00:00', resetZone: 'local', maxTries: '9 guesses', verified: false,
      desc: 'Solve four Wordles at once.' },
    { host: 'octordle.com', also: [{ host: 'britannica.com', path: '/games/octordle' }], name: 'Octordle', category: 'Word & Association',
      resetTime: '00:00', resetZone: 'local', maxTries: '13 guesses', verified: false,
      desc: 'Solve eight Wordles at once.' },
    { host: 'semantle.com', name: 'Semantle', category: 'Word & Association',
      resetTime: '00:00', resetZone: 'utc', maxTries: 'Fewest guesses', verified: false,
      desc: 'Find the secret word by meaning, with closeness scores.' },
    { host: 'squaredle.app', name: 'Squaredle', category: 'Word & Association',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Find every word', verified: false,
      desc: 'Find words in a square of letters.' },
    { host: 'wordiply.com', name: 'Wordiply', category: 'Word & Association',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Longest word', verified: false,
      desc: 'Make the longest word that contains the given letters.' },
    { host: 'solitaired.com', path: '/phrazle', name: 'Phrazle', category: 'Word & Association',
      resetTime: '00:00', resetZone: 'local', maxTries: '6 guesses', verified: false,
      desc: 'Guess the phrase, Wordle-style. There are two a day (midnight and noon); Dailies tracks the midnight one.' },
    { host: 'wordwormdormdork.com', name: 'Weaver', category: 'Word & Association',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Fewest steps', verified: false,
      desc: 'Change one letter at a time to go from word to word.' },
    { host: 'theatlantic.com', path: '/games/bracket-city', name: 'Bracket City', category: 'Word & Association',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Solve every bracket', verified: false,
      desc: 'Nested clues that build to one answer.' },
    { host: 'crosswordle.com', name: 'Crosswordle', category: 'Word & Association',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Fewest guesses', verified: false,
      desc: 'A crossword built from Wordle-style guesses.' },
    { host: 'thricegame.com', name: 'Thrice', category: 'Custom Daily',
      resetTime: '00:00', resetZone: 'local', maxTries: '15 points', verified: false,
      desc: 'Five trivia questions, three clues each.' },
    { host: 'worldle.teuteuf.fr', name: 'Worldle', category: 'Geography & Maps',
      resetTime: '00:00', resetZone: 'local', maxTries: '6 guesses', verified: false,
      desc: 'Guess the country from its outline.' },
    { host: 'oec.world', path: '/en/games/tradle', also: [{ host: 'oec.world', path: '/en/tradle' }, { host: 'games.oec.world', path: '/en/tradle' }],
      name: 'Tradle', category: 'Geography & Maps', resetTime: '00:00', resetZone: 'utc', maxTries: '6 guesses', verified: false,
      desc: 'Guess the country from its exports.' },
    { host: 'flagle.io', name: 'Flagle', category: 'Geography & Maps',
      resetTime: '00:00', resetZone: 'local', maxTries: '6 guesses', verified: false,
      desc: 'Guess the flag a piece at a time.' },
    { host: 'timeguessr.com', name: 'TimeGuessr', category: 'Geography & Maps',
      resetTime: '00:00', resetZone: 'local', maxTries: '50,000 points', verified: false,
      desc: 'Guess where and when each photo was taken.' },
    { host: 'countryle.com', name: 'Countryle', category: 'Geography & Maps',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Fewest guesses', verified: false,
      desc: 'Find the country from hints about its location and stats.' },
    { host: 'geogridgame.com', name: 'GeoGrid', category: 'Geography & Maps',
      resetTime: '00:00', resetZone: 'local', maxTries: '9 answers', verified: false,
      desc: 'A 3x3 grid of countries that fit both categories.' },
    { host: 'mathler.com', name: 'Mathler', category: 'Math & Logic',
      resetTime: '00:00', resetZone: 'local', maxTries: '6 guesses', verified: false,
      desc: 'Find the hidden equation that equals the number.' },
    { host: 'cluesbysam.com', name: 'Clues by Sam', category: 'Math & Logic',
      resetTime: '00:00', resetZone: 'local', maxTries: 'No mistakes', verified: false,
      desc: 'A logic puzzle: work out who is innocent and who is a criminal.' },
    { host: 'murdle.com', name: 'Murdle', category: 'Math & Logic',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Solve the case', verified: false,
      desc: 'A daily murder-mystery logic grid.' },
    { host: 'chess.com', path: '/daily-chess-puzzle', name: 'Chess.com Daily Puzzle', category: 'Math & Logic',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Solve it', verified: false,
      desc: 'The daily chess puzzle.' },
    { host: 'puzzmo.com', url: 'https://www.puzzmo.com/today', name: 'Puzzmo', category: 'Custom Daily',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Today\'s puzzles', verified: false,
      desc: 'A daily set of word and logic puzzles.' },
    { host: 'jigsawexplorer.com', path: '/daily-jigsaw', name: 'Daily Jigsaw', category: 'Custom Daily',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Fastest time', verified: false,
      desc: 'A new jigsaw puzzle every day.' },
    { host: 'putt.day', name: 'putt.day', category: 'Custom Daily',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Fewest strokes', verified: false,
      desc: 'A daily minigolf hole.' },
    { host: 'framed.wtf', path: '/episode', name: 'Episode', category: 'Movies & Media',
      resetTime: '00:00', resetZone: 'utc', maxTries: '6 frames', verified: false,
      desc: 'Identify the TV show from six frames.' },
    { host: 'moviedle.app', name: 'Moviedle', category: 'Movies & Media',
      resetTime: '00:00', resetZone: 'local', maxTries: '6 tries', verified: false,
      desc: 'Guess the movie from a one-second, sped-up clip.' },
    { host: 'bandle.app', name: 'Bandle', category: 'Movies & Media',
      resetTime: '00:00', resetZone: 'local', maxTries: '6 tries', verified: false,
      desc: 'Guess the song as more instruments join in.' },
    { host: 'actorle.com', name: 'Actorle', category: 'Movies & Media',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Fewest guesses', verified: false,
      desc: 'Guess the actor from their films.' },
    { host: 'costcodle.com', name: 'Costcodle', category: 'Custom Daily',
      resetTime: '00:00', resetZone: 'local', maxTries: '6 guesses', verified: false,
      desc: 'Guess the price of a Costco item.' },
    { host: 'loldle.net', name: 'Loldle', category: 'Gaming & RPG',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Fewest guesses', verified: false,
      desc: 'Guess the League of Legends champion.' },
    { host: 'pokedle.net', name: 'Pokedle', category: 'Gaming & RPG',
      resetTime: '00:00', resetZone: 'local', maxTries: 'Fewest guesses', verified: false,
      desc: 'Guess the Pokémon from its attributes.' },
    { host: 'gamedle.wtf', name: 'Gamedle', category: 'Gaming & RPG',
      resetTime: '00:00', resetZone: 'local', maxTries: '6 guesses', verified: false,
      desc: 'Guess the video game from its art.' },
    { host: 'immaculategrid.com', name: 'Immaculate Grid', category: 'Custom Daily',
      resetTime: '09:00', resetZone: 'America/New_York', maxTries: '9 guesses', verified: false,
      desc: 'A 3x3 grid of baseball players who fit both clues.' },
    { host: 'n-puzzle.com', path: '/en/nintendoku', name: 'Nintendoku', category: 'Gaming & RPG',
      resetTime: '06:00', resetZone: 'Europe/Paris', maxTries: '9 guesses', verified: false,
      desc: 'A 3x3 grid of Nintendo games that fit both categories.' }
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
    let bestLen = -1;
    CATALOG.forEach((entry) => {
      // Other addresses the same game is played at (a site that moved, a short link).
      [entry].concat(entry.also || []).forEach((at) => {
        // Subdomains only for addresses with a path: a whole-site entry (Nerdle) isn't its siblings (mini.nerdlegame.com).
        const hostOk = p.host === at.host || (at.path && p.host.endsWith('.' + at.host));
        if (!hostOk) return;
        if (at.path && !(p.path === at.path || p.path.startsWith(at.path + '/'))) return;
        const len = (at.path || '').length;
        if (len > bestLen) { best = entry; bestLen = len; }
      });
    });
    return best ? Object.assign({}, best) : null;
  }

  return { CATALOG, find };
});
