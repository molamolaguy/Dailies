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
| **Dark game pages** | Game sites' own colours | Sites without a dark mode are darkened in the dark theme |
| **Games you've stopped playing** | Remove only | Archive: hidden, no alerts, history kept |
| **Editing many games** | One at a time | Select several: alerts on/off, reset time, archive |
| **Sharing your games** | Not possible | A link or QR code that adds the same games for a friend |
| **Your history** | `.json` backup | Also a `.csv` spreadsheet |
| **One-handed mode** | None | The page starts lower and dialogs open at the bottom |

## Also changed
- A simpler app icon in Material 3 colours.
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
- Text and icons that sat slightly off-centre (alert chips, bottom bar, game player toolbar).
- Game details and icons load through a phone proxy or VPN proxy.
- Downloads from games save inside the app on Android 7–9.
- Sharper, centred app icon on Android 7.

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
