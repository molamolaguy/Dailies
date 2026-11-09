# Dailies

**One home for every daily web puzzle you play.** An Android app with no account and no tracking.

**[Download the latest APK](../../releases/latest)** · Android 7.0+ · [Release notes](RELEASE_NOTES.md) · [How it works](GUIDE.md)

## What it does
- **Tracks resets** in each game's own time zone, with a live countdown to the next puzzle.
- **Alerts you** when a new puzzle is out.
- **Plays games ad-free** in a built-in player.
- **Logs results automatically** when you tap a game's Share button.
- **Keeps your streaks** with a calendar and your full score history.
- **Keeps your data on your phone**: no server, no analytics, with `.json` backup and import.

## Install
1. On your phone, open the [latest release](../../releases/latest) and download `Dailies-<version>.apk`.
2. Open the file and allow installing when Android asks.

Updates are signed with the same key, so they install over the previous version and keep your data.

<details>
<summary>Signing certificate SHA-256</summary>

```
5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF
```
</details>

## Build from source
Needs JDK 17+ and the Android SDK (platform 37). Node.js 18+ is only needed for the JS tests.

```bash
./gradlew testDebugUnitTest testHubJs assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Signed releases, CI and the ad-block lists are covered in [GUIDE.md § 5](GUIDE.md#5-building-from-source).

## Licence
GPL-3.0 ([LICENSE](LICENSE)). Bundled filter lists, fonts and icons keep their own licences; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
