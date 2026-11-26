# Playing daily games to collect their share text

Goal: for each game in your batch, play today's puzzle in a real (headless) browser until the game
offers its result, press its Share / Copy button, and record the exact text the game shares. Winning
does not matter: a lost game's share text is just as useful (we only need its format). Be quick.

Folder: /home/user/Dailies/data/listdle-play
(cd there first). Games: games.jsonl (one JSON object per line, fields id, name, url, goal, desc).

## The browser helper
    node drive.js <id> <url> '<steps>'
- Reopens the game with a saved profile each time, so progress is kept between runs.
- `<steps>`: JavaScript run in an async function, with `page` (Playwright) and helpers:
  `await click("regex")` (clicks a visible button/link whose text matches), `await type("CRANE")`
  (types and presses Enter), `await key("Backspace")`, `await tap(x, y)` (screen is 412x900),
  `await wait(ms)`, `frame("part of url")` (a Playwright Frame for an embedded game iframe, listed under
  FRAMES in the output). `click` searches buttons first, then any element whose text matches, in every
  frame. Shares are collected from every frame. You can also use `page` directly (page.locator(...).click(), page.keyboard, etc.).
- Prints the page text and every text the game copied/shared so far ("CAPTURED SHARES"), and saves a
  screenshot at work/<id>/shot.png: use the Read tool on it to see the game.
- Start with `node drive.js <id> <url> ''` to look, then act. Use `timeout 120 node drive.js ...`.
- Copy/Share is captured automatically (clipboard, share sheet, copy command). If a game only shows the
  result on screen with no Share/Copy button, record the on-screen result text instead and say so.

## Rules
- Prefer short regexes for click, e.g. `click("^share")`. Use `page.evaluate(() => ...)` (or
  `frame(...).evaluate`) with console.log to inspect the DOM when you need to.
- Games with long timers: you may let one run wait up to 3 minutes (`await wait(170000)`, run with
  `timeout 240`). Don't wait longer than that; record what you have.
- A share captured from navigator.share is the title, text and url joined by line breaks: record it as is.
- Spend at most about 8 helper runs per game. If it's not working, record why and move on.
- Never sign up, log in, pay, or enter personal details. Skip games that require an account.
- Don't play past today's puzzle or open the archive; one daily result is enough.
- Some links aren't daily games or are dead: record that.
- When a game is done, delete its profile: `rm -rf work/<id>/profile`.

## Recording results
Append one JSON line per game to results/<your batch name>.jsonl (use a small python or node
command, keep the share text exact, including line breaks and emoji):
    {"id": 12, "name": "...", "status": "ok" | "on_screen_only" | "no_share" | "needs_account" | "not_daily" | "broken" | "blocked",
     "share_text": "exact text, or empty", "score": "how the result is scored, e.g. '3/6 guesses', 'time 1:42', '840 points', 'X/6 (lost)'",
     "notes": "one short sentence: how to play/finish, anything odd (e.g. reset time shown on page)"}
Write each line as soon as that game is finished, so nothing is lost.

When your batch is done, reply with only: counts by status, and any game where something notable
happened (one line each). Do not paste the share texts in the reply.
