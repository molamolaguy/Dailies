# Dailies 1.8.7

A cleaner look, tidier Score History, app menus in games, and no more flashes. It installs over 1.8.6 and keeps everything. If you're on 1.8.6, you can install it from **Settings → About → Check now**.

## Streaks & Log
- **Score History**: each folded result is one line (the game, its streak, time and ✓/✗); the result itself shows when you open it. No more PB tag, and the 🔥 lines up with its number.
- **Recent changes** moved from Score History to **Settings → Data & Backup**.
- **Recaps**: swiping the boxes keeps them inside the recap card.
- **Score stats** (under the calendar): no longer shows how many results are logged.

## Look
- **Settings**: no rounded box around each option, just a thin line between them.
- **Card Style** setting removed (cards use the standard style).
- **Hub icons**: every game's icon sits on the same rounded-square shape.
- **Bottom Bar See-Through**: toward Clear the bar also loses its colour and turns to clear glass, with a light sheen and a brighter edge.
- **In-app notifications**: no thicker bar down the left side.
- **Gold and ruby buttons** (+ Add, Today, the bottom bar): an even rim all round, so the highlight no longer looks lower than the button.
- **Data & Backup**: "Restore one game from a backup" removed; Trash no longer has an emoji.

## Game player
- **Drop-down lists on game pages** open the app's own menu, in the app's colours, instead of Android's picker.

## Fixes
- **Background flash when changing tabs** (dark and light themes): the glow behind the app is now fixed to the screen. It was sized to the page, so each tab (a different length) drew it differently.
- **Changing the theme**: everything changes colour at once (buttons used to fade behind the rest, so a box flashed around each app icon choice).

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.6

Smoother animations throughout. It installs over 1.8.5 and keeps everything. If you're on 1.8.5, you can install it from **Settings → About → Check now**.

## Smoother
- **"Done today"**: folds and unfolds with a fade and slide instead of shrinking, and only its own cards are added or removed (the whole list used to be rebuilt).
- **Settings and Achievements sections**: opening one fades its contents in, and the sections below glide to their new place instead of jumping.
- **Score History**: changing page, searching or filtering redraws only the list, so the next page slides in at once (the whole page, calendar included, used to be redrawn).
- **Saving**: changes are written at the next quiet moment (straight away when you leave the app or open a game), not in the middle of the animation they started. Device settings are only passed to Android when one changes.
- **Page swipes**: the page you left is set aside a moment later instead of in the same frame the new one lands, and countdowns don't tick while a page slides.
- **Countdowns**: only redrawn when the time shown changes (with seconds hidden, once a minute).
- **Back from a game**: the hub only redraws what changed while you played.
- **Celebration**: no full-screen blur behind it (it was worked out again on every frame).

## Fixes
- **Bottom Bar See-Through**: a scroll that starts on the slider no longer changes it.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.5

Game pages checked for changes, a more see-through bottom bar you can adjust, and smoother swipes. It installs over 1.8.4 and keeps everything. If you're on 1.8.4, you can install it from **Settings → About → Check now**.

## New
- **Check Game Pages for Changes** (Settings → Gameplay & Time, off until you turn it on): once a week, on Wi-Fi with the battery not low, Dailies reads each game's page again in the background. Nothing is changed by itself. The next time you open the app, it lists what changed for each game (name, description, icon, reset time, score type, category, or a new address) with the old and new value, and you tick what to update. Anything you skip isn't suggested again. **Check now** runs it straight away. Games from the built-in list, and score types learned from your results, are left alone.
- **Bottom Bar See-Through** (Settings → Display): a slider from solid to clear. The bar is now more see-through by default (75%).

## Improvements
- **Copied results**: copying or sharing all of today's results puts a spacer line between each game.
- **Recaps**: more room between the lines of the weekly and monthly recap, in the app and on the shared picture.
- **Gold and ruby accents**: the glow around buttons is now even on every side (it sat lower than the button).

## Fixes
- **First swipe to Settings**: no more stutter. Every page is now drawn ahead while the app is idle, not only the next one, so nothing heavy happens just after landing on Streaks & Log.
- **Stretching the hub sideways** right after opening the app is smooth: drawing pages ahead stops the moment a finger touches the screen.
- The first swipe away from the hub no longer redraws it.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.4

Smoother swipes between pages. It installs over 1.8.3 and keeps everything. If you're on 1.8.3, you can install it from **Settings → About → Check now**.

## Changes
- **Swiping to Settings with Glass cards**: no more stutter. Glass cards are now a flat see-through tint with a bright edge, which looks almost the same and is about a third of the work to draw.
- **The first swipe to a page**: no more small stutter the first time after opening the app. The pages next to the one you're on are now drawn ahead and kept ready just off screen.
- Work that can wait no longer runs while you're swiping.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.3

Smarter game adding, more results found automatically, and a tidier, smoother hub. It installs over 1.8.2 and keeps everything. If you're on 1.8.2, you can install it from **Settings → About → Check now**.

## Adding a game
- **Reads the page as it really looks**: most daily games build their page in JavaScript, so Dailies now also opens the link out of sight for a few seconds and reads what the game shows, including its "How to play". You get the game's real name, a short description of how to play, and its reset time and time zone more often (from the page, its app info, or its own code).
- **Score type**: only set when the page says so. After a few results, a game's format is set from what you've logged ("6 guesses", "Fastest time", "Most points"), with Undo.
- **More built-in games**: Blossom, Keyword, WhenTaken, Sedecordle, Duotrigordle, Spotle, Chronophoto and Globle: Capitals.

## Finding results in a game
- **"See results" first**: when a finished game keeps its Share button behind "See results" or "Stats", Dailies opens that once and finds the result.
- **Results shared by link**: games that share by opening X, WhatsApp, Telegram, a text message or email: the result is read from the link.
- **More games**: buttons inside web components, and end screens in Spanish, French, German, Portuguese, Italian and Dutch.
- **Result on screen**: a game with no Share button at all: Dailies offers the result it shows ("Solved in 4 guesses"). Tap **Log it** to keep it; it's never logged on its own.

## Hub, Streaks & Log and Settings
- Reset alerts no longer show a second copy of the app icon.
- Every list (Add Game, Settings) opens the app's own menu instead of Android's.
- Daily Hub: only **Play all** above your games (Play next and Select are gone), no highlight around the search box, and a more see-through bottom bar.
- Streaks & Log: no "Tough day" tags, no Personal Bests card, no duplicate fastest time in the score stats; swipe the recap's three boxes to change week or month; no gap above the secret achievements.
- Settings: swipes in without stutter; Card Style's Glass and Outline now look clearly different; the Gameplay & Time clock follows Show Seconds; Recent changes moved out of Data & Backup (it's still on Score History).

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.2

Reset alerts work again, the app opens straight to your hub, and swiping between tabs is smooth. It installs over 1.8.1 and keeps everything. If you're on 1.8.1, you can install it from **Settings → About → Check now**.

## Changes
- **Reset alerts**: when several games reset at the same time, the single "games are ready" alert was cleared the moment it was posted, so you got no alert at all. It now stays until you open it. (Alerts for a single game weren't affected.)
- **Opening the app**: no more blank screen. The launch screen stays up until the hub has drawn your games, so the first thing you see is the finished hub.
- **Swiping between tabs**: no more stutter as your finger starts moving. The page you're swiping to is kept ready instead of being built at that moment.
- **Streaks & Log**: the calendar no longer slides in too high and drops into place after the swipe.
- **Daily challenge**: removed, with its banner, its setting and the Challenger secret. Ruby now unlocks with the other five secrets, and if you've already unlocked it, you keep it.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.1

A faster, smoother hub and a tidier Score History. It installs over 1.8.0 and keeps everything. If you're on 1.8.0, you can install it from **Settings → About → Check now**.

## Changes
- **Score History**: results are grouped under their day (Today, Yesterday, Fri, Nov 13) with how many of that day's games you solved. Each result is one short row: the game, a one-line preview of the result, and ✓ or ✕ with your streak and play time. Tap a row for the full result, when you logged it, and **Copy**. Eight results per page instead of five, and the filters take less room.
- **Faster start**: the hub is ready about twice as fast with a long history. Achievements, the time-zone list and the reset-time check no longer hold up the first screen.
- **Smoother page switching**: sliding between Daily Hub, Streaks & Log and Settings no longer jumps at the start, and going back to the hub responds sooner.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.0

Search, quick actions, a trash bin and a smarter player, plus tools for keeping your games in shape. It installs over 1.7.0 and keeps everything. If you're on 1.7.0, you can install it from **Settings → About → Check now**.

## What's new since 1.7.0
| | 1.7.0 | 1.8.0 |
|---|---|---|
| **Search** | — | Tap the magnifier on the hub to find a game by name, site or category |
| **Quick actions** | Long-press only moved a card | Long-press a card and let go without moving it: Play, Log, notes, Stats & calendar, reset time, alerts, copy link, Archive, Remove. Right-click does the same on a computer. Dragging still reorders |
| **Game notes** | — | Keep a tip or strategy for each game (from its quick actions) |
| **Trash** | Removing a game kept its history out of sight | Removed games and forgotten history sit in **Settings → Data & Backup → Trash** for 30 days: restore them or delete them for good |
| **Recent changes** | The last 25 | Everything from the last 60 days, with a search box |
| **All done** | A toast | Your app icon sparkles, in gold or ruby if you use them, when you finish every game for the day (Settings → Display → Celebrate When All Done) |
| **Reset times** | Checked for a game when you logged it | Every game is checked when the app opens. Turn on **Fix Reset Times Automatically** (Settings → Gameplay & Time) to have them corrected for you, with Undo |
| **Game pages** | — | Cookie and consent banners are hidden, and pull down at the top of a page to reload it (Settings → Game Player) |
| **Links & icons** | — | **Settings → Data & Backup → Check links & icons**: find games whose site is gone or has moved (and switch to the new address), and fetch sharper icons |
| **Storage** | — | See what Dailies keeps on your phone (preloaded pages, icons, site cache, downloads) and clear it. Your games and history are never touched |
| **Report a problem** | — | **Settings → About → Report a problem** gathers recent errors and crashes (no games or scores) to copy, share or attach to a GitHub issue |
| **What's new** | Only in the release notes | Shown once after an update, and any time from **Settings → About** |
| **Start-up** | — | The hub opens sooner |

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.7.0

A daily challenge, secret achievements with a new Ruby look, more ways to style the app, and fixes for missed reset alerts. It installs over 1.6.1 and keeps everything. If you're on 1.6.1, you can install it from **Settings → About → Check now**.

## What's new since 1.6.1
| | 1.6.1 | 1.7.0 |
|---|---|---|
| **Daily challenge** | — | Each day Dailies picks 2 or 3 of your games (favoring ones you haven't played lately). Finish them all for a challenge streak. Turn it off in Settings → Gameplay & Time |
| **Secret achievements** | — | Six hidden achievements, shown as **???** until you find them |
| **Ruby** | — | Find every secret to unlock a Ruby app icon (the Dailies star cut as a raised, faceted 3D gemstone) and Ruby accents with a glassy, faceted gem finish (choose Ruby or Gold) |
| **Gold** | Satin gold | A polished champagne gold with a bright highlight band, and a new Gold icon in grained gold with crisp beveled edges |
| **App icon** | Preset styles | Also **Custom**: pick the star and background colors, then use it in the app or add it to your home screen as a shortcut |
| **Card style** | Filled | **Filled**, **Glass** or **Outline** (Settings → Display) |
| **Countdown font** | Mono | **Mono**, **Rounded** or **Classic** |
| **Text size and contrast** | — | **Large** and **Larger** text, and a **High Contrast** option |
| **Restoring** | A whole backup at once | Also **one game** from a backup (Settings → Data & Backup): add the days you're missing, or replace its history. Undo it from Recent changes |
| **Reset alerts** | Could be skipped when Dailies was open at the reset | Always posted, and they use your app icon's color and picture |
| **Hub** | The header scrolled away | The title and filter row stay at the top; pop-ups can be swiped away |
| **Speed** | Tabs rebuilt when opened | Smoother tab switching, and games preload after their reset on Wi-Fi so they open faster |

## Also changed
- Preloaded game files are deleted once you log the game, and expire at its next reset. Turn preloading off in **Settings → Game Player → Preload Games After Reset**.
- The bottom navigation bar is a little more see-through.
- Backups keep the Ruby unlock and your daily challenge history.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.6.1

Fixes and polish. It installs over 1.6.0 and keeps everything.

## Changes
- **Achievement details**: closing an achievement's details (with ✕ or Back) no longer leaves Streaks & Log stuck so it can't be scrolled or tapped. The same fix covers Today's results and Recent changes.
- **Color themes**: Wallpaper is no longer one of the themes. While **Match Device Wallpaper Colors** is on, the color themes are grayed out; turn it off to choose one.
- **Bottom navigation bar**: slightly see-through, so you can see what's behind it.
- **Web app**: the browser tab is titled just "Dailies". The web app is at https://molamolaguy.github.io/Dailies/.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.6.0

A feature update: play your games in a row, undo any change, and see weekly recaps and personal bests. It installs over 1.5.0 and keeps everything. If you're on 1.5.0, you can install it from **Settings → About → Check now**.

## What's new since 1.5.0
| | 1.5.0 | 1.6.0 |
|---|---|---|
| **Playing several games** | **Play next** after each game | **Play all** opens every game you haven't played, one after another; after each result the player counts down ("Play Strands in 3…") and moves on |
| **Undo** | Only from the toast, for 10 minutes | **Score History → ↶ Changes** (or Settings → Data & Backup) lists your last 25 logs, edits, removals and archived games; undo any of them |
| **Recap** | The calendar's month | **Week** or **Month**, compared with the week or month before, with your best game. **Share card** sends it as a picture |
| **Personal bests** | In each game's score stats | Also a **Personal Bests** section for every game, and a **🏆 PB** badge on the game card and in Score History when a result sets a record |
| **Tough days** | — | Days you scored much worse than usual (for example 6/6 when you usually get 4/6) are marked in the calendar and Score History |
| **Hub order** | Your own order | The ⇅ button sorts by **Next reset**, **A–Z** or **Least played**, or keeps your **Custom order** |
| **Today's results** | **Copy** copied each result | **Results** shows them as one message with a heading ("Dailies · Fri, Nov 13 — 5/7 solved"), with or without emoji grids, to **Copy** or **Share** |
| **Color themes** | Wallpaper colors or the default purple | Also **Ocean**, **Forest**, **Sunset**, **Rose** or a **Custom** color. Light, dark, AMOLED, the game player's bar and in-app notifications all follow it |
| **Settings** | Each heading folded | Sections start open; you can still fold any of them |
| **Achievements** | Tiles of different sizes with descriptions and tier badges | Same-size tiles; tap one for what each tier takes and your progress. Tier 2 shows a ★ in the corner |

## Also changed
- When you swipe between tabs, the highlight in the bottom bar follows your finger.
- Krillion results are found automatically: a "Copy result" button on a game's end screen is now recognized on its own.
- No scroll bar on the right while scrolling.
- Marathon counts whole hours.
- **Done** tags show a ★ instead of a dot.
- The game pickers in the calendar and Score History open the app's own drop-down list instead of Android's picker.
- Gold accents and the Gold app icon use a satin, brushed gold; the icon's star is engraved in dark gold.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.5.1

Polish and fixes. It installs over 1.5.0 and keeps everything.

## Changes
- **Settings** sections start open again; you can still fold any of them.
- **Swiping between tabs**: the highlight in the bottom bar now slides along with your finger, so it's already under the new tab when the page arrives.
- **Krillion** results are found automatically: a "Copy result" button on a game's end screen is now recognized on its own, including buttons built from plain page elements.
- **No scroll bar** on the right while scrolling.
- **Achievements**: every tile is the same size, with just the icon, name and progress. Tap one to see what each tier takes and how far along you are. Tier 2 shows a ★ in the corner instead of a tier badge. Marathon counts whole hours.
- **Done** tags show a ★ instead of a dot.
- **Game pickers** in the calendar and Score history open the app's own drop-down list instead of Android's picker.
- **Gold** now uses a satin, brushed gold throughout, including the Gold app icon, whose star is now engraved in dark gold.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.5.0

An update with more achievements and a tidier layout. It installs over 1.4.0 and keeps your games, streaks, history and earned achievements. If you're on 1.4.0, you can install it from **Settings → About → Check now**.

## What's new since 1.4.0
| | 1.4.0 | 1.5.0 |
|---|---|---|
| **Achievements** | 14 badges | 19 achievements with two tiers each (38 in all). New: Early bird, Night owl, Hole in one, Record breaker, Clean sweep, Bounce back, Regular, Marathon and Weekend warrior. Badges that overlapped were merged into tiers (Getting started II is 100 solves) |
| **Gold app icon** | Every achievement | A hidden reward to discover |
| **Gold accents** | — | New and secret: a shiny gold look for the whole app, for those who find it |
| **Achievements section** | Always open | Folded by default; tap to open |
| **Settings** | Every section open | Each heading folds; all start folded |
| **Reset alerts** | One notification per game, bundled when several came at once | One notification that lists every game that's ready (a single game still gets its own, as before) |
| **Switching tabs** | The new tab opened at the top | It opens at the same relative spot (e.g. scrolled to the bottom → the bottom) |
| **Removed games** | Re-add by pasting the link again | **Add Game → Add back a removed game** lists the 5 most recently removed games, with their streaks and history |

## Also changed
- Achievements you've already earned carry over as the matching tiers, and earned tiers are never taken away.
- Once the Gold icon or gold accents are unlocked they stay unlocked, including after a restore and when a later update adds new achievements.
- Backups now also keep the gold accents unlock.
- Gold accents use the same shiny metallic gold as the Gold icon on buttons, the selected tab, chips, progress bars, headings, card edges, streaks and solved calendar days.
- The clock in Settings → Gameplay & Time uses the same font and color as the games' countdowns.
- Only the 5 most recently removed games are kept for adding back. Results you logged for older removed games stay in Score history, but those games no longer appear in the calendar.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.4.0

A feature update. It installs over 1.3.0 and keeps your games, streaks and history. If you're on 1.3.0, you can install it from **Settings → About → Check now**.

## What's new since 1.3.0
| | 1.3.0 | 1.4.0 |
|---|---|---|
| **Logging results** | You had to tap the game's Share / Copy button | When a game shows its end screen, Dailies reads the result from its Share button by itself. Nothing is copied or shared. |
| **Several games in a row** | Back to the hub between games | Tap the game's name in the player to switch to any of today's games, or **Next** (⏭) for the next one you haven't played |
| **After a game** | Back to the hub, then pick the next game | When a result is saved in the player, **Play \<next game\>** opens it straight away, or **Back to hub**. Logging by hand after the player asks the same. |
| **Page zoom** | Not available | ⋮ → **Zoom** in the player; remembered for each site |
| **Reset times** | Set by hand | If a game's puzzle numbers show its reset time is wrong, Dailies suggests the right one |
| **Score history** | Filter by game and outcome | Also search every result you've logged (e.g. "2/6", "#117") |
| **Copy today's results** | One at a time | **Copy** at the top of the hub copies everything you've solved today, each with its own formatting |
| **Paging Score history** | Pages jumped | Pages follow your finger, like the calendar |
| **App icon** | White, or matching the theme | Also Midnight, Sunset, Ocean and Mono, plus a shiny **Gold** icon unlocked by earning every achievement (Settings → Display → App Icon) |
| **Hub logo** | Always in the theme's colors | Matches the app icon you chose |
| **Popular games** | 12 built-in games | 59, with their links and reset times: **Add Game → Or pick from popular daily games**. Includes NYT, LinkedIn, Quordle, Worldle, Bandle, Immaculate Grid, FE Guess, Globle, Krillion and Nintendoku |

## Also changed
- The player's **Reload** and **Open in browser** buttons moved into the ⋮ menu to make room for **Next**.
- **Find Results Automatically** can be turned off in Settings → Game Player.
- More share formats are recognized for automatic logging, such as LinkedIn's "Queens #540 | 1:42 👑", the NYT Mini's "solved … in 0:42", Quordle's numbered boards, and results counted in words, clues or strokes.
- Ordinary text you copy while a game is open (a shopping list with ✅, a date, an address, a link) is no longer mistaken for a result.
- The result card in the player has a **Copy** button, Back closes it, and it follows the keyboard.
- Results of games played earlier in one player session are logged with one **Undo**, including a game that reset while you were playing.
- Backups now keep your achievements, so the Gold icon stays unlocked after a restore.
- Games that share a site (e.g. Mini Nerdle next to Nerdle) can be added side by side.
- Search in Score history no longer garbles results with apostrophes or "&" in them.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.3.0

An update with fixes and polish. It installs over 1.2.0 and keeps your games, streaks and history.

## What's new since 1.2.0
| | 1.2.0 | 1.3.0 |
|---|---|---|
| **Updates** | "Get it" opened GitHub in the browser | **Check now** offers to download and install the update inside the app |
| **Auto-logging** | Could miss a result the game copied (e.g. Krillion) | Also checks for a copied result when you return to the game or close it, and accepts results scored in meters, % or points |
| **Play next** | None | Once you've finished a game, "Up next" opens the next unplayed one |
| **Score history** | Every game together | Filter by game and by Completed / Missed |
| **Streak reminders** | One time for every game | Each game can have its own time, or none |
| **Times and points** | Best and average only for "3/6"-style scores | Also for game times ("1:42"), points ("2,450 pts", Krillion's "60") and counts ("45 guesses") |
| **Tab swipes** | The old page faded out as a copy, which could leave traces | The real next page slides in beside the current one and follows your finger |
| **Calendar** | Tapping a day opened the editor | Shows that day's result first, with Copy and Edit buttons |
| **Calendar months** | ‹ › buttons only | Also swipe left or right on the calendar |
| **Score history entries** | Every result shown in full | Date, time, play time, result and streak; tap **Result** to see the text and copy it |
| **Finished games** | "Done today" open by default | Folded away until you open it |
| **Logged games** | ✓ button stayed on finished games | Hidden once today is logged; edit it from the game's settings (**Edit entry**) |
| **App icon** | Purple, follows your phone | White with a yellow-to-green star; **App Icon Matches Theme** still gives the themed icons |

## Removed
- The score distribution chart, which didn't suit games that aren't scored in guesses.
- One-handed mode.

## Also fixed
- Select mode no longer covers a game's Ready / Done tag.
- The share-list QR code screen no longer scrolls or stretches.
- Back closes every dialog, including the QR code and import screens.
- Shorter description for **Block Ads in Games**.
- The first swipe to Streaks & Log no longer stutters: the page is only redrawn when something changed.
- Changing month moves the month name, tiles and recap along with the calendar.
- A downloaded update file is deleted the next time the app starts.

## Requirements
- Android 7.0 or newer.
- In-app updates need **Install unknown apps** allowed for Dailies (Android asks the first time), and work from 1.3.0 onwards. Install 1.3.0 itself the usual way.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.2.0

A feature update. It installs over 1.1.0 and keeps your games, streaks and history.

## What's new since 1.1.0
| | 1.1.0 | 1.2.0 |
|---|---|---|
| **Play time** | Not tracked | Timed per puzzle, with fastest and average times |
| **Personal bests** | Not shown | Best score, fastest time and longest streak, with a note when you beat one |
| **Achievements** | None | 14 badges, such as a 30-day streak, 100 solves and a perfect week |
| **Monthly recap** | None | Games played, win rate, best streak and best result, ready to share |
| **Screenshots** | Log by hand | Share a result screenshot to Dailies and it reads the result |
| **Dark game pages** | Game sites' own colors | Sites without a dark mode are darkened in the dark theme |
| **Games you've stopped playing** | Remove only | Archive: hidden, no alerts, history kept |
| **Editing many games** | One at a time | Select several: alerts on/off, reset time, archive |
| **Sharing your games** | Not possible | A link or QR code that adds the same games for a friend |
| **Your history** | `.json` backup | Also a `.csv` spreadsheet |
| **One-handed mode** | None | The page starts lower and dialogs open at the bottom |

## Also changed
- A new app icon: a Material 3 flower shape with a sparkle. It follows your phone's light or dark theme and, on Android 12+, your wallpaper colors. Turn on **App Icon Matches Theme** to have it follow the app's own theme instead.
- Swiping between tabs follows your finger and glides into place.
- The "Done today" section is a clear Show/Hide row that animates and brings the cards into view.

## Requirements
- Android 7.0 or newer.
- Reading screenshots needs Google Play services (the text is read on the phone).
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.1.0

A feature update. It installs over 1.0.x and keeps your games, streaks and history.

## What's new since 1.0.1
| | 1.0.1 | 1.1.0 |
|---|---|---|
| **Theme** | Dark only | Dark, light, or match your phone |
| **Finished games** | Mixed in with the rest | Grouped under "Done today", which you can fold away |
| **Stats** | Streaks and plays | Also win rate, average score and guess spread per game |
| **Streak reminder** | None | Optional alert before a reset if a streak game isn't logged |
| **Alert sound** | Silent unless changed in Android settings | Your phone's default sound, unless you'd set it yourself |
| **Backups** | Saved by hand | Also weekly to a folder you choose (last 5 kept) |
| **Updates** | Check GitHub yourself | Optional daily check with an "update available" banner |
| **Sign-in popups** | No ad blocking | Same ad blocking as the game |

## Also fixed
- Text and icons that sat slightly off-center (alert chips, bottom bar, game player toolbar).
- Game details and icons load through a phone proxy or VPN proxy.
- Downloads from games save inside the app on Android 7–9.
- Sharper, centered app icon on Android 7.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.0.1

A bug-fix update. It installs over 1.0.0 and keeps your games, streaks and history.

## What changed since 1.0.0
| | 1.0.0 | 1.0.1 |
|---|---|---|
| **Failed puzzles** | Auto-logged as Solved | Logged as Missed |
| **Logging near a reset** | Could land on the next day and break your streak | Saved to the puzzle you opened |
| **Editing a game's link** | Reset its name and reset time | Keeps your settings |
| **Importing a backup** | Replaced everything without asking | Asks first, keeps history, can be undone |
| **Sign-in popups and new-window links** | Replaced the game page | Open in a window over the game |
| **Ads after Reload** | Could come back | Stay hidden |
| **Reset alerts** | Duplicates after travel, none after a reboot, always silent | One per new puzzle, caught up after a reboot, sound follows your settings |
| **Narrow screens and large text** | Countdowns and labels cut off | Fit cleanly |

## Also fixed
- Undo on every log, and past days can be corrected from the calendar.
- Saving retries after an error and warns you if it keeps failing.
- Downloads from games now work.
- Right-to-left and emoji game names, plurals and calendar language display correctly.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.0.0

The first public release.

## Highlights
- **Daily Hub**: add any daily game by link; its name, icon and reset time are filled in for you.
- **Correct reset times**: each reset follows the game's own time zone and is shown in yours.
- **Game player**: built-in ad blocking with a per-site shield.
- **Auto-logging**: tap Share in a game and your result is logged.
- **Streaks & Log**: a calendar, best and current streaks, and your score history.
- **Reset alerts**: a notification when a new puzzle is out.
- **Your data**: stored only on your phone, with `.json` backup and import.

## Upgrading from a private 2.x build
1.0.0 uses a new signing key, so it can't install over a 2.x build. Save a backup in the old app, uninstall it, install 1.0.0, then use **Import Backup**.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`
