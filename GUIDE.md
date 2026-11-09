# Dailies 1.2.0: How the App Works

- **App name**: Dailies
- **Package ID**: `com.dailygames.hub`
- **Version**: `1.2.0` (`versionCode = 4`)
- **Android**: 7.0 (API 24) or newer; built for Android 17 (API 37)
- **Layout target**: large phones such as the Pixel XL line (about 448 CSS px wide), checked down to 360 px
- **Licence**: GPL-3.0 (see [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md))

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
| **Game player** | Full-screen player with Hub, Reload, Open in browser, an ad-block shield (with a blocked count) and Log. Supports fullscreen video and canvas, file uploads (Photo Picker), downloads, and "open in app" links. |
| **Auto-logging** | When you tap a game's **Share** or **Copy** button, the result is captured. Going back to the hub logs it as Solved (or Missed for a failed result such as "X/6"), with the share text as your score and an **Undo** button. |
| **Ad blocking** | About 302,000 ad, tracker and malware domains, 23,400 URL rules and 43,000 element-hiding rules, built from EasyList, EasyPrivacy, AdGuard Base, HaGeZi Pro and StevenBlack. You can switch it off per site or everywhere. |
| **Streaks & Log** | A completion calendar per game (best streak, current streak, plays) and the full score history, 5 results per page. Swipe or tap to change pages. |
| **Reset alerts** | A notification when a game's new puzzle is out, per-game on/off, grouped when several reset at once, and exact timing where Android allows it. |
| **Look** | Material 3 Expressive with Google Sans, Material Symbols icons, Material You wallpaper colours, an optional true-black AMOLED mode and compact cards. |
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
  - The name, icon, category and reset time fill in by themselves.
  - Anything you've already typed is never overwritten.
  - **✓ Reset time from built-in list** means the time came from the catalogue of popular games.
  - Pick the reset's time zone if the site doesn't say. The hint shows what that is in your time ("= 2:00 PM your time").
- **Game cards** show:
  - the icon, title and streak (🔥 7d);
  - the reset time in your local time;
  - a status pill (Ready / Done / Missed);
  - a live countdown and the action buttons.
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
  - **Reload**.
  - **Open in browser**.
  - **Shield**: shows how many requests were blocked. Tap it to allow ads on this game's site, or to block them again; the choice is remembered per game.
  - **Log**: go back and open the log form, pre-filled with any captured result.
- **Result capture** works two ways at once:
  1. **Clipboard**: anything the game copies while it's on screen is checked.
  2. **Page hooks**: a small script (`share-hook.js`) sees `navigator.clipboard`, `execCommand('copy')`, copy events, `DataTransfer.setData` and `navigator.share`. Each hook calls the original and returns its result unchanged, so games behave exactly as before.
- **What counts as a result**: an emoji grid, a score like "3/6" with context, or the game's name with a count such as "#512" or "4 guesses". Links, names and random text are ignored.
  - Only the game's own page can submit a result; ads and embedded frames can't.
  - The result is logged against the puzzle that was live when you opened the game.
- **Downloads**:
  - Web links download with the game's login: into Downloads on Android 10+, or through Android's **Save to…** picker on Android 7–9 (up to 25 MB). If that fails, the link goes to your browser.
  - Files the game builds itself (`blob:` and `data:` links, e.g. a "save image" button) open Android's **Save to…** picker. They can be up to 25 MB.
- **Links to other apps** (`intent://`) only open an installed app through a normal "view" request, or fall back to the web page the link names.

### Tab 2: Streaks & Log
- **Completion calendar**:
  - Pick a game, use ‹ › to change month, and **Today** to jump back.
  - The tiles show **Best streak**, **Current** and **Plays**.
  - **Score stats** below them: win rate, and for "3/6"-style scores the average and a bar for each number of guesses (X = failed).
- **Score history**:
  - Every result is listed newest first, 5 per page.
  - From the 6th result, a pager appears: tap ‹ › or swipe on the list.
  - Logging the same day again (✓ on the card) replaces that day's entry. Stats are recalculated from the history, so they go down as well as up.

### Tab 3: Settings
- **Display**:
  - Theme: Dark, Light or Match phone
  - Match Device Wallpaper Colors
  - AMOLED Pitch-Black Surface (dark theme only)
  - Compact Game Cards
  - Group Finished Games: today's done games move under a **Done today** header you can fold away (with the All filter)
- **Gameplay & Time**:
  - 12h / 24h clock
  - Seconds in countdowns
  - Ask to Log After Playing
  - Auto-scrape on paste
- **Game Player**:
  - Block Ads in Games (shows the rule counts)
  - Auto-Log Shared Results
- **Reset Alerts**:
  - The button reads **✓ Notifications enabled** when Android allows them. Otherwise it reads **Allow Notifications**, which asks for permission or opens the right Android settings page.
  - **Streak Reminder**: off, or 30 min to 6 hr before a reset. If a game you have a streak in isn't logged by then, one notification lists them (on its own "Streak Reminders" channel).
  - Below it, each game has its own alert toggle.
- **Data & Backup**: **Save Backup (.json)** and **Import Backup (.json or .txt)** through the Android file picker.
  - **Weekly Automatic Backup**: choose a folder and a backup in the same format is written there every week (`dailies_autobackup_<date>.json`, the newest 5 kept). **Back up now** and **Turn off** appear once it's on.
- **About**: the version, and an opt-in **Check for Updates** (once a day, one anonymous request to GitHub's releases API). A newer release shows a banner on the hub.

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
  - **Travelling west** (going back a day) doesn't wipe a logged result.
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
  - edge-to-edge, with predictive back and dynamic colours;
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
    - Sites marked `$generichide` / `$elemhide` / `$document` in the lists are honoured.
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
- **Travel**: travelling west no longer marks today as unplayed.
- **Long breaks**: gaps up to 10 years fill in correctly.
- **Stats** are recalculated from your history, so undoing or re-labelling a result can lower them.
- **Time zones**: zone names are normalised to the form Java accepts.

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
- The bar follows the **Match wallpaper colours** setting.

### Ad blocker
- **Rule priority**: `$important` blocks and `@@…$important` exceptions now follow the correct order.
- **List cleanup**:
  - `$badfilter` rules cancel what they name.
  - Trailing-dot rules (e.g. `||ads.`) and conditional exceptions are no longer mistreated.
  - `$document`, `$generichide` and `$elemhide` page exceptions are honoured.
- **Hosts and sites**:
  - URLs with `user:pass@` and IPv6 hosts are parsed correctly.
  - "Same site" uses the full Public Suffix List.
- **Request types**:
  - `$ping` and `$websocket` rules no longer hit ordinary fetches.
  - `$~xmlhttprequest` rules no longer hit requests of unknown type.
  - JSON requests are recognised as XHR.
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
- **Screenshots**: `ScreenshotReader` (ML Kit text recognition through Google Play services) runs on an image shared to Dailies or picked from the log dialog. This is the one non-free dependency; an F-Droid build would need a flavour without it.
- **Dark game pages**: `WebSettingsCompat.setAlgorithmicDarkeningAllowed`. The game player uses `Theme.Dailies.Light` in the light theme so pages get `prefers-color-scheme: light`.
- **Tab swipes**: the page follows the finger, and `animateTabChange` slides between views with the Web Animations API (with a timeout fallback). Reduced-motion settings are respected.
- **App icon**: adaptive icons in six launcher entries (`activity-alias`). `.MainActivity`, the 1.x launcher name kept so home-screen shortcuts survive the update, is the default: it uses `-night` and `-v31` colours, so it follows the phone's dark theme and wallpaper. With **App Icon Matches Theme** on, `AppIcon` enables the entry for the app's theme when you leave the app. The activity itself is now `HubActivity`.
- New tests: `AppIconTest`, `GameListCodecTest`, Node tests for the stats helpers, and device tests that tap the Done-today row with real touch events and open a shared list link.
