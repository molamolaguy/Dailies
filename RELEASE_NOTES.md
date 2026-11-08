# Dailies 1.0.1

A bug-fix update. It installs straight over 1.0.0 and keeps all your games, streaks and history.

## Logging and streaks
- A failed puzzle (e.g. Wordle "X/6", "Next time", a lost Connections grid) is now logged as **Missed**, not Solved.
- Copied help text such as "Guess the Wordle in 6 tries" is no longer mistaken for a result.
- A result saved just after a reset goes to the puzzle you were looking at when you opened the form, so it no longer breaks your streak.
- Sharing a result to Dailies from another app logs it instead of reopening the game.
- Every quick log now has **Undo**, and the Undo toast stays up for 20 seconds.
- Tap a day in the calendar to correct a past result.
- A game added just before its reset is no longer marked Missed for that day.

## Editing, import and saving
- Editing a game's link no longer resets its name, reset time and zone.
- Changing a game's reset zone no longer shifts all its history by a day.
- Import asks before replacing your games, keeps the history of games not in the backup, skips bad entries instead of failing, and can be undone.
- Large backups show an error instead of crashing the app.
- A failed save is retried on the next save, and you're told if saving keeps failing.
- Removed games are grouped in the calendar, and their history can be forgotten.

## Game player
- Links that open a new window and sign-in popups (Google, Apple, Firebase, …) now work without replacing the game page.
- Hidden ads stay hidden after Reload and when moving between games on the same site.
- Downloads from a game's "Save image" button work, and go to Downloads (Android 10+) with your sign-in.
- Other apps and downloads can only be opened by a tap.
- The player reloads the game instead of closing if Android frees its memory.
- The toolbar leaves more room for the game's name, and its buttons are larger.

## Alerts
- Duplicate alerts after travelling west or setting the clock back are fixed.
- A reset missed while the phone was off is announced after it restarts.
- Alerts can now make a sound if you turn it on in Android's notification settings.
- Re-adding a game or turning its alert back on no longer posts a stale alert.
- Reset times are now correct in zones with unusual daylight-saving rules (e.g. Chatham, Lord Howe).

## Look and feel
- Countdowns are no longer cut off on 412 px-wide phones (e.g. Pixel 6–8).
- Large system font sizes no longer break the layout.
- Right-to-left game names, emoji names and plurals ("1 day") display correctly.
- Dark checkboxes and time pickers, Material You colours throughout, larger touch targets and better screen-reader labels.
- Calendar month and weekday names follow your language and first day of the week.
- The themed (monochrome) app icon keeps its two-tone look.

## Requirements
- Android 7.0 (API 24) or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Previously: Dailies 1.0.0

The first public release.

## Highlights
- **Daily Hub**:
  - Add games by link or the share sheet; name, icon, category and reset time are detected automatically.
  - A built-in list of reset times for 12 popular games.
  - Live countdowns, streaks, filters and drag-to-reorder.
- **Time-zone-correct resets**: stored in the game's own zone (local / UTC / named zone) and shown in your local time, including daylight-saving edge cases.
- **Game player with ad blocking**:
  - About 302k blocked domains, 23k URL rules and 43k element-hiding rules (EasyList, EasyPrivacy, AdGuard Base, HaGeZi Pro, StevenBlack).
  - `$important` / `$badfilter` support and Public Suffix List site matching.
  - Stand-in scripts for common ad libraries.
  - A per-site shield.
- **Auto-logging**: results are captured when you tap a game's Share/Copy button, logged with your score text, with Undo.
- **Streaks & Log**: calendar, best/current streak, plays, and paged score history.
- **Reset alerts**: exact where allowed, grouped notifications, per-game toggles.
- **Material 3 Expressive**: Google Sans, Material Symbols, Material You colours, AMOLED black.
- **Data**:
  - Unlimited on-device storage with atomic saves and a backup copy.
  - `.json` export/import.
  - Recovery banner if a save can't be read.

## Upgrading from a private 2.x build
1.0.0 uses a new signing key and version code 1, so Android won't install it over a 2.x build.
1. **Settings → Data & Backup → Save Backup (.json)** in the old app.
2. Uninstall it.
3. Install 1.0.0.
4. **Import Backup**.

## Requirements
- Android 7.0 (API 24) or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`
