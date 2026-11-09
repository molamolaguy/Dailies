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
