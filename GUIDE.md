# Dailies 1.10.3: How the App Works

- **App name**: Dailies
- **Package ID**: `com.dailygames.hub`
- **Version**: `1.10.3` (`versionCode = 38`)
- **Android**: 7.0 (API 24) or newer; built for Android 17 (API 37)
- **Layout target**: large phones such as the Pixel XL line (about 448 CSS px wide), checked down to 360 px
- **License**: GPL-3.0 (see [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md))

Dailies is a home for the daily web puzzles you play: Wordle, Connections, Nerdle, geography and
trivia games, or any site with a once-a-day puzzle. It tracks when each one resets, alerts you when a
new puzzle is out, opens games in a built-in ad-blocking player, and keeps your streaks and results.

---

## 1. What's in Dailies

| Area | What you get |
|---|---|
| **Daily Hub** | A card for every game you add, with a live countdown to its next reset, your streak, and Play / Log / Edit actions. Search, sort, filter by All / Ready / Done, group finished games, long-press to drag-reorder or open Quick Actions (with notes), or tap **Play all** to run through unplayed games in sequence. |
| **Adding games** | Paste or share a link (or a `dailies://add` shared list / QR code). The app reads the game's name, icon, category, target format/par, short description, reset time and reset time zone from the page, detects duplicates, and includes a built-in catalog of 69 popular daily games with known reset times. |
| **Reset times** | Each reset is stored in the time zone the game publishes it in (your local time, UTC, or a named zone such as `America/New_York` or `Europe/Berlin`). It's always shown in your phone's local time, with daylight-saving changes handled correctly. |
| **Game player** | Full-screen player with Hub, a game switcher on the title, Next, an ad-block shield (with a blocked count), a ⋮ menu (Reload, Open in browser, Zoom) and Log. Supports fullscreen video and canvas, file uploads (Photo Picker), downloads, cookie-banner hiding, pull-to-refresh, dark game pages, asset preloading and safe `intent://` links. |
| **Auto-logging** | When you tap a game's **Share** or **Copy** button — or when a game reaches its end screen — the result is captured. Going back to the hub logs it as Solved (or Missed for a failed result such as "X/6"), with the share text as your score and an **Undo** button. Practice, Unlimited, Replay and Archive rounds are ignored so they never overwrite today's Daily. You can also read results from screenshots via on-device OCR. |
| **Ad blocking** | About 316,000 ad, tracker and malware domains, 21,500 URL rules and 42,900 element-hiding rules, built from EasyList, EasyPrivacy, AdGuard Base, HaGeZi Pro and StevenBlack. You can switch it off per site or everywhere. |
| **My Results** | A completion calendar per game (best streak, current streak, plays, score stats and personal bests), weekly and monthly swipeable recaps, searchable and filterable score history (grouped by whole day), and always-open two-tier + secret monochrome achievements. |
| **Reset alerts** | A notification when a game's new puzzle is out, optional global and per-game streak reminders before reset, per-game on/off toggles, grouped alerts, and exact timing where Android allows it. |
| **Look** | Material 3 Expressive with Google Sans, Material Symbols icons, Dark / Light / Match phone themes, Material You wallpaper colors, custom color seeds, true-black AMOLED mode, selectable app icons (including Custom, Gold and Ruby), and compact cards. |
| **Your data** | Stored only on your phone, with no size limit. Every save is atomic and keeps a backup copy. You can export/import `.json` backups, schedule weekly folder backups, export `.csv`, and undo from Recent changes or Trash. |

---

## 2. Installing

1. Download `Dailies-<version>.apk` (e.g. `Dailies-1.9.4.apk`) from the GitHub release page.
2. Open it on your phone. Android will ask you to allow installs from your browser or file manager the first time; allow it.
3. Open **Dailies** and tap **+ Add Game**.

> **Already have a pre-1.0 2.x build installed?** Public 1.x releases are signed with the release key,
> so Android won't install them over a private 2.x build. Your data moves across with a backup:
> 1. In the old app: **Settings → Data & Backup → Save Backup (.json)**.
> 2. Uninstall the old app. This deletes its data, so do step 1 first.
> 3. Install `Dailies-<version>.apk`, then **Settings → Data & Backup → Import Backup** and pick the file.
>
> Every public release is signed with the same key, so updates install straight over the previous version.

---

## 3. Using the app

### Tab 1: Daily Hub
- **Adding a game**: tap **+ Add Game** and paste a link (or a shared `dailies://add` list link), or use **Share → Dailies** from Chrome.
  - **Or pick from popular daily games** lists the 69 built-in games by category (searchable); tap one to fill in its link, name and reset time.
  - The name, icon, category, target format/par, short description, reset time and reset time zone fill in by themselves.
  - Anything you've already typed is never overwritten.
  - **✓ Reset time from built-in list** means the time came from the catalog of popular games.
  - Pick the reset's time zone if the site doesn't say. The hint shows what that is in your time ("= 2:00 PM your time").
- **Game cards** show:
  - the icon, title and streak (🔥 7d);
  - the reset time in your local time;
  - a status pill (Ready / Done / Missed);
  - a live countdown and the action buttons.
- **Reset time check**: when a game's logged puzzle numbers don't fit its reset time (two puzzles on one day, or one puzzle on two), a banner suggests the time that fits, such as midnight New York time. **Update** changes it (moving history the same way as editing it); ✕ hides that suggestion for good. You can also turn on automatic reset-time fixes in Settings.
- **Search, sort & filter**: search games by name, site, category or notes; sort by custom order, status, next reset, streak or name; filter by **All / Ready / Done**; and optionally fold finished games under **Done today**.
- **Copy**: the button at the top of the hub copies or shares all of today's solved results, each as logged, with a divider line between them. It shows once a result is logged today.
- **Play all**: the **Play all** bar above the cards opens your unplayed games in hub order and automatically advances to the next one after each captured result.
- **Reordering & Quick Actions**:
  - Long-press a card until it lifts, then drag it to reorder (the list auto-scrolls near the edges).
  - Long-press and release without moving (or right-click on desktop) to open **Quick Actions** (edit notes, archive, move, delete).
  - With a filter on, only the visible cards swap places.
- **Playing**: **Play** opens the game player. **✓** logs a result by hand: **Solved**, **Missed**, an optional score, or **Read from screenshot**. **📋 Paste** drops in a copied share text.
- **After playing**:
  - Come back from a game you haven't logged and a sheet offers **Solved / Missed / + Score**.
  - If the puzzle reset while you were playing, the sheet first asks **which puzzle** you finished: the one you opened, or the new one.

### The game player
- **Top bar**:
  - **Hub**: go back to the hub.
  - **Game name**: tap it to switch to any of today's games (▶ playing, ✓ done, ○ not yet) without going back to the hub.
  - **After a result is saved** (copied, shared or found on the end screen), a card offers **Play \<next game\>** (opens it in the player straight away; counts down automatically in Play all mode), **Back to hub**, or ✕ to keep playing. Logging by hand after leaving the player (the **Log** button, or **Solved** / **Missed** on the return prompt) shows the same choice in the hub.
  - **Next** (⏭): the next game you haven't played, in your hub order. Each game keeps its own play time and result, all logged when you return.
  - **Shield**: shows how many requests were blocked. Tap it to allow ads on this game's site, or to block them again; the choice is remembered per game.
  - **⋮**: **Reload**, **Open in browser** and **Zoom**. Zoom (50–200%) is remembered for each site.
  - **Log**: go back and open the log form, pre-filled with any captured result.
- **Result capture** works three ways at once:
  1. **Clipboard**: anything the game copies while it's on screen is checked, and checked again when you come back to the player or close it.
  2. **Find Results Automatically** (part of `share-hook.js`): when the page shows an end screen ("Next puzzle in 05:12:44", "Expedition #53 opens in 9 h 12 m", "You solved it") with a Share / Copy button a tap could actually reach, Dailies presses that button once in a probe mode where the result text is captured and nothing is copied, shared or opened. Links and buttons for other apps or images ("Share on X", "Copy link", "Share image") are never pressed, each button at most once and at most three accepted probes per page, it stops once a result has gone out, and a real tap ends probe mode at once. It can also offer a score read from a stable end screen (`Log it / Not now`). It can be turned off in Settings.
  3. **Page hooks**: a small script (`share-hook.js`) sees `navigator.clipboard`, `execCommand('copy')`, copy events, `DataTransfer.setData`, `navigator.share`, `window.prompt` share fallbacks, and share links. Each hook calls the original and returns its result unchanged, so games behave exactly as before.
- **What counts as a result**: an emoji grid, a score like "3/6" with context, or the game's name with a count such as "#512" or "4 guesses". Links, names, live progress counters ("0/9 Fields"), and Practice/Unlimited rounds are ignored.
  - Only the game's own page (and same-site frames) can submit a result; ads and third-party frames can't.
  - The result is logged against the puzzle that was live when you opened the game.
- **Downloads**:
  - Web links download with the game's login: into Downloads on Android 10+, or through Android's **Save to…** picker on Android 7–9 (up to 25 MB). If that fails, the link goes to your browser.
  - Files the game builds itself (`blob:` and `data:` links, e.g. a "save image" button) open Android's **Save to…** picker. They can be up to 25 MB.
- **Links to other apps** (`intent://`) only open an installed app through a normal "view" request, or fall back to the web page the link names.

### Tab 2: My Results
- **Completion calendar**:
  - Pick a game, swipe the calendar or use ‹ › to change month, and **Today** to jump back.
  - Tap a day to see its result (outcome, time, play time, streak and the result text) with **Copy** and **Edit**.
  - The tiles show **Best streak**, **Current** and **Plays**.
  - **Score stats** below them: win rate, the average score, play times and personal bests. Scores are read as guesses ("3/6"), game times ("1:42", "1m 05s"), points ("2,450 pts", "4,250m", or a line that's just a number, like Krillion's "60") or counts ("45 guesses"); each game is compared in the kind it uses most.
- **Weekly & monthly recaps**: swipe between weeks or months to compare puzzles solved, win rate, best streak, best game and puzzling time.
- **Score history**:
  - Every result is listed newest first, grouped by day (keeping all entries from the same day on the same page): game, date, time, play time, outcome and streak.
  - Search every result (all words must match the result text, game name or date; matches are highlighted), and filter by game (the dropdown) and by **All / Completed / Missed**.
  - Tap an entry to expand the text the game shared, and **Copy result** to copy it with its line breaks and emoji.
  - When results span multiple pages, a pager appears: tap ‹ › or swipe on the list (it follows your finger, like the calendar).
  - Logging the same day again replaces that day's entry. Once today is logged as done, the card's ✓ is hidden; use **Edit entry** in the game's settings (⚙). Stats are recalculated from the history, so they go down as well as up.
- **Achievements**: always-open section below Score History with 19 two-tier monochrome badges and 5 secret badges, unlocking the Gold and Ruby icons and themes.

### Tab 3: Settings
Settings is a list of collapsible sections that open in place, one at a time, and close when you leave Settings:
- **Display**:
  - **Theme** dialog: Dark, Light or Match phone; Match Device Wallpaper Colors; 12 pre-made color themes (plus unlocked Gold and Ruby) + Custom accent; **Daily Random Theme** (rotates unlocked pre-made color themes daily with matching app icon); AMOLED Pitch-Black Surface (dark theme only)
  - **App Icon** dialog: Default, Theme, Midnight, 12 matching preset icons (Iris, Ocean, Forest, Sunset, Rose, Teal, Amber, Crimson, Cobalt, Sage, Plum, Slate), Mono, Custom (pinned shortcut), Brushed Champagne Gold (unlocked via achievements), and Crystalline Prism Ruby (unlocked via secret achievements)
  - Compact Game Cards, Group Finished Games (**Done today**), Countdown Font, Text Size, High Contrast, Bottom Bar Transparency, Celebration Sparkle
- **Gameplay & Time**:
  - 12h / 24h clock, Seconds in countdowns, Ask to Log After Playing, Auto-scrape on paste, Auto-fix reset times, Check game pages weekly for changes
- **Game Player**:
  - Block Ads in Games, Auto-Log Shared Results, Find Results Automatically, Dark Game Pages, Hide Cookie Banners, Pull to Refresh, Preload Games on New Reset
- **Reset Alerts**:
  - **✓ Notifications enabled** / **Allow Notifications** permission button
  - **Streak Reminder**: off, or 30 min to 6 hr before a reset (each game can override it in its settings, independently of reset alerts)
  - Per-game reset alert toggles
- **Data & Backup**:
  - **Save Backup (.json)**, **Import Backup (.json or .txt)**, **Weekly Automatic Backup** (SAF folder, newest 5 kept), **Export (.csv)**, **Share game list / QR code** (Android), **Recent changes** (undo log up to 300 entries / 60 days), **Trash** (up to 100 items / 30 days), **Check links**, **Refresh icons**, and **Storage** manager
- **Archived Games**: restore or permanently delete archived games
- **About**: version, opt-in **Check for Updates** (GitHub releases API + verified in-app APK install), **What's new**, and **Report a problem** (with local error and crash logs)

### Undo
- Auto-logged results, quick actions, manual logs and backup imports show a toast with an **Undo** button (20 seconds; 10 minutes for deleted-game restore toasts).
- **Recent changes** (Settings → Data & Backup) keeps up to 300 entries for 60 days with no time limit on undo.
- To correct an older day directly, tap that day in the **Streaks & Log** calendar.

---

## 4. How it works inside

```
┌────────────── HubActivity (hub) ───────────────┐        ┌──────── GameActivity (player) ────────┐
│ WebView: assets/index.html + app.css           │  Play  │ WebView: the game's own site          │
│   hub-core.js       (dates, resets, streaks)   │ ─────▶ │   share-hook.js → "DailesShare"       │
│   hub-ui-helpers.js (hub sort, sched, norm)    │        │   cosmetic.js   → "DailesAdBlock"     │
│   known-games.js    (67-game catalog)          │        │ AdBlockEngine / CosmeticFilters       │
│ AndroidBridge (JS ⇄ Kotlin)                    │ ◀───── │ Surrogates (stand-in ad libraries)    │
│   HubStateStore  → files/hub_state.json        │ result │ PageCache (preloaded cycle assets)    │
│   FaviconCache   → files/favicons/             │        └───────────────────────────────────────┘
│   WebsiteScraper + PageRenderer → SafeHttp     │
│   GameStore → ResetAlarms / WorkManager        │──▶ Notifier (grouped reset & streak alerts)
└────────────────────────────────────────────────┘
```

### The hub (`assets/`)
- **`index.html`** is the whole hub UI, fully offline. It talks to Android through `AndroidBridge`.
- **`hub-core.js`** holds the pure date, reset, streak, score, achievement, recap, theme and duplicate-detection logic. It's unit tested with Node.
- **`hub-ui-helpers.js`** holds testable pure UI glue helpers (`hubOrder`, `normalizeGameObject`, `dedupeHistoryLogs`, `applyScheduleChangeState`, `streakAsOfDate`, etc.), shared by the hub and Node tests.
  - **Shared test fixtures**: `schedule_cases.json` and `share_cases.json` are run by **both** the Node and the Kotlin tests. That way the notification scheduler and the hub can never disagree about a reset time or what counts as a result.
- **Time zones**:
  - Resets are kept in their own zone and converted for display.
  - **Clock goes back** (a time happens twice): the later moment is used, the same as Java's calendar.
  - **Clock goes forward** (a time is skipped): the reset moves forward to the next real time.
  - **Traveling west** (going back a day) doesn't wipe a logged result.
- **Saving**:
  - Several saves made close together are combined into one write, and flushed synchronously (including native `syncGames`) when leaving the app.
  - On startup, the newest copy (native file or browser storage) wins.
  - If saved data exists but can't be read, the hub shows a banner instead of starting empty. **Start fresh** moves the unreadable file aside (it's never deleted); **Import backup** is also offered.
- **Accessibility**: dialogs trap focus and close with Back, Escape or a tap outside. Toasts are announced by screen readers, streaks have spoken labels, and pinch-zoom works.

### Native code (`app/src/main/java/com/dailygames/hub/`)
- **`HubStateStore.kt`** saves the full JSON state with no size cap:
  - It writes a temp file and fsyncs it, the current copy becomes `.bak`, then the temp file is renamed into place.
  - Reads fall back to `.bak`.
  - A failed write is retried on the next save, and the hub shows a warning if saving keeps failing.
  - The state is flushed when the app goes to the background.
- **`SafeHttp.kt`**: all metadata and icon downloads go through it.
  - Only http and https to **public** addresses are allowed: no localhost, private, link-local or carrier NAT ranges, checked on every redirect.
  - Each download has a deadline and a size cap.
- **`WebsiteScraper.kt`** & **`PageRenderer.kt`** read a new link's title, description, icon, embedded frames and reset time, including the zone written next to it ("9 AM ET", "midnight UTC"). `PageRenderer` enforces `SafeHttp.isPublicHost` on redirects/requests and cancels cleanly on `HubActivity.onDestroy()`.
- **`FaviconCache.kt`** stores icons on the device and serves them through a reserved address (`favicon.dailes.invalid`). It backs off from icons that keep failing, refreshes icons older than 30 days in the background, and keeps the cache under 20 MB.
- **Reset & streak alerts**:
  - `GameStore.kt` mirrors each game's schedule natively.
  - `ResetAlarms.kt` uses an exact alarm where allowed. Otherwise it uses a staged inexact alarm that re-arms closer to the reset.
  - `DailyRefreshWorker.kt` (WorkManager) is the backup; `StreakReminders.kt` schedules pre-reset streak reminders for active streaks.
  - `Notifier.kt` posts grouped notifications. Each carries a private token, so other apps can't use it to open the player.
  - Alarms are re-armed after a reboot, app update, time-zone change or clock change.
- **`GameActivity.kt`** is the player:
  - edge-to-edge, with predictive back and dynamic colors;
  - fullscreen, uploads, downloads (off-main-thread SAF writes & `data:` decoding) and safe `intent://` handling;
  - its state survives Android closing it in the background (play time, captured result, confirmation flag, page).
- **Ad blocking** (`AdBlockEngine.kt`, `CosmeticFilters.kt`, `AdBlocker.kt`, `Surrogates.kt`):
  - **Hostname lists**: about 316k domains, stored as sorted signed 64-bit hashes and found by binary search. A listed domain covers its subdomains.
  - **URL rules** use a subset of Adblock Plus syntax:
    - anchors and wildcards;
    - third-party / first-party, resource-type, `domain=` (including `foo.*` entity rules) and `match-case` options, including uBlock Origin aliases;
    - `@@` exceptions and `$important`.
    - They're indexed by token, so a check takes about 40 µs.
  - **Precedence**: an `@@…$important` exception beats a `$important` block, which beats a normal exception (including `allow_hosts.txt`), which beats a normal block.
  - **Same site**: the Public Suffix List decides whether a request comes from the game's own site (loaded before `GamePrefs` lookups). The game's own site is never blocked by the big host lists.
  - **Stand-ins**: blocked Google Analytics / gtag, Google Publisher Tag, IMA video ads and AdSense are replaced with harmless scripts (for script requests), so games that call them directly don't break.
  - **Element hiding**:
    - The page reports the class and id names it actually uses, and only matching rules come back.
    - Site rules support `~domain` exclusions and exclusion-only rules.
    - Sites marked `$generichide` / `$elemhide` / `$document` in the lists are honored.
  - **Popups and redirects**: pop-ups, pop-unders and redirects to ad domains that you didn't tap are stopped.
  - **Service workers**: requests made by a site's service worker are filtered too.

### Privacy
- No accounts, analytics, ads or tracking. The app has **no server**.
- **What reaches the internet**:
  - the games you open;
  - the page of a link you add, to read its details (`WebsiteScraper` / `PageRenderer`);
  - game icons, fetched through `SafeHttp` / Google's favicon service and cached locally in `FaviconCache`;
  - optional **Check for Updates** requests to GitHub's releases API (`UpdateCheck`) and APK downloads from GitHub Releases (`UpdateInstaller`), only when enabled or triggered by you;
  - optional **weekly game-page rechecks** (`GameRecheck`, off by default, unmetered network) and **new-cycle asset preloading** (`PageCache`, unmetered network) for games you added;
  - **on-device screenshot OCR** (`ScreenshotReader`) uses Google Play services ML Kit Text Recognition; Google Play services may download the Latin OCR model on demand, and screenshots are processed locally on your device.
- Your games, history and settings stay on your phone.
  - Android Auto Backup / device transfer includes only the hub file and portable settings — device-specific preferences (SAF backup folder grant, notification prompt state, per-install notification token, crash log and recheck timestamps), game-site logins, cookies and the icon cache are excluded.
- **Permissions**:
  - `INTERNET` (loading games, scraping links, icons, opt-in updates/rechecks/preloading);
  - `POST_NOTIFICATIONS` (reset and streak reminder alerts);
  - `VIBRATE` (drag haptics);
  - `RECEIVE_BOOT_COMPLETED` (re-arm alarms after reboot);
  - `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` (on-time reset alerts);
  - `REQUEST_INSTALL_PACKAGES` (installing verified APKs from GitHub Releases when you use in-app updates).

---

## 5. Building from source

Requirements:
- JDK 17 or newer;
- the Android SDK with platform 37 (Android Studio installs it);
- Node.js 18+ (for JS tests and the web app build);
- Python 3 (only to rebuild/test the ad-block lists).

```bash
./gradlew testDebugUnitTest testHubJs lintDebug assembleDebug
node --test app/src/test/js/*.test.js
node web/build.mjs dist-web && node web/check-build.mjs dist-web
python3 tools/test_build_adblock.py
```

The debug APK is `app/build/outputs/apk/debug/Dailies-debug.apk`.

- **Android Studio**: open the folder, let Gradle sync, then **Build → Build APK(s)**.
- **Release builds** need your keystore. Without one, the build stops on purpose.

  ```bash
  export RELEASE_STORE_FILE=/path/to/dailies-release.jks RELEASE_STORE_PASSWORD=… RELEASE_KEY_ALIAS=thedailes RELEASE_KEY_PASSWORD=…
  ./gradlew assembleRelease     # → app/build/outputs/apk/release/Dailies-release.apk
  ```

  `release.storeFile` / `release.storePassword` / `release.keyAlias` / `release.keyPassword` in `local.properties` work too.
- **Tests**:

  | Command | What it runs |
  |---|---|
  | `testDebugUnitTest` | 130 Kotlin unit tests |
  | `connectedDebugAndroidTest` | 25 on-device instrumentation tests (needs an emulator or phone; CI runs them on an Android 14 emulator) |
  | `node --test app/src/test/js/*.test.js` (and `testHubJs`) | 51 Node tests (`hub-core.js`, `hub-ui-helpers.js`, `known-games.js`) |
  | `python3 tools/test_build_adblock.py` | 14 ad-block list-builder tests |

- **Refreshing the ad-block lists**: `tools/fetch_adblock_lists.sh <folder>` downloads the sources, then run `python3 tools/build_adblock.py <folder>` and rebuild. The **Update ad-block lists** workflow does this every Monday and opens a pull request (allow it under **Settings → Actions → General → Allow GitHub Actions to create pull requests**). The file names to use are in the comment at the top of the script:
  - the EasyList / EasyPrivacy component files;
  - AdGuard Base (`adguard_base.txt`);
  - HaGeZi Pro (`hagezi_pro.txt`);
  - StevenBlack (`stevenblack_hosts`);
  - the Public Suffix List (`public_suffix_list.dat`).

### Project layout

| Path | Contents |
|---|---|
| `app/src/main/assets/` | Hub UI (`index.html`, `app.css`, `hub-core.js`, `hub-ui-helpers.js`, `known-games.js`), fonts, game-page scripts, ad-block data |
| `app/src/main/java/com/dailygames/hub/` | Kotlin sources (see Section 4) |
| `app/src/main/res/` | Icons, launch theme, strings, backup / network rules |
| `app/src/test/` | Kotlin tests, Node tests (`js/`), shared fixtures (`resources/`) |
| `web/` | Zero-dependency PWA build (`build.mjs`, `check-build.mjs`, `sw.js`, `web.js`, `web.css`, manifest & icons) |
| `tools/` | Ad-block list builder and its tests |
| `design/icons/` | Material Symbols PNG sources and icon/CSS generators |
| `scripts/` | Initial-setup GitHub publishing (`publish-to-github.sh` / `.ps1`) |
| `.github/workflows/` | CI tests on every push, PWA deploy, weekly ad-block refresh, and a signed release build when you push a `v*` tag |

---

## 6. Known limits
- **Ads the blocker can't catch**: ads a game draws itself from its own server with no telltale paths, and anti-adblock walls. Use the shield for those sites.
- **System UI fonts**: some parts are drawn by Android in the phone's system font: dropdown pop-up lists, the file pickers and notification text.
- **Exact alarm timing**: Android may still delay an alert by a few minutes if exact alarms aren't allowed on your phone.
- **Alert sound**: Android doesn't let apps change a channel's sound. 1.1.0 moves reset alerts to a new channel with sound, unless you had blocked the old one or turned its sound on yourself; then your choice stays.

---

## 7. Fixed and improved in the 1.0.0 review

### Data safety
- An unreadable save file no longer starts the hub empty. A banner offers **Start fresh**, which moves the file aside without deleting it, or **Import backup**.
- Saves are atomic and keep a `.bak` copy. A failed write is retried. The state is flushed when the app goes to the background.
- On startup, the newest of the native and browser copies wins.
- Imports:
  - Importing a backup with duplicate game IDs keeps every game.
  - Re-adding a deleted game moves its old history onto the new schedule.
  - Imports are streamed and capped at 50 MB.

### Dates and streaks
- **Daylight saving**: an ambiguous fall-back time (e.g. 01:30 in New York) now resolves to the later instant, and a skipped spring-forward time moves forward. The Kotlin and JS code agree, with shared fixtures for New York, local time and Santiago.
- **Travel**: traveling west no longer marks today as unplayed.
- **Long breaks**: gaps up to 10 years fill in correctly.
- **Stats** are recalculated from your history, so undoing or re-labeling a result can lower them.
- **Time zones**: zone names are normalized to the form Java accepts.

### Logging
- **"Which puzzle?"**: if a game reset while you played, the return sheet asks which puzzle you finished.
- **Undo** has a button, a 10-minute window, and refuses after the puzzle resets.
- **Strict result check**:
  - non-breaking spaces count as spaces;
  - upper-case and underscore hosts are handled;
  - Kotlin and JS use identical ASCII rules.

### Game player
- Results are only accepted from the game's own page.
- `blob:` and `data:` downloads now work, and `intent://` links are handled safely.
- Pages that use `about:blank` or `data:` frames keep working.
- Play time and the captured result survive Android closing the player in the background.
- The ad-block choice is remembered per game, not per page the game navigated to.
- The bar follows the **Match wallpaper colors** setting.

### Ad blocker
- **Rule priority**: `$important` blocks and `@@…$important` exceptions now follow the correct order.
- **List cleanup**:
  - `$badfilter` rules cancel what they name.
  - Trailing-dot rules (e.g. `||ads.`) and conditional exceptions are no longer mistreated.
  - `$document`, `$generichide` and `$elemhide` page exceptions are honored.
- **Hosts and sites**:
  - URLs with `user:pass@` and IPv6 hosts are parsed correctly.
  - "Same site" uses the full Public Suffix List.
- **Request types**:
  - `$ping` and `$websocket` rules no longer hit ordinary fetches.
  - `$~xmlhttprequest` rules no longer hit requests of unknown type.
  - JSON requests are recognized as XHR.
- **Stand-ins**: stand-in scripts for common ad and analytics libraries stop games stalling.
- **Element hiding**:
  - Hiding rules can exclude subdomains, and exclusion-only rules work.
  - Embedded frames don't get the heavy generic CSS.
  - The page script stops when blocking is off, and restores its styles if a page removes them.

### Security and network
- **Downloads**: scraper and icon downloads can't reach private or local network addresses, and every redirect is checked.
- **Notifications**: links carry a private token.
- **Backups**: Android's backup includes only the hub file and settings, never game cookies.
- **Exact alarms**: alerts use exact alarms where allowed, with a staged fallback.

### Interface
- **Dialogs**: they stack and close correctly, and trap focus. Back, Escape and a tap outside close them.
- **Filter tabs and editing**:
  - The filter tabs show which one is selected.
  - Editing refuses a URL that's already in the hub.
  - Unknown categories are kept.
- **Compact cards** fit 360 px screens.
- **Other fixes**:
  - Pasting a link scrapes it once.
  - Opening a deleted game from a notification shows a message.
  - Keyboard focus rings are visible.
  - Small text is bigger.
- **Performance**: less re-rendering, cached countdown maths and cached calendar dropdowns.

## 8. Fixed in 1.0.1

1.0.1 fixes the problems found in a review of 1.0.0. The full list is in [RELEASE_NOTES.md](RELEASE_NOTES.md). Notes for developers:
- `release.yml` now fails if the pushed tag doesn't match `versionName`, and lint errors fail the build (`abortOnError = true`).
- `SafeHttp` connects directly (no proxy) to the address it checked, so DNS rebinding can't reach the local network; IPv4 addresses embedded in IPv6 (NAT64, 6to4) are checked too.
- The zone picker only offers zones that Android's own time-zone database knows, so alerts and the hub always agree on the zone.
- New tests: `BackgroundFixesTest`, `GamePlayerPolicyTest`, `SurrogatesTest`, more `HubStateStore` cases, and new schedule and share fixtures shared by the Kotlin and Node tests.

## 9. New in 1.1.0

The user-facing changes are in [RELEASE_NOTES.md](RELEASE_NOTES.md). Notes for developers:
- **New Kotlin files**: `StreakReminders.kt` (planner, alarm, receiver, "Streak Reminders" channel), `AutoBackup.kt` (folder access via the Storage Access Framework, daily WorkManager check, weekly write, pruning) and `UpdateCheck.kt`.
- **Bridge methods**: `setThemeMode`, `setStreakReminder`, `chooseAutoBackupFolder`, `disableAutoBackup`, `getAutoBackupStatus`, `runAutoBackupNow`, `checkForUpdate`, `openReleasePage`. The hub also sends `loggedCycle` per game so reminders skip games already logged.
- **Light theme**: `html[data-theme="light"]` overrides at the end of `app.css`; native screens read `ThemePrefs.isLight()`. The wallpaper palette now carries light tones under `palette.light`.
- **Alert channel**: reset alerts post on `reset_alerts` (with sound). `Notifier.channelMigration()` decides once whether to keep the old silent `daily_game_refresh` channel.
- **SafeHttp** uses the phone's HTTP proxy when one is set; it still refuses local names and every spelling of a local IPv4 address (`proxiedHostAllowed`).
- **Popups** get network and element-hiding ad blocking judged against the popup's own site.
- **Centring**: Google Sans's uneven font padding is turned off on native labels (`includeFontPadding = false`); hub fixes are in the "Optical centring" block of `app.css`.
- **CI**: a `device-tests` job runs `connectedDebugAndroidTest`; `release.yml` also checks `APP_VERSION` in `index.html` and publishes only the newest section of the release notes. Lint is at 0 warnings.
- **Store metadata** for F-Droid / Obtainium is in `fastlane/metadata/android/en-US/`.
- New tests: `V110FeaturesTest`, proxy tests in `SafeHttpTest`, `scoreStats` in the Node tests, and `HubSmokeTest` on device.

## 10. New in 1.2.0

The user-facing changes are in [RELEASE_NOTES.md](RELEASE_NOTES.md). Notes for developers:
- **Play time**: `onReturnFromGame`'s elapsed time is added to `game.playTime {key, ms}` for that puzzle and saved on the log row as `timeMs`.
- **Stats logic** in `hub-core.js` (Node-tested): `parseScore`, `formatDuration`, `newRecords`, `personalBests`, `computeAchievements` / `perfectRun`, `monthlyRecap`, `extractResultText`. Earned badge ids are kept in `state.achievements`, so only new ones get a toast.
- **Archive**: `game.archived`. Archived games are left out of the hub, KPIs and alert list, synced with alerts off, and skip roll-overs (no auto-missed days). Restoring treats today like a newly added game.
- **Bulk edit** reuses `applyScheduleChange`, so history moves with a new reset time exactly as in the edit dialog.
- **Game lists**: `GameListCodec` makes `dailies://add?v=1&d=<deflate+base64url>` links and QR codes (ZXing). MainActivity opens them from a VIEW intent, a shared message or a paste into the Add Game box.
- **Screenshots**: `ScreenshotReader` (ML Kit text recognition through Google Play services) runs on an image shared to Dailies or picked from the log dialog. This is the one non-free dependency; an F-Droid build would need a flavor without it.
- **Dark game pages**: `WebSettingsCompat.setAlgorithmicDarkeningAllowed`. The game player uses `Theme.Dailies.Light` in the light theme so pages get `prefers-color-scheme: light`.
- **Tab swipes**: the page follows the finger, and `animateTabChange` slides between views with the Web Animations API (with a timeout fallback). Reduced-motion settings are respected.
- **App icon**: adaptive icons in six launcher entries (`activity-alias`). `.MainActivity`, the 1.x launcher name kept so home-screen shortcuts survive the update, is the default: it uses `-night` and `-v31` colors, so it follows the phone's dark theme and wallpaper. With **App Icon Matches Theme** on, `AppIcon` enables the entry for the app's theme when you leave the app. The activity itself is now `HubActivity`.
- New tests: `AppIconTest`, `GameListCodecTest`, Node tests for the stats helpers, and device tests that tap the Done-today row with real touch events and open a shared list link.

## 11. New in 1.3.0

The user-facing changes are in [RELEASE_NOTES.md](RELEASE_NOTES.md). Notes for developers:
- **In-app updates**: `UpdateInstaller` fetches the latest release again (the page can't pass a URL), downloads its `.apk` asset from this repository's releases (redirects only to GitHub hosts), checks the package name, a higher version code and the same signing certificate as the installed app, then commits a `PackageInstaller` session. `UpdateInstallReceiver` shows Android's confirmation; progress reaches the page through `window.onUpdateStatus`. Needs `REQUEST_INSTALL_PACKAGES`.
- **Auto-logging**: `isStrictShareResult` (JS and Kotlin, same fixtures) also accepts results that name the game (or the site's own name, e.g. "krillion" for krillion.io) with a measured score ("4,250m", "87%", "1,200 XP") or emoji plus numbers. The share channel accepts same-site frames, the gesture window is 30 s, and a clip copied while the game was open is checked again when the player regains focus or closes (using the clip's timestamp, Android 8+).
- **Tab pager**: `Pager` places the real neighboring view beside the current one (absolutely positioned, `overflow-x: clip` on the scaffold) and both follow the finger; the release velocity sets the glide duration. No cloned snapshots.
- **Calendar**: a day opens `modalDayEntry` (read-only); its Edit opens the log editor. Month swipes are handled on `#calendarSwipeArea`, which the tab swipe ignores.
- **Removed**: the guess distribution (`scoreStats().dist`) and one-handed mode.
- **Icon**: the default launcher entry `.MainActivity` uses `ic_icon_default_*` (white, gradient star). The previous wallpaper-following icon is the `.IconAuto` entry, used when **App Icon Matches Theme** is on with the Match phone theme.
- **Play next**: `nextGameToPlay()` picks the next game with status `ready` after the one opened last (hub order, wrapping). The bar shows once a game is finished today.
- **Score history filter**: `historyFilter` (game key `id:<id>` or `name:<name>`, outcome) is kept in memory; copy buttons find their row by game and date.
- **Per-game reminders**: `game.reminderMinutes` (-1 = app setting, 0 = off, or 30/60/120/180/360) is synced to the native side; `StreakReminderPlanner.leadFor` uses it per game.
- **Scores**: `parseResultValue` in `hub-core.js` returns guesses, time, count or points with a comparable `rank` (lower is better); `personalBests`, `newRecords` and `scoreStats().avgText` use the group a game scores in most.
- **Stats rendering** is skipped when nothing changed (`statsRenderKey`, bumped by `saveState`).

## 12. New in 1.4.0

The user-facing changes are in [RELEASE_NOTES.md](RELEASE_NOTES.md). Notes for developers:
- **Finding results**: the probe lives inside `share-hook.js`'s closure (`__DAILIES_FIND__`), so a page can't switch probe mode on. Every message carries a per-player key (`__DAILIES_NONCE__`, filled in by `GameActivity.shareHookScript`) that the page can't read; the native listener drops messages without it. Before pressing, the hook posts `{kind: "press"}`: for 2.5 s the player refuses new windows, other apps, downloads and main-frame navigations (`probeGuarded`), and a probe result is accepted only within 3 s of the press and once per page. In probe mode the hooks report the text with `via: "probe"` and don't call the real clipboard / share / `window.open`. Buttons must be in the viewport, not under `aria-hidden` / `inert`, and the element `elementFromPoint` hits at their center. The probe doesn't run in other sites' frames.
- **Game switching**: the hub passes today's games as `EXTRA_LINEUP` (`openGameInLineup`). `GameActivity.switchToGame` keeps one WebView: it stores the current game's play time and result in `earlierResults`, resets the per-game state (timer, captured result, ad-block choice, blocked count) and loads the next game; history is cleared after it loads. On return the hub gets `EXTRA_EARLIER` and runs `onReturnFromEarlierGames` (play time + auto-log, no prompts) before the usual `onReturnFromGame`. The current game, lineup and earlier results survive a recreate.
- **Zoom**: `GamePrefs.siteZoom` per registrable site, applied as CSS `zoom` on the root element at commit and finish, so it works on pages that disable pinch-zoom.
- **Reset times**: new log rows of the current puzzle carry `loggedAt`. `HubCore.puzzleNumber`, `resetFitsObservations` (puzzle number − day must be constant) and `suggestResetTime` (common midnights first, then a whole hour when only one or two fit) drive the banner; a dismissed suggestion is kept as `game.dismissedResetSuggestion`.
- **Search**: `historyFilter.query`; every word must appear in the row's score, game name, date or outcome.
- **Hub logo**: `html[data-app-icon]` (`default` / `themed`) switches the header SVG's colors in `app.css`.
- New tests: `GamePlayerTest` (Next, earlier results, zoom prefs) on device; puzzle numbers and reset suggestions in the Node tests.
- **Catalog**: `known-games.js` has 59 games; zones can be IANA names. Reset times that aren't verified are corrected over time by the reset-time check. The picker opens `entry.url` or `https://host/path`.
- **More share formats**: the strict check also accepts a solve time ("0:42", not "9:14 AM") and counts in words / clues / hints / strokes when the text names the game; emoji plus a number needs a puzzle number. The probe also treats a countdown to the next puzzle ("Next puzzle in 05:12:44") and phrases like "Puzzle solved", "flawless" as an end screen.
- **App icon styles**: `AppIcon.style` ("default", "themed", "midnight", "sunset", "ocean", "mono", "gold"), set by `setAppIconStyle`; launcher entries `.IconMidnight` … `.IconGold` with adaptive icons and pre-26 PNGs. The 1.2–1.3 "matches theme" switch maps to "themed". The hub draws the same icons (`appIconSvg`) for the picker and its logo, and only offers Gold when `state.achievements` holds every id. Previews: `design/icons/app-icon-styles.png`, `app-icon-gold.png`.
- **Play-next prompt**: `GameActivity.showDonePrompt` after a captured result (`offerShare`); in the hub, `offerNextGame` after a log that came from the player (`logOpenedFromPlayer`, or the return prompt's Solved / Missed). Both pick the next `ready` game after the current one in hub order. Device test: `aCapturedResultOffersPlayNextOrBackToHub`.

### Review fixes before release
- **Strict detector** (`isStrictShareResult` / `ShareResultDetector`): matched on an ASCII-lower-cased copy with case-sensitive patterns and one explicit trim set, so JS and Kotlin agree (a 600,000-case differential fuzz differs only on malformed links). One result glyph needs the game's name or a `#NN` puzzle number (two side by side, or keycaps, are a grid). A fraction needs the game or a game word, `n ≤ max` and `max ≥ 3`. `gameMention` grades how clearly the text names the game: 2 = a name of several words, a line starting with the name and a number ("Zip #245"), or the game's own site; 1 = a one-word name as a word, the site's name, or a link on a site shared by several games (path names the game). Counted / timed / measured results named only in passing also need a `#NN` or "solved". `share_cases.json` has 257 cases, including ordinary copied text that must not log. The clipboard-late path also requires `mentionsGame`.
- **Clipboard**: clip timestamps are wall-clock (`System.currentTimeMillis`), compared with `openedAt`; switching games records the current clip's stamp so the old game's copy isn't credited to the new one.
- **Switching**: `switching` from `switchToGame` until the new page's `onPageCommitVisible` (15 s fallback); offers from the old page are dropped, and the last tap is forgotten. History is cleared at commit and again at the new page's finish. `earlierResults` holds one entry per game.
- **Reset suggestions**: `suggestResetTime` needs 4+ rows of 3+ puzzles over 2+ days, the current schedule contradicted on 2+ days, the results pinning the reset to ≤ 90 min, and every fitting candidate being the same schedule for a year (`scheduleSignature`); a single midnight wins over other hours. `rescheduleDays` moves rows with `loggedAt` to their day under the new schedule and clamps keys to today; `applyScheduleChange` uses it.
- **Backups** carry `achievements` and `goldUnlocked` (sticky once earned); import unions them.
- **Catalog**: entries can list `also` addresses (Tradle, Octordle, Quordle). Whole-site entries no longer match subdomains, and only path entries (or ones with `also`) make two links the same game, so sibling games on one site can be added. `cleanGameUrl` stores `URL.href`.
- **Launcher icon**: `HubActivity` skips `AppIcon.apply` in `onStop` while one of its own screens covers it (`startActivityForResult` sets `coveredByOwnScreen`).

## 13. New in 1.5.0

The user-facing changes are in [RELEASE_NOTES.md](RELEASE_NOTES.md). Notes for developers:
- **Achievements** (`hub-core.js`): 19 achievements, two tiers each (`ACHIEVEMENTS[].tiers`, each `{ desc, metric, target, below? }`). `achievementMetrics` computes the numbers (solves, distinct games solved, streak, perfect run, variety, two-guess and first-guess solves, fastest time, games tracked, early-bird / night-owl solves from `loggedAt` and each game's reset, personal bests, clean-sweep days with 3+ games, bounce-backs, solved days, play time, weekends with both days solved). `computeAchievements` returns each one's earned `tier`, its earned `keys` and the `next` tier with progress.
- **Earned tiers** are kept in `state.achievements` as keys such as `roll#2` and are never taken away. `migrateAchievementKeys` turns 1.4.0's one-badge ids into tiers (`solves100` → `started#1`, `started#2`, `streak365` → `centurion#1`, `centurion#2`, …) on load and on import. `checkNewAchievements` toasts each new tier.
- **Gold unlocks** are sticky flags, kept in saves and backups (manual and weekly), so later versions that add achievements never take them away: `goldUnlocked` (Gold app icon: every `TIER1_KEYS`) and `goldAccentsUnlocked` (Gold Accents: every `ALL_TIER_KEYS`). `settings.goldAccents` turns the accents on; `applyThemeAndSettings` then sets `html[data-gold="true"]` and swaps the `--md-primary` family for gold (separate light and dark values; AMOLED keeps its black surfaces), and `app.css` adds gold trim.
- **Folding cards**: any card with `data-fold` shows only its heading until opened (`initFolds`; descriptions, buttons and the clock in a heading are `.fold-extra` and hidden while folded). All start folded; opened ones stay open until the app closes (`openFolds`). `revealFold(el)` opens the card around an element before scrolling to it. Used by the Achievements card and every Settings card.
- **Removed games**: `retainedGameStats` entries carry `removedAt`; `pruneRetainedMap` keeps the 5 most recently removed games (older saves are dated by their last played day). Add Game lists them (`renderRemovedGames`); picking one fills the form, and saving restores its history through the existing link match.
- **Reset alerts**: `DailyRefreshWorker.runRefresh` collects a pass's ready games and calls `Notifier.postResetNotifications`. One game, with nothing unread in the shade: its own notification, as before. Otherwise one notification (`COMBINED_ID`, `InboxStyle`, `combinedText`) lists every ready game, merging names from alerts still in the shade (`mergeReadyNames`; single alerts carry their name in an extra) and canceling those. It opens the hub.
- **Tab scroll position**: `switchNavigation` and `Pager.attach` keep the scroll fraction (`scrollFraction`): the incoming page is placed at the spot it will have once current, and `jumpScroll` sets it without smooth scrolling.
- **Gold accents styling**: `--gold-shine` (the icon's metallic gradient) on filled buttons, tonal pills, chips, bars, mask icons and solved calendar days; `--gold-text` as gradient text on the title, card headings and stat values; `--gold-edge` as a gradient border (padding-box / border-box backgrounds) on cards and the dock. Native checkboxes use a solid gold `accent-color`.
- **Easter egg**: the Achievements card doesn't say what the gold unlocks need.

## 14. New in 1.5.1

- **Folding**: Settings cards carry `data-fold-default="open"`; `foldState` remembers a choice until the app closes. Achievements still start folded.
- **Bottom bar highlight**: `#navIndicator` (one pill under the three buttons). `NavIndicator.set(a, b, t)` places it between two tabs; `Pager.follow` moves it by the swipe's fraction and `Pager.settle` animates it with the page's duration and easing.
- **Finding results**: a button whose label reads "Copy/Share result(s)/score/stats/grid" counts as an end screen by itself (`RESULT_BUTTON_RE`), and clickable divs/spans (`[onclick]`, `*[class*=btn]`, `div[tabindex="0"]`) are candidates too (Krillion).
- **Scroll bars**: hidden in CSS and on both WebViews (`isVerticalScrollBarEnabled = false`).
- **Achievements**: tiles are buttons of one size (`grid-auto-rows: 1fr`); `openAchievementDetails` shows each tier (`tierList`) in `modalAchievement`. A tier can carry `scale` / `unit` (Marathon: hours, rounded).
- **Themed drop-downs**: `themeSelect(sel)` puts a button after a `<select>` and opens `.tsel-menu`; picking sets the select and fires `change`. The select's `value` / `selectedIndex` setters and a MutationObserver keep the label current. Used for `#calendarGameSelect` and `#historyFilterGame`; Back closes the menu.
- **Gold palette**: a satin gold (`#E4CC7E`, `#DBC377`, `#D1AB48`, `#C7A042`, `#AF8A39`, `#987841`, `#64512E`; ink `#1C1607`) in `--gold-shine` / `--gold-text` / `--gold-edge`, the theme override, `.ach.max` and the Gold launcher icon (vectors and PNGs regenerated).

## 15. New in 1.6.0

- **Play all**: `playAll()` sends the unplayed games (hub order, starting at the next one) to `AndroidBridge.playAllGames`, which starts the player with `EXTRA_PLAY_ALL`. In that mode the done card counts down `AUTO_NEXT_SECONDS` on its Play button (`startAutoNext`) and switches game; Copy, Keep playing, a touch outside the card or leaving the player stops it (`stopAutoNext`).
- **Recent changes**: `state.recentChanges` (max `CHANGES_MAX` = 25, never in backups). `recordChange` stores `log` entries (with `snapshotForUndo`'s snapshot), `remove` (the whole game and its index) and `archive` entries. `undoChange(id)` restores them. `restoreUndo` has no time limit: while the same puzzle is live it restores status and score too; after a reset it restores only that day's record. Undoing a day also marks later changes to that day as undone.
- **Recaps**: `HubCore.periodRecap(logs, from, to)` (the monthly recap now uses it), `weekBounds` (Monday to Sunday) and `recapChange`. `drawRecapCard` draws a 1080×1350 canvas from the current `--md-*` tokens, and `AndroidBridge.shareImage` checks the PNG signature, writes it to `cache/shared/`, and shares it through the `FileProvider` (`${applicationId}.files`).
- **Personal bests**: `recordGameOutcome` stores `row.records` (from `newRecords`), which shows as 🏆 PB on the card and in history. `renderPersonalBests` lists `personalBests` for every game.
- **Tough days**: `HubCore.toughDays(rows)` works on the game's dominant score kind and needs 5 results. A day is tough at guesses ≥ median+2, time or count ≥ 1.75× median, or points ≤ half the median. At most the worst 15% of days are kept.
- **Sorting**: `settings.hubSort` and `HubCore.sortGames`. `hubOrder()` feeds the grid, Play next, the player's lineup and today's results. `adoptSortAsCustom()` runs when a card is dragged or moved while sorted.
- **Combined share**: `HubCore.combinedShareText(entries, {dateLabel, total, grids})`. `isGridLine` drops emoji grids when grids are off, and the game's name is added with `mentionsGame`.
- **Color themes**: `settings.themeSeed` (iris, ocean, forest, sunset, rose or custom) with `settings.customAccent`, chosen when `dynamicWallpaper` is off. `HubCore.themeTokens(seed, light)` makes the `--md-*` roles in OKLCH (chroma lowered until in sRGB). They're cached in `localStorage.dailydeck_theme_tokens_v1` for the first frame and sent to the player with `setPlayerColors` (`ThemePrefs.playerColors`; gold accents send their colors too).
- **Toasts**: `showToast` builds `.app-toast` (`tone-ok` / `tone-info`) colored from `--md-primary` / `--md-primary-container`, with AMOLED, light and gold (gold edge and title) variants. `.status-completed` and the Done ★ use the theme's primary too.

## 16. Web app (PWA)

The hub also runs as an installable web app on GitHub Pages; see `web/README.md`. The page is the same `index.html`: with no `window.AndroidBridge`, `IS_WEB` is true, `<html>` gets the `web` class, `.android-only` elements are hidden and `.web-only` ones shown (the **Paste** button in the "Finished?" sheet). Games open through `window.DailiesWeb.openGame` (from `web/web.js`), which reports the return with `window.onReturnFromGame`. `web/build.mjs` builds `dist-web/`; CI checks the build, and `pages.yml` deploys it.

## 17. New in 1.6.1

- **Dialog lock**: `syncBackInterceptState` (which sets `html.modal-open` and Android's Back interception) now runs from `openModal` / `closeModal` and watches every id in `MODAL_IDS`; before, the achievement details, Today's results and Recent changes weren't watched, so the page stayed locked after they closed. `HubSmokeTest.everyDialogUnlocksThePageWhenItCloses` opens and backs out of each one.
- **Color themes**: `COLOR_THEMES` no longer has Wallpaper. `themesLocked()` (Android, wallpaper colors on) grays out `#themeChoices` and shows `#themeLockedHint`; `savedThemeId()` is the theme that applies once wallpaper colors are off.
- **Bottom bar**: `.m4-floating-dock` is 78% opaque with a 6 px backdrop blur (AMOLED, light and gold too).
- **Title**: `<title>Dailies</title>` (the web app's tab).

## 18. New in 1.6.2

- **Missed reset alerts**: re-arming the alarm (`scheduleNextReset`) replaces the pending one, so a games-list save just after a reset (the hub's own rollover) pushed the alarm a day on and that reset was never processed. `DailyRefreshWorker.refreshAndSchedule` (used by the hub's sync and app start) runs `runRefresh` first when `GameRepository.hasUnannouncedReset()` finds a game past its last announced cycle. Tested in `DailyRefreshProcessorTest`.
- **Alert icon**: `Notifier.withAppIcon` tints the small icon with `AppIcon.accentColor` and sets the chosen launcher icon (`AppIcon.chosenIconBitmap`, from the activity alias) as the large icon; used for reset alerts, the combined alert and streak reminders. Device test `resetAlertsLookLikeTheChosenAppIcon`.
- **Toasts**: `enableToastSwipe` (sideways or up, with a fling); the tab pager ignores touches in `#toastContainer`.
- **Sticky hub header**: `#viewDashboard > .hub-sticky` (`position: sticky`, solid surface, covering the status-bar area). Card drag auto-scroll starts below it.
- **Smoothness**: `queueTabPrerender` redraws Streaks & Log while idle after a change; folded cards (`renderWhenShown`) draw when idle or opened; the hub skips redrawing cards when `dashboardRenderKey` is unchanged; off-screen Stats / Settings cards use `content-visibility: auto`.
- **Preloaded games**: `PageCache`. On a new cycle (`NotificationDispatcher.onCycleStarted`), `PageCache.enqueue` deletes the old files and queues `PreloadWorker` (unmetered network, battery not low): it fetches the page, then up to 40 scripts / stylesheets / preloads / stylesheet fonts (4 MB each, 16 MB per game, 80 MB in all), skipping what the ad blocker would block and `no-store` / `private` responses, and keeps CORS headers. The player serves them in `shouldInterceptRequest` after the ad-block check (the page itself always comes from the network; a CORS request without a stored allow-origin header goes to the network). Files expire at the next reset; the hub calls `gameFinished(id)` when a game is logged or removed. Setting: `preloadGames` → `setPreloadGames`.
- **Bottom bar**: 63% opaque.
- **Gold palette**: satin champagne gold sampled from a reference logo (highlights `#DCC993` / `#D6C38F`, main `#C5AE6C`, mid `#BAA465` / `#A28F57`, shadows `#8B7D54` / `#5F5336`; ink `#1B160A`). Every gold value was moved to the same lightness at hue 91° with less chroma (OKLCH), in the `data-gold` CSS, the gold theme roles, the recap card, the Gold icon (vectors and PNGs) and `AppIcon.accentColor`.
- **Gold icon**: satin, finely grained gold Dailies star with a crisp bevel (an SVG filter: light band where the shape ends toward the top left, dark toward the bottom right), cut-outs that show the background, a soft shadow, on near-black with warm corner glows. One design (`design/icons/gold-icon.js`) renders the adaptive-icon layers (`mipmap-*/ic_gold_fg.png`, `ic_gold_bg.png`), the legacy PNGs and `goldIconSvg` in `index.html`, which the hub uses for the picker and logo. `--gold-shine` has a bright specular band (`#F2E6BC`) between deep shadows.

## 19. New in 1.7.0

- **Secret achievements**: `HubCore.SECRET_ACHIEVEMENTS` (one tier, key `secret:<id>`, kept in `state.achievements`): `secretMetrics` (first in line within 5 min of a reset, 2–4 AM, last-guess solves, 5 games before 8 AM, 2 h of play in a day; the 7-day challenge streak secret was removed in 1.8.2). Shown as "???" tiles until found.
- **Ruby**: every secret found → `state.rubyUnlocked` (sticky, in backups). Ruby icon (`.IconRuby`, `ic_ruby_fg/bg`: a faceted gemstone star from `design/icons/ruby-icon.js`, which also writes `rubyIconSvg` into the hub) and Ruby accents (`settings.rubyAccents`, `html[data-ruby]`); the ruby CSS is generated from the gold rules by `design/icons/ruby-css.js`, which then adds a gemstone finish (hard-edged facets under a glassy highlight, white text on filled buttons). Gold and Ruby accents are exclusive.
- **Daily challenge**: removed in 1.8.2 (see section 22).
- **Display**: `settings.cardStyle` (`html[data-cards]`), `countdownFont` (`data-cdfont`), `textSize` (`setTextZoom` on Android, CSS zoom on the web), `highContrast` (`data-contrast`).
- **Custom icon**: `settings.customIcon {star, bg}`; `customIconSvg` in the hub; `pinCustomIcon` / `saveCustomIcon` save a 432 px adaptive PNG (`AppIcon.saveCustom`, used for alerts) and request a pinned shortcut (`ShortcutManagerCompat`). The launcher entry stays the default.
- *(Single-game backup restore was removed from Settings in 1.8.7 and cleaned up completely in 1.9.6.)*

## 20. New in 1.8.0

- **Hub search**: `#btnHubSearch` / `#hubSearchRow`; `hubSearch` filters `renderGamesGrid` by name, site, category and notes (`hubSearchMatches`), unfolding "Done today" while searching.
- **Quick actions**: in `initCardDragReorder`, a lifted card let go without moving (`drag.moved` false) opens `openGameActions(id)` (`#modalGameActions`); the touchend is cancelled so its tap doesn't close the dialog. Sorting is adopted as the custom order only once a drag actually moves. Right-click (`contextmenu`) opens it too. `game.notes` (≤ 2000 chars) is a new game field.
- **Trash**: `state.trash` (on this phone only, not in backups, ≤ 100 items, 30 days: `pruneTrash`). `kind: 'game'` (from `confirmDeleteGame`, linked to its Recent-changes entry) and `kind: 'forgot'` (from `forgetRemovedGame`, with the retained snapshot and its log rows). Restore uses `restoreRemovedGame`; deleting a game for good also drops its retained stats and log rows.
- **Recent changes**: `CHANGES_MAX` 300, entries older than `CHANGES_KEEP_DAYS` (60) dropped; `#changesSearch` matches every word against the label, game, details and day.
- **All done**: `checkAllDoneCelebration` (from `renderPlayNext`) compares with the state at first draw, so opening the app never celebrates; `celebrateAllDone` shows `#celebrate` with `appIconSvg` and sparks (gold / ruby tones), and `#hubLogo.all-done` twinkles. `settings.celebrate`.
- **Reset times**: `checkAllResetSuggestions` runs once idle after start; with `settings.autoFixReset`, `checkResetSuggestion` applies the suggestion (`autoApplyResetSuggestion`, Undo restores the old schedule and dismisses that suggestion).
- **Game pages**: `assets/game/cookie-hide.js` (CSS for the common consent tools, and their scroll locks) and `assets/game/pull-refresh.js` (decided in the page: only at the top, not on canvas / SVG / inputs / touch-action elements, and not if the game called `preventDefault`), added as document-start scripts when `GamePrefs.hideCookies` / `pullRefresh` (bridge `setPlayerPrefs`).
- **Links & icons**: `GameHealth.checkLinks` (SafeHttp, 4 at a time; `classify`: dead / error / unreachable / moved / blocked) and `GameHealth.refreshIcons` (scraped icon and `/apple-touch-icon.png`, kept only if wider than the current one; `FaviconCache.forget`). Bridge `checkLinks` / `refreshIcons` reply to `window.onLinksChecked` / `onIconsRefreshed`.
- **Storage**: `GameHealth.storageInfo` (pages, icons, web, updates, temp; plus data) and `clearStorage` (the WebView cache via `clearCache(true)` on the main thread).
- **Report a problem**: page errors in `localStorage['dailies_error_log_v1']` (last 40; links replaced by `<url>`), captured by the first script in `<head>`; `CrashLog` (uncaught exceptions to `files/crash_log.txt`, hub renderer crashes, Android 11+ crash/ANR exit reasons). `errorReportText()`; `openIssuePage` only opens this repository's new-issue page.
- **What's new**: `WHATS_NEW[version]`, `state.lastSeenVersion`; `maybeShowWhatsNew` shows it once after an update (not on a fresh install).
- **Start-up**: the all-time-zones list is built when idle or when the Add/Edit form needs it (it was about half of the start-up script time on a phone); missed-reset catch-up and backup scheduling run off the main thread (`DailyRefreshWorker.refreshAndSchedule` is now synchronized).

## 21. New in 1.8.1

- **Score History**: `historyRowHtml` draws each result as a `details.hist-row` (a plain `div` when there's no result text) inside a `.hist-day` group; `historyDayLabel` gives Today / Yesterday / the date (year only when it isn't this year); `historyPreviewLine` is the first line with a letter or digit, minus the game's name. The day count uses all of that day's results for the chosen game (not the outcome filter or search). `HISTORY_PAGE_SIZE` is 8; the page dots are gone.
- **Start-up**: `currentAchievements()` caches `computeAchievements` by `stateRev` and the games / log arrays; the silent start-up `checkNewAchievements(true)` runs when idle. `populateZoneSelect` trusts `Intl.supportedValuesOf('timeZone')` instead of building a formatter per zone. `checkAllResetSuggestions` runs once after start, one game per idle callback.
- **Tab pager**: `Pager.attach` reads every measurement of the current page before showing the new one; a tapped slide starts after two animation frames (`tabSlidePending`), so the new page's first paint happens while it is still off to the side.

## 22. New in 1.8.2

- **Reset alerts**: the streak reminder had the same notification id as the combined reset alert (`0x0DA1E6`), so `StreakReminders.schedule()` (run after every reset pass) cancelled the combined alert as soon as it was posted. Fixed ids are now `Notifier.SUMMARY_ID` 0x0DA1E5, `Notifier.COMBINED_ID` 0x0DA1E6 and `StreakReminders.NOTIFICATION_ID` 0x0DA1E8, kept unique by `NotificationIdsTest`; `ResetAlertDeviceTest` runs a whole pass on a device. `runRefresh` always arms the next reset (`finally`), and a failure to post is written to the crash log.
- **Launch**: `HubActivity.holdLaunchScreen` blocks the activity's drawing (an `OnPreDrawListener`) so the launch screen stays up while the page loads; `revealHub` (from `markPageReady`, i.e. `onHubReady`) releases it, and a surface-coloured cover stays over the WebView until `WebViewCompat.postVisualStateCallback`. At most `MAX_LAUNCH_HOLD_MS` (3 s). `LaunchDeviceTest`.
- **Tab pages**: `.tab-page.hidden` keeps pages off screen laid out (absolute, height 0, `overflow: clip`) instead of `display: none`; they are `inert` and `aria-hidden` (set in `switchNavigation`). `#m3Scaffold` is `position: relative; overflow-x: clip` for good. The Streaks / Settings cards no longer use `content-visibility: auto`. `Pager.attach` reads the new page's height (`scrollHeight`) while it is still hidden, and measures the current page's content top without the sticky header's collapsed negative margin (Streaks & Log used to slide in too high and drop into place). `queueTabPrerender` also redraws the hub's cards while it is off screen.
- **Daily challenge removed**: the card, `settings.dailyChallenge`, `state.challenge` / `challengeDays`, `HubCore.pickChallenge` / `challengeStreaks` and the `challenger` secret. Ruby needs the five remaining secrets; `state.rubyUnlocked` stays sticky.

## 23. New in 1.8.3

- **Adding a game**: `PageRenderer` renders the pasted link in a WebView behind the hub (no bridge, no file access, no images, http(s) only, `RENDER_TIMEOUT_MS` 8 s, `onRenderProcessGone` contained), only for a public host (`SafeHttp.isPublicHost`), in parallel with the plain fetch; `EXTRACT_JS` returns the title, meta tags, JSON-LD and text (with hidden help / rules sections). `WebsiteScraper.fetchAndScrape(url, rendered)` also reads the manifest and up to three of the site's own scripts (`scriptZoneHint`: an Intl `timeZone` option or `setUTCHours(0, 0…)`, used only when the page names no zone) and parses with `PageExtras`. `jsonLdInfo`, `pickShortDescription` (a how-to-play sentence, ≤ 90 characters), `detectMetric` (null when there's no clue). The hub waits 24 s for a scrape.
- **Score format**: `HubCore.learnScoreFormat(rows)` / `isGenericScoreFormat`; `checkScoreFormat(game)` after each result (`recordGameOutcome`), with Undo (`game.scoreFormatDeclined`). Not for built-in games or typed formats.
- **Finding results** (`share-hook.js`): `RESULTS_OPEN_RE` (press "See results" / "Stats" once on a finished game, then rescan), `shareLinkText` (window.open and `<a>` share links), `deepQueryAll` / `deepText` (open shadow roots), `FINISHED_INTL_RE` and the multilingual `BUTTON_RE`, `screenResultLine` → `via: "screen"`. `GameActivity.captureShareLink` (`ShareLinkText`, also for navigations and popups), `offerScreenResult` (a Log it / Not now card; `EXTRA_SHARE_CONFIRMED` makes the hub skip its strict check).
- **UI**: every `<select>` uses `themeSelect`; `#btnPlayAll` is the only bar above the games; no Select button, Personal Bests, tough days or Settings → Recent changes; recap boxes swipe (`.recap-swipe`); achievements and secrets in two grids; `settingsRenderKey` so Settings isn't rebuilt on each swipe; card styles restyled; `formatLocalNowTime` follows Show Seconds; alerts have no large icon.

## 24. New in 1.8.4

- **Glass cards**: one flat tint (`color-mix` of white into the container colour) plus the bright edge and an inset highlight; the 1.8.3 gradient and large soft shadow made Settings about 3x as much raster work as it slid in.
- **Parked pages**: `Pager.park(tab, draw)` / `unpark`, `parkNeighbours()` (after every switch, when idle, and after `queueTabPrerender`). The current page's neighbours keep their full layout beside the screen (`.tab-page.hidden.parked`, `will-change: transform`); `draw` shows the page for a few frames at 0.4% opacity so the browser rasters it, then moves it aside. `#m3Scaffold` is `overflow: clip; min-height: 100vh`, so parked pages never change the scroll length. A cancelled swipe re-parks (`Pager.detach`). Off with Reduce Motion.
- **Idle work**: `whenIdle` waits while a finger is on the screen (`touchActiveUntil`), a page is sliding or a neighbour is attached; `queueTabPrerender()` also runs at startup.

## 25. New in 1.8.5

- **Checking game pages** (`GameRecheck.kt`, Settings → Check Game Pages for Changes, `settings.recheckGames`, off by default): a daily `PeriodicWorkRequest` on an unmetered network with the battery not low runs a pass once a week (`GameRecheck.isDue`). It reads up to 60 games' pages with `WebsiteScraper.fetchAndScrape` (no rendering, which needs the app open) within 7 minutes and writes `files/game_recheck.json` (`{at, results: [{id, url, finalUrl, parsed}]}`); it changes nothing. On the next start the hub reads it (`AndroidBridge.getRecheckResults`), and `HubCore.recheckChanges` keeps only changes worth asking about: name / description that differ, the icon when the game uses a site icon, the reset time only when the page states it (`parsed.timeFound` / `zoneFound`, new in the scraper output), the score type only over the "Daily Puzzle" placeholder, the category only over "Custom Daily", and the link when the site moved to another host. Built-in games are skipped. The dialog (`modalRecheck`) applies the ticked ones (a reset time moves history as an edit does) and keeps the rest in `state.recheckDeclined` (`HubCore.recheckKey`) so they aren't suggested again; "Keep mine" declines them all, closing decides nothing. **Check now** runs a pass at once (`recheckGamesNow` → `window.onRecheckDone(count)`).
- **Bottom bar**: `--nav-alpha` (how solid it is, 25% by default) from `settings.navTransparency` (0-100, the slider in Settings → Display).
- **Copied results**: `HubCore.combinedShareText` puts a `───────────` line between games.
- **Swipes**: `parkNeighbours()` now parks every page but the current one, drawing one at a time (neighbours first), 700 ms after a switch (`parkSoon`). `Pager.stopDrawing()` on `touchstart` moves a page being drawn ahead aside at once (the screen's frames waited for that drawing). `saveState({ keeps: ['dashboard'] })` for saves the hub doesn't show (the version seen, achievements), so they don't make the hub redraw on the first swipe away.
- **Accents**: `--gold-glow` has no offset (it was 4 px lower than the button).

## 26. New in 1.8.6

Measured with a harness that runs each interaction at 4x CPU slowdown and reports long animation frames and animations that couldn't run on the compositor.
- **Saving**: `saveState` no longer flushes in a microtask. `flushPendingSave` runs at the next idle moment (`requestIdleCallback`, 600 ms timeout), waits while `interactionBusy()` (up to 3 s), and runs at once on `visibilitychange` (hidden), `pagehide` and `HubActivity.onPause` (`window.__flushPendingSave`). A save serialises the whole state (over 1 MB with a long history). `saveLaunchPrefs` only calls the bridge when its values change (`setThemeMode` redrew the window background on the UI thread every save).
- **Done today**: `toggleDoneSection` fills or empties `#doneSection` only, with an opacity/translate animation (it animated `height` and rebuilt the grid). `hubList()` is shared with `renderGamesGrid`.
- **Folds**: `setFold(card, open, animate)`; `glideSiblingsAfter(el, change)` is a FLIP on the following cards.
- **Score History**: `renderHistoryList()` split out of `renderStatsAndCharts`; paging, search and filters call only it.
- **Landing**: the page just left becomes `inert` / `aria-hidden` when idle (it's clipped to no height at once); the live tick skips while `tabAnimating` or a neighbour is attached; `updateLiveTimers` writes text only when it changed (`el.__text`).
- **Return to the hub**: `refreshVisibleTab()` (keyed, the calendar keeps its month) instead of `renderVisibleTab()` on `visibilitychange`.
- **Nav labels**: no colour transition (it ran on the main thread as a page landed). **Celebration**: no `backdrop-filter`.
- **Slider**: a vertical drag that starts on `#settingNavTransparency` puts its value back.

## 27. New in 1.8.7

- **Background glow**: `body::before` (fixed, `z-index: -1`, explicit radial-gradient radii) instead of `background-image` on `body`. The body background is drawn on the canvas, sized to the document, so each tab's length changed the glow: a brightness step when a tab landed.
- **Theme changes**: `applyThemeAndSettings` adds `html.theme-switch` (`transition: none` on everything) for two frames.
- **Score History**: rows show the name and the meta (streak, time, status) on one line; the preview line and the PB flag are gone. `btnHistoryChanges` lives in Data & Backup.
- **Recap**: `#recapCard { overflow: clip }`.
- **Settings rows**: `.set-row` (no box; a divider between rows) replaces the boxed row classes inside `#viewSettings`.
- **Card Style**: setting, `data-cards` and its CSS removed.
- **Bottom bar**: `applyNavTransparency` also sets `--nav-sheen`, `--nav-edge` and `--nav-sat` (clear glass toward 100).
- **Gold / ruby buttons**: `inset 0 0 0 1px` rim instead of a top highlight and bottom shade. **Toasts**: no `tone-ok::before` bar. **Hub icons**: `.m3-shape-app` (30% radius) for every card.
- **Game player**: `assets/game/select-menu.js` (document-start, every frame) prevents a single-choice `<select>`'s own picker on `mousedown` and opens a themed menu in a closed shadow root; a choice sets `selectedIndex` and dispatches `input` and `change`. Colours from `GameActivity.selectMenuColors`.

## 28. New in 1.8.8

- **Pages as layers**: `.tab-page { will-change: transform }` permanently (not with Reduce Motion); `Pager.attach` / `settle` no longer set and clear `will-change` on the current page. Toggling it re-rastered the page leaving at the start of each swipe and the page arriving (into the root layer) as it landed.
- **Parking at the scroll position**: `Pager.slideGeometry(cur, el)` (shared by `attach` and `park`) gives the top a page slides in at; parked pages wait (and are ghost-drawn) there. A scroll listener re-parks them when idle, redrawing when the scroll moved more than half a screen from where they were drawn (`el.__drawnAt`).
- **Drop-downs in the player**: `select-menu.js` opens the menu on `touchend` (non-passive, cancelled) for a tap that didn't move, so no compatibility `mousedown` reaches the `<select>` (Android WebView opened its picker before a `mousedown` handler could prevent it). `GamePlayerTest.aDropDownOnAGamePageOpensTheAppsOwnMenu` taps a `<select>` in the real player and checks the menu shows.
- **Bar at Clear**: `--nav-accent` (1 − see-through). Gold / ruby docks draw their accent edge as a masked `::before` ring at that opacity over the white glass edge, and their glow's alpha is scaled by it.
- **Even rims**: ruby buttons and the bar's highlight use a gloss that is the same at the top and bottom; gold's shine on them runs at 90°.

## 29. New in 1.8.9

Measured frame by frame: page velocity across release, main-thread work per drag frame by stage (at 6x CPU), invalidation tracking and a JS profile of the drag.
- **Release**: `Pager.settleCurve(dx, target, v, w)` — a `cubic-bezier(0.333, s/3, 0.667, 1)` (time-exact) whose starting slope `s` is the finger's speed (`toward * ms / left`, at most 3), duration `2.2 * left / speed` within 200-420 ms, starting a frame's travel ahead. From rest or moving away: `cubic-bezier(0.2, 0, 0, 1)`. Rubber band uses a fifth of the finger's speed. The old curve paused a frame and set off at up to 3.6x.
- **Drag**: `Pager.dragWith` makes paused WAAPI animations (cur page, neighbour, highlight; `fill: both`, linear over 1000 ms) and `follow` sets their `currentTime`; `stopDrag(dx)` writes the same positions inline, cancels them and flushes styles (a transition's start is the pre-drag inline value otherwise). Layerize per frame went from ~3 ms to ~0.25 ms at 6x.
- `Pager.width()` cached (`_w`, cleared on resize); `NavIndicator` measures tabs once (fractional, relative to the bar), writes only changed properties (`put`), equal widths within 0.5 px move by transform only.
- **Card press**: `press-hold` added after 150 ms of resting, not on touchstart.
- `slideGeometry` uses fractional rects (offsetTop/Left are rounded).
- `#calendarDaysGrid, #historyTableBody, .recap-swipe { will-change: transform, opacity }`.
- Device test `HubSmokeTest.swipingBetweenPagesWithAFingerMovesTabAndSettles`.

## 30. New in 1.8.10

Quick swipes stuttered, slow ones didn't: the first drag frame did all the setup (attach changed the neighbour's class and cssText, repainting it; `dragWith` created the WAAPI animations) and took ~37 ms at 6x, so a fast finger was well ahead by the next frame.
- **`Pager.prime()`** on touchstart (after `swipe` is made): paused WAAPI animations (2000 ms, `fill: both`, `currentTime` 1000 = rest) for the current page (w → -w), prev (0 → -2w) and next (2w → 0) when both are parked exactly, and the highlight (prev, cur, next x; equal widths only). `follow(dx)` sets `currentTime = 1000 - dx / w * 1000`. `unprime(dx)` writes the inline positions, cancels and flushes; it runs from `stopDrag`, a vertical scroll, a card reorder and an unlocked `endSwipe`.
- **`attach` fast path**: a parked page whose `el.__parked` (`{left, top, width, dir, w}` from `park`) still matches `slideGeometry` keeps its class and cssText. `unpark` and `switchNavigation` clear it.
- `NavIndicator.place(tab)` no longer measures; `measure()` runs on resize and after a text-size change.
- First touchmove handler ~0.6 ms at 6x (setup ~9 ms at touchstart, before motion); per move ~6.5 ms.

## 31. New in 1.8.11

Measured in the real WebView on the CI emulator (`SwipeTimingDeviceTest`, logcat tag `SwipeTiming`, printed by ci.yml): only the first slides in a fresh app process stalled (touchmoves 100-500 ms late, no JS task over 8 ms meanwhile, so drawing); later ones were smooth either way, also after a page reload or a new renderer. Neither parking pages in place at 0.4% opacity nor a 0.4% opacity rehearsal helped.
- **`warmUnderCover()`** (index.html): called by `HubActivity.revealHub` once the launch screen lets the activity draw, while `launchCover` is still over the WebView. Attaches each other tab in turn and moves it with `Pager.follow` over 8 frames (out to a full page and back), then `stopDrag(0)` / `detach()` / `NavIndicator.place`, and calls `AndroidBridge.onWarmDone()`, which lifts the cover (`liftLaunchCover`, after a visual-state callback). The cover comes off after `MAX_WARMUP_MS` (1.5 s) whatever happens. About 300 ms on desktop.
- Device-test hooks: localStorage `dailies_test_motion=1` turns reduced motion off (CI runs with animations off, which the WebView reports as reduced motion) and turns on `window.__probe` (timers, idle tasks, frame callbacks, listeners and native calls over 8 ms).

## 32. New in 1.8.12

- **share-hook.js, screen results**: `saysFinished()` (FINISHED_RE / FINISHED_INTL_RE without lines matching NEXT_LINE_RE or COUNTDOWN_RE) gates `offerScreenResult`; `screenResultLines()` skips NOT_RESULT_RE / COUNTDOWN_RE lines and 0:00, a time needs a label (`time`, `took`, `solved in`…, not a bare `in`), a bare value isn't labelled by a line that is itself a score. The lines are read twice 1.6 s apart and only unchanged ones are kept, ranked (what the player did > a count > a time).
- **share-hook.js, MutationObserver**: observes `document` (it ran before `document.documentElement` existed, threw, and never observed), and ignores records that add no element (a ticking clock).
- **hub-core.js**: `memo()` for `normalizeZone`, `parseHHMM` (frozen), `cycleKeyToDay`, `addDaysToCycleKey`, `resetOnDay` (keyed on the day in its zone, plus `localZone()` for local schedules, read at most once a second); `trackedDays` once per `achievementMetrics`.
- **index.html**: the achievements cache also holds the secrets (`currentSecrets()`); `checkNewAchievements` keeps it current across its own save (`keepAchievementsCurrent`); the start check runs 8 s after load.
- Device tests `GamePlayerTest.aCountdownAndARunningClockAreNotOfferedAsAResult`, `aLateEndScreenOffersItsResult`.

## 33. New in 1.8.13

- **Ruby cabochon**: `design/icons/ruby-css.js` GEM: `--gold-shine` is three radial gradients (a gloss at 32% 20%, light from below at 56% 82%, a body darkest in the middle and the same red at every edge); filled buttons get `inset 0 0 0 1px rgba(100,0,28,.3)` inside a light border, and an even glow (`0 0 16px -5px`). `--gold-edge` is vertical and as bright at the bottom as the top. The generator now rewrites its block in place and copies only the gold rules before it (later rules adjust gold and ruby together). The 1.8.8 ruby override uses `var(--gold-shine)`.
- **Same game, other link**: `HubCore.findSameGame(cand, games)` → `{ game, reason, sure }`; `gameLinkKey` (host without www./m., lower-case path without index.html or `/`), `gameNameKey`. Sure: `same` (the page, or one of `game.altUrls`), `redirect` (`cand.finalUrl`). Asked: `embedded` (`cand.embeds` vs the game's links, or `game.embeds` vs the candidate), `menu` (same host, one path inside the other), `name`. Archived games are skipped.
- **All done**: `celebrateAllDone()`, `#celebrate` and the `.celebrate` / `.cel-*` styles are gone; `checkAllDoneCelebration()` only sets `#hubLogo.all-done` (the sparkle), and only with `settings.celebrate` on (re-checked when the setting changes).
- Scraper: `WebsiteScraper.embeddedGameLinks(html, pageUrl, renderedFrames)` (iframes from the HTML and PageRenderer's frames of at least 150×150, no ads, videos, maps or social frames) → `embeds` in the result. The Add form keeps `addLinkInfo`, shows `#sameGameNote`, asks on the first Save (`sameGameAskedFor`); a new game stores `altUrls` and `embeds`.

## 34. New in 1.9.0

- **Settings → Display**: `#btnOpenTheme` opens `#modalTheme` (theme mode, wallpaper colors, `#themeChoices`, AMOLED: the same controls, moved, so their wiring is unchanged); `#btnOpenAppIcon` opens `#modalAppIcon` (`#iconChoices`). `#settingAccent` (None / Gold / Ruby, only unlocked ones) sets the old `#settingGoldAccents` / `#settingRubyAccents` switches, which stay in the page (rows `.accent-switch-row`, never shown) with their own handlers. `renderDisplaySummaries()` fills `#themeSummary`, `#appIconSummary` and the Accent choice; it runs from the theme / icon / accent renders and after any setting changes.
- **Explanations**: at start, each Settings row's grey line right after its title (a `p` without an id) gets `.set-desc` (hidden) and an `.set-info` ⓘ button in the title that toggles `.show-desc` on the row (it prevents the default, so inside a switch's label it never flips the switch). `.set-row` padding 12px → 9px. Divider lines: any row with a shown row before it (hidden rows between no longer drop the line).
- **Hub**: the `.gc-pb` personal-best tag is gone from game cards.

## 35. New in 1.9.1

- **Settings as pages**: `#settingsIndex` lists the `#viewSettings > [data-fold]` cards (`renderSettingsIndex()`: the card's icon and h3, a note from `SETTINGS_SECTION_NOTES`, a row hidden while its card is, kept in step by a MutationObserver). `openSettingsSection(name)` adds `.sub-open` to the view and `.sub-current` to the card (opened, its heading no longer folds it); CSS shows either the list or the `#settingsSubHead` back bar plus that card. `closeSettingsSection()` returns to the list (the back bar, `handleBackPress` before leaving the tab, and the Settings tab tapped again). `revealFold()` on an element in Settings opens its section.
- The 1.9.0 ⓘ explanations are gone (`.set-desc` / `.set-info`), and `.set-row` padding is 12px again.

## 36. New in 1.9.2

- **Settings sections open in place** (replacing 1.9.1's pages): `#settingsIndex` and the back bar are gone. Each `#viewSettings > [data-fold]` card is a fold that starts closed; `initFolds()` adds a `.fold-note` (from `SETTINGS_SECTION_NOTES`) after its h3, shown only while folded. `openSettingsSection(name, { keepScroll })` closes any other open section, keeps the tapped heading where it was on screen (scrolling by how far a section closing above moved it), opens it with the usual glide, and scrolls a section opened low on the screen up. `closeSettingsSection({ instant })` closes the open one (true if there was one); Back, tapping Settings again, and `switchNavigation` leaving Settings (instantly, once the page has slid away) call it. `revealFold()` opens the element's section without the scroll handling.

## 37. New in 1.9.3

- **Ruby app icon, step cut**: `design/icons/ruby-icon.js` draws the stone as four copies of the star outline scaled 1, 0.84, 0.68 and 0.52 about the centre, each with its own top-left-to-bottom-right gradient (alternately lit and shadowed, so they read as steps), thin light edges between them and a blurred highlight at the top left; the shadow, rim and white mark are as before. Run it to rebuild the launcher PNGs and the hub's `rubyIconSvg`.
- **Today on the calendar**: `.cal-cell.today` uses `--today-c` (the primary colour, `#BAA465` gold, `#CF1D4F` ruby) for an inset ring, an inner glow and an outer halo (`z-index: 1` so it shows over the next day); `.cal-swipe` has `overflow-clip-margin: 16px` so the halo isn't cut off at the edges.

## 38. New in 1.9.4

- **Hub JS & UI glue (`hub-ui-helpers.js`, `hub-core.js`, `index.html`)**: extracted pure UI glue helpers (`hubOrder`, `msUntilNextResetCached`, `normalizeGameObject`, `applyRestoreGameState`, `restoreRestoredGameState`, `dedupeHistoryLogs`, `applyScheduleChangeState`, `streakAsOfDate`) into `app/src/main/assets/hub-ui-helpers.js` with Node regression tests. Fixed `hubOrder()` passing `Date.now()` instead of `new Date()` when sorting by Next reset; preserved `altUrls`/`embeds` in `normalizeGameObject()`; reset/restored `status` and `todayScore` on single-game Restore/Undo; flushed native `syncGames` synchronously in `flushPendingSave()`; routed `renderRemovedGames`/`recheckValueHtml` through `iconSrc()`; fixed `themeSelect()` listener leak; replaced unbounded `Math.max`/`Math.min` spreads with O(n) loops; deduplicated `historyLogs` after schedule changes; normalized `swaps?`/`clicks?`/`steps?` and 1–2 digit numerators in `POINTS_OF_RE`; added DST slack in `suggestResetTime()`; fixed past-day edit streak stamping and PB suppression; grouped `periodRecap()` by `gameId`; revoked `exportCsv()` object URLs; removed dead `selectMode` markup/code and dead `data-fold-default="open"` attributes.
- **Native Android & Game Player**: fixed `StreakReminders` broken-streak skip, explicit per-game `reminderMinutes` override when muted, and DST-safe `nextCheck()`; excluded 5 device-local `SharedPreferences` files from `backup_rules.xml` and `data_extraction_rules.xml`; added `SafeHttp.isPublicHost` redirect/request checks and `cancel()` to `PageRenderer`; fixed detached popup `WebView` leaks, off-main-thread SAF writes and `data:` decoding, `@Volatile gameId`, `STATE_SHARE_CONFIRMED`, `WindowInsets`, Back press, `exitFullscreen()`, and `banner.animate()` race in `GameActivity`; moved `ScreenshotReader` bitmap decode off the UI thread with `recognizer.close()` and cancel handling; wrapped `PageCache`/`GameRecheck` in `Dispatchers.IO` with atomic `PageCache` staging; added `fetchExtras = false` for `GameHealth.refreshIcons()`; fixed `WebsiteScraper` `.co.uk` PSL and `midnight`/`noon` reset context; synchronized `CrashLog` with safe truncation; fixed `GameListCodec` deflate loop and size bounds; abandoned failed `UpdateInstaller` sessions with BAL-safe confirmation; hardened `download-hook.js` (bounded `blobs` + TTL), `select-menu.js`, `share-hook.js`, and `cookie-hide.js`.
- **Ad-blocker & tools**: loaded `PublicSuffix` before `GamePrefs` lookups on cold start; fixed `$important` vs `allow_hosts` precedence, `domain=foo.*` entity matching, and lowercase `pageHost` in `AdBlockEngine`; pre-sorted signed int64 host hashes and separated exclusion-only cosmetic rules in `tools/build_adblock.py`; added curl timeouts to `tools/fetch_adblock_lists.sh`.
- **PWA, docs & CI**: scoped `web/sw.js` cache lookups to `CACHE`, added 5xx navigation fallback, wrapped `c.put()` in `event.waitUntil()`, and added `{ cache: 'reload' }` on precache; hashed `web/sw.js` and bundled `hub-ui-helpers.js` in `web/build.mjs` / `web/check-build.mjs`; guarded `web/web.js` `showHint()` and `openGame()`; updated `THIRD_PARTY_NOTICES.md`, `RELEASE_NOTES.md`, `README.md`, `GUIDE.md`, `design/icons/README.md`, Fastlane metadata (`30.txt`, `31.txt`, `32.txt`, `full_description.txt`), and `.github/workflows/{ci,release}.yml`.

## 39. New in 1.9.5

- **Radiant Star Ruby accents**: replaced the coral-pink cabochon Ruby finish with a deep faceted crimson palette (`#AD0826` / `#D90B32` primary, `#FFE0E5` / `#4A0614` containers), crisp prism rims, vivid crimson glow, high-contrast Light mode tones, and a subtle sheen sweep on primary buttons (disabled under Reduce Motion).
- **Crystalline Prism Ruby icon**: redesigned `.IconRuby` (`ic_ruby_fg`/`bg` and `rubyIconSvg`) as a 4-plane geometric crystal with a central diamond table, crimson under-glow, and white glints across launcher densities and the hub picker.
- **Gold accent polish**: restored unlocked achievement card gold borders and aligned Gold's completed status pill with the horizontal gold sheen used on primary buttons.

## 40. New in 1.9.6

- **12 pre-made Color Themes + Gold & Ruby in Theme picker**: added 7 themes (Teal, Amber, Crimson, Cobalt, Sage, Plum, Slate) alongside Iris, Ocean, Forest, Sunset, and Rose (`HubCore.THEME_PRESETS`, `COLOR_THEMES`), and moved unlocked Gold and Ruby themes directly into **Settings → Display → Theme**.
- **Matching App Icons & Daily Random Theme**: added 10 new preset launcher aliases and adaptive vector drawables (`.IconIris`, `.IconForest`, `.IconRose`, `.IconTeal`, `.IconAmber`, `.IconCrimson`, `.IconCobalt`, `.IconSage`, `.IconPlum`, `.IconSlate`) matching every pre-made theme 1-to-1 in `ICON_STYLES`, `AppIcon.STYLES`, and `iconForTheme()`. Enabling **Daily Random Theme** (`HubCore.pickDailyTheme`) rotates unlocked pre-made themes without back-to-back repeats across days (including midnight rollovers) and automatically matches both the hub logo and launcher icon, locking fixed icon picks with an explanatory hint while active. Selecting `"Theme"` (`themed`) with a non-wallpaper pre-made theme also resolves to its matching preset icon on Android (`launcherIconStyle()`).
- **Brushed Champagne Gold icon**: rebuilt `goldIconSvg` (`design/icons/gold-icon.js`) and launcher assets with pure-vector diagonal specular highlights, crisp hairline rim, and centered elevation shadow.
- **UI, history, persistence & security hardening**:
  - Updated `loadState()` to block overwriting corrupt `localStorage` or native saves (`__saveBlocked`), prefer `localStorage` over native only when written as an explicit `__nativeFallback`, and sync `lastLoggedCycle` to native `StreakReminders`.
  - Normalized `recomputeLogStreaks()` and `sortHistoryLogs()` across manual edits (preserving existing `loggedAt`), undo, trash restore, auto-missed logs, and backup imports; guarded past-day `restoreUndo()` from overwriting today's live status/score; deduplicated conflicting Trash vs Undo entries and reopened `modalTrash` on cancel or remaining items; synced `todayScore` across rollovers, rechecks, and schedule edits (`applyScheduleChange` before renaming).
  - Updated `onReturnFromGame()` to respect `interrupted` and `fromWebTab` flags, `handleReturnPromptAction('paste')` to use `readClipboardText()`, `onScreenshotText()` to surface native OCR errors (`unreadable_image`, `model_unavailable`, `ocr_failed`), and `sharedResultTarget()` / `onScreenshotText()` to require `HubCore.mentionsGameExplicitly()` instead of bare substring matches.
  - Upgraded `drawRecapCard()` (Light+Gold & Dark/Light Ruby palettes, code-point truncation, value width scaling, Unicode `\u2068...\u2069` bidi isolation), CSV export (RFC 4180 formula neutralization including whitespace/control prefixes and `parseResultValue` formatting), `customIconSvg` sparkle contrast (`L > 0.62`), modal `Shift+Tab` & `themedSelectOpen` keyboard/back behavior, hub search back-button closing, section-scoped card drag-reorder with `touchcancel` reset, Dynamic Wallpaper clearing legacy accent flags, Dark+Ruby primary contrast (`#D90B32`), `<meta name="theme-color">` syncing, and removed all dead single-game backup restore code.

## 41. New in 1.10.0

- **Material 3 Expressive typography & Hub layout**: bundled authentic `ROND=100` `GoogleSansFlex.woff2` + `GoogleSansFlex-LatinExt.woff2` (`opsz 8..72`, `wght 400..800`) and `res/font/google_sans_bold.ttf`. Replaced the legacy Hub header with `.hub-topbar` (`#hubLogo` + `Dailies` wordmark + tonal `Results` & `Search` pills) and `.hub-hero` (`28px` tonal summary card with `#kpiDoneCount` display count, `#kpiReadyCount` & `#kpiTopStreak` ticker pills, and integrated `#btnPlayAll`), backed by a global status-bar scrim (`body::after`) and clean sticky `.hub-sticky` filter/sort bar. Upgraded `.game-card` (`26px` tonal cards, `46×46` `32%`-squircle icons, uniform `38px` `.gc-actions` row) and replaced the 4-item bottom dock with `.m3-bottom-bar-row` (3-tab `.m4-floating-dock` with `#navIndicator` paired with `56×56` `#btnOpenAddModal` `.m3-dock-fab`).
- **Native `GameActivity` expressive menus & alignment audit**: added spring pill-to-squircle touch morphing (`attachPillMorph`), replaced platform `PopupMenu`s with `24dp` tonal `PopupWindow` menus (`showExpressiveMenu`), upgraded `showZoomDialog()` to a `28dp` tonal `ComponentDialog`, added `sheetStrokeColor()` `1dp` outlines for AMOLED clarity, styled all Hub checkboxes as `46×26` M3 pill switches, fixed `#viewSettings` `.set-row` multi-column layout at `>= 640px`, right-aligned compact `.tsel-menu` / `select-menu.js` popups, and unified all 21 modals to `28px` tonal sheets.
- **Session persistence & codebase hardening**: added `InFlightGameSession` so active game sessions survive notification taps and restarts; hardened `SafeHttp` DNS/IPv6/redirect checks, `CosmeticFilters` wildcard rules, `ShareLinkText` / `share-hook.js` `#` query preservation, `ScreenshotReader` (`androidx.exifinterface`), `StreakReminders` window text, `UpdateInstaller` API guards, and atomic state/backup persistence.

## 42. New in 1.10.1

- **Persistent, interactive app header & seamless Hub controls**: moved `.hub-topbar` (`#appHeader.app-topbar-shell`, `52px` + `--safe-top`) above `<main id="m3Scaffold">` with `#btnCopyToday`, interactive `#btnHeaderReady` (toggles `ready`/`all` or switches to Hub with `ready`), and `#btnHeaderStreak` (jumps to **Streaks & Log** pre-selecting the highest-streak game's calendar) live across all tabs and subtle scroll elevation (`html.app-scrolled`). Made `#viewDashboard > .hub-sticky` transparent and non-sticky, placed `#btnHubSearch` inside `.hub-actions-right` alongside `#hubSort`, removed card `data-action="delete"` (`X`) buttons, hid `.reorder` arrows when `#gamesGrid[data-sort]` is not `custom`, and wrapped `.game-card.compact .gc-title` cleanly without truncation.
- **Zero-stretch boundaries & smooth transitions**: added `OVER_SCROLL_NEVER` in `HubActivity`, `overscroll-behavior: none` across pages/dialogs/menus, and zero rubber-band clamping at main tab, Score History, and Recap swipe boundaries. Rebuilt `shiftCalendarMonth()` using a flicker-free `.cal-ghost` cross-slide across fixed 6-row (`42`-cell) grids, softened `.cal-cell.today`, and refactored Settings folds (`openSettingsSection()`) to batch reads/writes cleanly over `.fold-body` with `contain: layout style`.
- **Monochrome SVG UI, Streaks & Gold/Ruby surface polish**: standardized Dailies UI/shared text on crisp monochrome outline SVGs (`achIconHtml`, `wnIconHtml`, lock/clock/camera/theme-swatch SVGs) and clean typographic markers (`✓`/`✗`/`•`/`★`), restricting `🔥` exclusively to streaks and `⚡` exclusively to fastest completion times. Moved **Achievements** open below **Score History**, added whole-day Score History pagination (`HubUiHelpers.paginateHistoryByDay()`), added modal top/bottom safe-area clearance, updated Gold & Ruby rules (`ruby-css.js`), and added warm obsidian **Gold** / wine-obsidian **Ruby** surface tokens synced via `dailydeck_theme_tokens_v1` first-frame boot and `AndroidBridge.setPlayerColors`.

## 43. New in 1.10.2

- **Balanced Daily Hub spacing & default All view**: restored clean vertical and horizontal breathing room between `#appHeader`, `.hub-hero`, `.hub-sticky`, `.game-card` items, inline action buttons, and `.m3-bottom-nav-wrapper`, and always reset `activeFilter` to `'all'` on launch.
- **Brushed Champagne Gold palette, concentric rims & stable nav labels**: matched the Gold theme tokens directly to the Brushed Champagne Gold app icon, unified Ruby and Gold filled buttons onto concentric `1px` `border-box` rims, and switched `#navIndicator` on Gold/Ruby to translucent glass so bottom nav labels stay invariant while swiping.
- **Proportional numerals, monochrome achievements & Recap cleanup**: switched streak/stat numerals to uniformly lit proportional `Google Sans Flex` glyphs, converted all 24 achievements to monochrome outline SVGs, and removed the Weekly Recap share buttons.

## 44. New in 1.10.3

- **Automatic result logging overhaul (`share-hook.js`, `GameActivity`, `ShareResultDetector`, `hub-core.js`)**: extended post-input watch windows (`90s`) for long finish animations, added DOM visibility/attribute `MutationObserver` watching, matched word countdowns (`"opens in 9 h 12 m"`), filtered below-the-fold FAQ/SEO prose and live gameplay progress counters (`0/9 Fields`, remaining guesses), blocked non-daily rounds (`Unlimited`, `Practice`, `Replay`, `Archive`, `∞`), and added `aria-label` / icon-only Share probing, `localStorage` completion cues, `window.prompt` & readonly share boxes, and two-step Share → Copy menus.
- **URL site scraping overhaul (`WebsiteScraper`, `PageRenderer`, `index.html`, `hub-core.js`)**: fixed bare-origin relative URL resolution (`resolveUrl`), prioritized app bundles over vendor scripts, expanded reset time & time zone detection (`German time`, `Berlin`, `Toronto`, `Brazil`, Asian/Oceania zones, `"in your local time"`, `UTC±offset`, Luxon/Day.js hints), added `targetFormat` extraction (`37 guesses`, written numbers, `50,000 points`, `Fastest time`, `Most points`, `Fewest guesses`, `Fewest strokes`), improved concise how-to-play descriptions, and added weighted **Gaming & RPG** / **Custom Daily** category scoring.
- **My Results tab**: renamed the middle bottom navigation tab from **Streaks & Log** to **My Results**.

