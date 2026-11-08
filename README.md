# Dailies

**One home for every daily web puzzle you play.** Dailies tracks when each game resets, alerts you
when a new puzzle is out, opens games in a built-in ad-blocking player, logs your results automatically
when you tap a game's Share button, and keeps your streaks and score history: all on your phone, with
no account and no tracking.

- **Download:** [latest release](../../releases/latest) → `Dailies-1.0.0.apk` (Android 7.0+)
- **How it works & what's in 1.0.0:** [GUIDE.md](GUIDE.md)
- **Release notes:** [RELEASE_NOTES.md](RELEASE_NOTES.md)

## Features

- **Daily Hub**:
  - Add any daily game by pasting or sharing its link; its name, icon and reset time are filled in for you.
  - Live countdowns, streaks, and Ready / Done / Missed status.
  - Drag to reorder.
- **Correct reset times**:
  - Each reset is stored in the zone the game uses (local, UTC or e.g. New York) and shown in your local time.
  - Daylight saving and travel are handled.
- **Game player**:
  - Ad blocking: about 300k ad and tracker domains, 23k URL rules and 43k element-hiding rules, plus stand-ins for common ad scripts.
  - Fullscreen, uploads and downloads.
  - A per-site shield.
- **Auto-logging**: tap Share or Copy in a game and the result is logged with your score text, with Undo.
- **Streaks & Log**: a completion calendar per game, best and current streaks, and the full score history.
- **Reset alerts**: a notification when a new puzzle is out, per game, grouped.
- **Material 3 Expressive**:
  - Google Sans and Material You wallpaper colours.
  - True-black AMOLED mode and compact cards.
- **Your data stays yours**:
  - Unlimited history, stored atomically on the device.
  - `.json` backup export and import.
  - No server, no analytics.

## Install

1. Download the APK from **Releases** on your phone.
2. Open it and allow installing from your browser or file manager when Android asks.
3. Updates from this repository are signed with the same key and install over the previous version.

**Signing certificate SHA-256:**

```
5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF
```

## Build from source

You need JDK 17+ and the Android SDK (platform 37). Node.js 18+ is optional; it's only used for the JS tests.

```bash
./gradlew testDebugUnitTest testHubJs assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`. For signed release builds, CI and refreshing the
ad-block lists, see [GUIDE.md § 5](GUIDE.md#5-building-from-source).

## Licence

GPL-3.0. See [LICENSE](LICENSE). The bundled filter lists, fonts and icons keep their own licences, listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
