# Dailies 1.5.1: How the App Works

- **App name**: Dailies
- **Package ID**: `com.dailygames.hub`
- **Version**: `1.5.1` (`versionCode = 9`)
- **Android**: 7.0 (API 24) or newer; built for Android 17 (API 37)
- **Layout target**: large phones such as the Pixel XL line (about 448 CSS px wide), checked down to 360 px
- **License**: GPL-3.0 (see [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md))

Dailies is a home for the daily web puzzles you play: Wordle, Connections, Nerdle, geography and
trivia games, or any site with a once-a-day puzzle. It tracks when each one resets, alerts you when a
new puzzle is out, opens games in a built-in ad-blocking player, and keeps your streaks and results.

---

## 1. What's in version 1.0.0

1.0.0 is the first public release. It contains everything from the private 2.x builds, plus a full
code review: 37 bugs fixed and 26 improvements made. Section 7 lists the changes from the review.

| Area | What you get |
|---|---|
| **Daily Hub** | A card for every game you add, with a live countdown to its next reset, your streak, and Play / Log / Edit / Remove buttons. Reorder cards by long-press and drag, or with the arrows. Filter by All / Ready / Done. |
| **Adding games** | Paste or share a link. The app reads the game's name, icon, category and reset time from the page, and checks a built-in list of 12 popular games (Wordle, Connections, Strands, Contexto, Globle, Nerdle, Waffle, FE Guess, Framed, PokeDoku, Travle, Krillion) for known reset times. |
| **Reset times** | Each reset is stored in the time zone the game publishes it in (your local time, UTC, or a named zone such as `America/New_York`). It's always shown in your phone's local time, with daylight-saving changes handled correctly. |
| **Game player** | Full-screen player with Hub, a game switcher on the title, Next, an ad-block shield (with a blocked count), a ⋮ menu (Reload, Open in browser, Zoom) and Log. Supports fullscreen video and canvas, file uploads (Photo Picker), downloads, and "open in app" links. |
| **Auto-logging** | When you tap a game's **Share** or **Copy** button, the result is captured. Going back to the hub logs it as Solved (or Missed for a failed result such as "X/6"), with the share text as your score and an **Undo** button. |
| **Ad blocking** | About 302,000 ad, tracker and malware domains, 23,400 URL rules and 43,000 element-hiding rules, built from EasyList, EasyPrivacy, AdGuard Base, HaGeZi Pro and StevenBlack. You can switch it off per site or everywhere. |
| **Streaks & Log** | A completion calendar per game (best streak, current streak, plays) and the full score history, 5 results per page. Swipe or tap to change pages. |
| **Reset alerts** | A notification when a game's new puzzle is out, per-game on/off, grouped when several reset at once, and exact timing where Android allows it. |
| **Look** | Material 3 Expressive with Google Sans, Material Symbols icons, Material You wallpaper colors, an optional true-black AMOLED mode and compact cards. |
| **Your data** | Stored only on your phone, with no size limit. Every save is atomic and keeps a backup copy. You can export and import `.json` backups. |

---

## 2. Installing

1. Download `Dailies-1.0.0.apk` from the GitHub release page.
2. Open it on your phone. Android will ask you to allow installs from your browser or file manager the first time; allow it.
3. Open **Dailies** and tap **+ Add Game**.

> **Already have a 2.x build installed?** 1.0.0 is signed with a new release key and has version code 1,
> so Android won't install it over a 2.x build. Your data moves across with a backup:
> 1. In the old app: **Settings → Data & Backup → Save Backup (.json)**.
> 2. Uninstall the old app. This deletes its data, so do step 1 first.
> 3. Install `Dailies-1.0.0.apk`, then **Settings → Data & Backup → Import Backup** and pick the file.
>
> Every future update is signed with the same key, so it will install straight over 1.0.0.

---

## 3. Using the app

### Tab 1: Daily Hub
- **Adding a game**: tap **+ Add Game** and paste a link, or use **Share → Dailies** from Chrome.
  - **Or pick from popular daily games** lists the 59 built-in games by category (searchable); tap one to fill in its link, name and reset time.
  - The name, icon, category and reset time fill in by themselves.
  - Anything you've already typed is never overwritten.
  - **✓ Reset time from built-in list** means the time came from the catalog of popular games.
  - Pick the reset's time zone if the site doesn't say. The hint shows what that is in your time ("= 2:00 PM your time").
- **Game cards** show:
  - the icon, title and streak (🔥 7d);
  - the reset time in your local time;
  - a status pill (Ready / Done / Missed);
  - a live countdown and the action buttons.
- **Reset time check**: when a game's logged puzzle numbers don't fit its reset time (two puzzles on one day, or one puzzle on two), a banner suggests the time that fits, such as midnight New York time. **Update** changes it (moving history the same way as editing it); ✕ hides that suggestion for good.
- **Copy**: the button at the top of the hub copies all of today's solved results, each as logged, with a "— — —" line between them. It shows once a result is logged today.
- **Up next**: once you've finished a game today, a bar above the cards names the next unplayed game (after the one you played last, in your order). **Play next** opens it.
- **Reordering**:
  - Long-press a card for about 0.4 s until it lifts, then drag it. The list auto-scrolls near the edges.
  - The ⌃/⌄ arrows also work.
  - With a filter on, only the visible cards swap places.
- **Playing**: **Play** opens the game player. **✓** logs a result by hand: **Solved**, **Missed** and an optional score. **📋 Paste** drops in a copied share text.
- **After playing**:
  - Come back from a game you haven't logged and a sheet offers **Solved / Missed / + Score**.
  - If the puzzle reset while you were playing, the sheet first asks **which puzzle** you finished: the one you opened, or the new one.

### The game player
- **Top bar**:
  - **Hub**: go back to the hub.
  - **Game name**: tap it to switch to any of today's games (▶ playing, ✓ done, ○ not yet) without going back to the hub.
  - **After a result is saved** (copied, shared or found on the end screen), a card offers **Play \<next game\>** (opens it in the player straight away), **Back to hub**, or ✕ to keep playing. Logging by hand after leaving the player (the **Log** button, or **Solved** / **Missed** on the return prompt) shows the same choice in the hub.
  - **Next** (⏭): the next game you haven't played, in your hub order. Each game keeps its own play time and result, all logged when you return.
  - **Shield**: shows how many requests were blocked. Tap it to allow ads on this game's site, or to block them again; the choice is remembered per game.
  - **⋮**: **Reload**, **Open in browser** and **Zoom**. Zoom (50–200%) is remembered for each site.
  - **Log**: go back and open the log form, pre-filled with any captured result.
- **Result capture** works three ways at once:
  1. **Clipboard**: anything the game copies while it's on screen is checked, and checked again when you come back to the player or close it.
  2. **Find Results Automatically** (part of `share-hook.js`): when the page shows an end screen ("Next puzzle in 05:12:44", "You solved it") with a Share / Copy button a tap could actually reach, Dailies presses that button once in a probe mode where the result text is captured and nothing is copied, shared or opened. Links and buttons for other apps or images ("Share on X", "Copy link", "Share image") are never pressed, each button at most once and at most three per page, it stops once a result has gone out, and a real tap ends probe mode at once. It can be turned off in Settings.
  3. **Page hooks**: a small script (`share-hook.js`) sees `navigator.clipboard`, `execCommand('copy')`, copy events, `DataTransfer.setData` and `navigator.share`. Each hook calls the original and returns its result unchanged, so games behave exactly as before.
- **What counts as a result**: an emoji grid, a score like "3/6" with context, or the game's name with a count such as "#512" or "4 guesses". Links, names and random text are ignored.
  - Only the game's own page can submit a result; ads and embedded frames can't.
  - The result is logged against the puzzle that was live when you opened the game.
- **Downloads**:
  - Web links download with the game's login: into Downloads on Android 10+, or through Android's **Save to…** picker on Android 7–9 (up to 25 MB). If that fails, the link goes to your browser.
  - Files the game builds itself (`blob:` and `data:` links, e.g. a "save image" button) open Android's **Save to…** picker. They can be up to 25 MB.
- **Links to other apps** (`intent://`) only open an installed app through a normal "view" request, or fall back to the web page the link names.

### Tab 2: Streaks & Log
- **Completion calendar**:
  - Pick a game, swipe the calendar or use ‹ › to change month, and **Today** to jump back.
  - Tap a day to see its result (outcome, time, play time, streak and the result text) with **Copy** and **Edit**.
  - The tiles show **Best streak**, **Current** and **Plays**.
  - **Score stats** below them: win rate, the average score, play times and personal bests. Scores are read as guesses ("3/6"), game times ("1:42", "1m 05s"), points ("2,450 pts", "4,250m", or a line that's just a number, like Krillion's "60") or counts ("45 guesses"); each game is compared in the kind it uses most.
- **Score history**:
  - Every result is listed newest first, 5 per page: game, date, time, play time, outcome and streak.
  - Search every result (all words must match the result text, game name or date; matches are highlighted), and filter by game (the dropdown) and by **All / Completed / Missed**.
  - Tap **Result** on an entry to see the text the game shared, and **Copy result** to copy it with its line breaks and emoji.
  - From the 6th result, a pager appears: tap ‹ › or swipe on the list (it follows your finger, like the calendar).
  - Logging the same day again replaces that day's entry. Once today is logged as done, the card's ✓ is hidden; use **Edit entry** in the game's settings (⚙). Stats are recalculated from the history, so they go down as well as up.

### Tab 3: Settings
- **Display**:
  - Theme: Dark, Light or Match phone
  - Match Device Wallpaper Colors
  - AMOLED Pitch-Black Surface (dark theme only)
  - Compact Game Cards
  - Group Finished Games: today's done games move under a **Done today** header, folded away until you open it (with the All filter)
  - App Icon: Default (white, yellow-to-green star), Theme (follows the theme and wallpaper colors), Midnight, Sunset, Ocean, Mono, and Gold, which unlocks once the first tier of every achievement is earned. The hub's logo matches it
  - Gold Accents (1.5.0): unlocked by earning every tier of every achievement; gold replaces the theme's main color, with gold card trim, in the dark, light and AMOLED themes
- **Gameplay & Time**:
  - 12h / 24h clock
  - Seconds in countdowns
  - Ask to Log After Playing
  - Auto-scrape on paste
- **Game Player**:
  - Block Ads in Games
  - Auto-Log Shared Results
  - Find Results Automatically
- **Reset Alerts**:
  - The button reads **✓ Notifications enabled** when Android allows them. Otherwise it reads **Allow Notifications**, which asks for permission or opens the right Android settings page.
  - **Streak Reminder**: off, or 30 min to 6 hr before a reset. Each game can override it in its settings (⚙ → **Streak reminder for this game**: same as Settings, off, or its own time). If a game you have a streak in isn't logged by then, one notification lists them (on its own "Streak Reminders" channel).
  - Below it, each game has its own alert toggle.
- **Data & Backup**: **Save Backup (.json)** and **Import Backup (.json or .txt)** through the Android file picker.
  - **Weekly Automatic Backup**: choose a folder and a backup in the same format is written there every week (`dailies_autobackup_<date>.json`, the newest 5 kept). **Back up now** and **Turn off** appear once it's on.
- **About**: the version, and an opt-in **Check for Updates** (once a day, one anonymous request to GitHub's releases API). A newer release shows a banner on the hub; **Update** (or **Check now**) downloads it and opens Android's install screen.

### Undo
- Auto-logged results, quick actions, manual logs and backup imports show a toast with an **Undo** button. The toast stays up for 20 seconds.
- Undo is refused once that game's puzzle has reset, so it can never overwrite a newer day.
- To correct an older day, tap that day in the **Streaks & Log** calendar.

---

## 4. How it works inside

```
┌────────────── MainActivity (hub) ──────────────┐        ┌──────── GameActivity (player) ────────┐
│ WebView: assets/index.html + app.css           │  Play  │ WebView: the game's own site          │
│   hub-core.js  (dates, resets, streaks)        │ ─────▶ │   share-hook.js → "DailesShare"       │
│   known-games.js (built-in reset times)        │        │   cosmetic.js   → "DailesAdBlock"     │
│ AndroidBridge (JS ⇄ Kotlin)                    │ ◀───── │ AdBlockEngine / CosmeticFilters       │
│   HubStateStore  → files/hub_state.json        │ result │ Surrogates (stand-in ad libraries)    │
│   FaviconCache   → files/favicons/             │        └───────────────────────────────────────┘
│   WebsiteScraper → SafeHttp                    │
│   GameRepository → ResetAlarms / WorkManager   │──▶ Notifier (grouped reset notifications)
└────────────────────────────────────────────────┘
```

### The hub (`assets/`)
- **`index.html`** is the whole hub UI, fully offline. It talks to Android through `AndroidBridge`.
- **`hub-core.js`** holds the pure date logic: which "day" (cycle) a puzzle is on, the next reset, streaks and share-result detection. It's unit tested with Node.
  - **Shared test fixtures**: `schedule_cases.json` and `share_cases.json` are run by **both** the Node and the Kotlin tests. That way the notification scheduler and the hub can never disagree about a reset time or what counts as a result.
- **Time zones**:
  - Resets are kept in their own zone and converted for display.
  - **Clock goes back** (a time happens twice): the later moment is used, the same as Java's calendar.
  - **Clock goes forward** (a time is skipped): the reset moves forward to the next real time.
  - **Traveling west** (going back a day) doesn't wipe a logged result.
- **Saving**:
  - Several saves made close together are combined into one write.
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
- **`WebsiteScraper.kt`** reads a new link's title, description, icon and reset time, including the zone written next to it ("9 AM ET", "midnight UTC").
- **`FaviconCache.kt`** stores icons on the device and serves them through a reserved address (`favicon.dailes.invalid`). It backs off from icons that keep failing, refreshes icons older than 30 days in the background, and keeps the cache under 20 MB.
- **Reset alerts**:
  - `GameStore.kt` mirrors each game's schedule natively.
  - `ResetAlarms.kt` uses an exact alarm where allowed. Otherwise it uses a staged inexact alarm that re-arms closer to the reset.
  - `DailyRefreshWorker.kt` (WorkManager) is the backup.
  - `Notifier.kt` posts grouped notifications. Each carries a private token, so other apps can't use it to open the player.
  - Alarms are re-armed after a reboot, app update, time-zone change or clock change.
- **`GameActivity.kt`** is the player:
  - edge-to-edge, with predictive back and dynamic colors;
  - fullscreen, uploads, downloads and safe `intent://` handling;
  - its state survives Android closing it in the background (play time, captured result, page).
- **Ad blocking** (`AdBlockEngine.kt`, `CosmeticFilters.kt`, `AdBlocker.kt`, `Surrogates.kt`):
  - **Hostname lists**: about 302k domains, stored as sorted 64-bit hashes and found by binary search. A listed domain covers its subdomains.
  - **URL rules** use a subset of Adblock Plus syntax:
    - anchors and wildcards;
    - third-party / first-party, resource-type, `domain=` and `match-case` options, including uBlock Origin aliases;
    - `@@` exceptions and `$important`.
    - They're indexed by token, so a check takes about 40 µs.
  - **Precedence**: an `@@…$important` exception beats a `$important` block, which beats a normal exception, which beats a normal block.
  - **Same site**: the Public Suffix List decides whether a request comes from the game's own site. The game's own site is never blocked by the big host lists.
  - **Stand-ins**: blocked Google Analytics / gtag, Google Publisher Tag, IMA video ads and AdSense are replaced with harmless scripts, so games that call them directly don't break.
  - **Element hiding**:
    - The page reports the class and id names it actually uses, and only matching rules come back.
    - Site rules support `~domain` exclusions.
    - Sites marked `$generichide` / `$elemhide` / `$document` in the lists are honored.
  - **Popups and redirects**: pop-ups, pop-unders and redirects to ad domains that you didn't tap are stopped.
  - **Service workers**: requests made by a site's service worker are filtered too.

### Privacy
- No accounts, analytics, ads or tracking. The app has **no server**.
- **What reaches the internet**:
  - the games you open;
  - the page of a link you add, to read its details;
  - game icons, through Google's favicon service.
- Your games, history and settings stay on your phone.
  - Android's own backup only includes the hub file and settings.
  - Game-site logins, cookies and the icon cache are never backed up.
- **Permissions**:
  - Internet;
  - notifications;
  - vibrate (drag haptics);
  - run at startup (re-arm alarms);
  - exact alarms (on-time reset alerts).

---

## 5. Building from source

Requirements:
- JDK 17 or newer;
- the Android SDK with platform 37 (Android Studio installs it);
- Node.js 18+ (only for `testHubJs`);
- Python 3 (only to rebuild the ad-block lists).

```bash
./gradlew testDebugUnitTest testHubJs assembleDebug
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
  | `testDebugUnitTest` | 78 Kotlin tests |
  | `connectedDebugAndroidTest` | On-device smoke tests (needs an emulator or phone; CI runs them on an Android 14 emulator) |
  | `testHubJs` | The Node tests |
  | `python3 tools/test_build_adblock.py` | The list-builder tests |

- **Refreshing the ad-block lists**: `tools/fetch_adblock_lists.sh <folder>` downloads the sources, then run `python3 tools/build_adblock.py <folder>` and rebuild. The **Update ad-block lists** workflow does this every Monday and opens a pull request (allow it under **Settings → Actions → General → Allow GitHub Actions to create pull requests**). The file names to use are in the comment at the top of the script:
  - the EasyList / EasyPrivacy component files;
  - AdGuard Base (`adguard_base.txt`);
  - HaGeZi Pro (`hagezi_pro.txt`);
  - StevenBlack (`stevenblack_hosts`);
  - the Public Suffix List (`public_suffix_list.dat`).

### Project layout

| Path | Contents |
|---|---|
| `app/src/main/assets/` | Hub UI (`index.html`, `app.css`, `hub-core.js`, `known-games.js`), fonts, game-page scripts, ad-block data |
| `app/src/main/java/com/dailygames/hub/` | Kotlin sources (see Section 4) |
| `app/src/main/res/` | Icons, launch theme, strings, backup / network rules |
| `app/src/test/` | Kotlin tests, Node tests (`js/`), shared fixtures (`resources/`) |
| `tools/` | Ad-block list builder and its tests |
| `design/icons/` | Material Symbols PNG sources |
| `scripts/` | One-step GitHub publishing (`publish-to-github.sh` / `.ps1`) |
| `.github/workflows/` | CI tests on every push, and a signed release build when you push a `v*` tag |

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
- **Gold icon bevel**: `ic_icon_gold_fg.xml` adds a drop shadow, an inner bevel (the star outline stroked light and dark, offset ±1.1 and clipped to the star) and engraved sparkles; `ic_icon_gold_bg.xml` a warm radial glow. The legacy PNGs are rendered from the same design, and `appIconSvg` draws it for styles with `bevel: true`. `--gold-shine` has a bright specular band (`#F2E6BC`) between deep shadows.
