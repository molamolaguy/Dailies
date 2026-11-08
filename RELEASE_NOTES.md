# Dailies 1.0.0

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
