# Dailies web app

The same hub as the Android app (`app/src/main/assets`), packaged as an installable web app (PWA)
and published with GitHub Pages at **https://molamolaguy.github.io/Dailies/**.

## Setting it up (once)

In the repository on GitHub: **Settings → Pages → Build and deployment → Source: GitHub Actions**.
After that, every merge to `main` that touches the hub or `web/` publishes it (workflow
`.github/workflows/pages.yml`; it can also be run by hand from the Actions tab).

## Installing it

- **iPhone / iPad**: open the link in Safari (or, on iOS 16.4 and later, Chrome, Edge or Firefox),
  tap **Share → Add to Home Screen**. The page shows this hint the first time.
- **Android / desktop Chrome or Edge**: the page offers **Install**, or use the browser menu.

## How it differs from the Android app

| | Web app |
|---|---|
| Hub, streaks, calendar, stats, recaps, bests, achievements, themes, gold | Same |
| Playing a game | Opens in a new tab. Coming back to Dailies asks **Finished?** with Solved, Missed or **Paste** (reads the result you copied in the game; iPhone asks to allow Paste) |
| Automatic result capture, Play all, ad blocking, dark game pages | Android only (they need the app's game player) |
| Reset notifications and streak reminders | Not yet (web push needs a small server) |
| Wallpaper colors, app icon choice, weekly automatic backup, update check, screenshot reading | Android only |
| Data | Stays in the browser on that device (`localStorage`); the page asks the browser to keep it. Use **Save Backup** / **Import Backup** to move data between devices or to the Android app |
| Offline | Works offline after the first visit (service worker) |

## Building it locally

```
node web/build.mjs           # writes dist-web/
node web/check-build.mjs     # checks every linked / cached file exists
python3 -m http.server 8765 --directory dist-web   # then open http://localhost:8765/
```

## Files

- `build.mjs` copies the hub into `dist-web/`, adds the web head tags (manifest, icons, iPhone
  settings), `web.js` and `web.css`, versions the file URLs (`?v=<hash>`) and fills in `sw.js`.
- `web.js` opens games in a tab and reports the return to the hub (`window.onReturnFromGame`),
  registers the service worker, asks for persistent storage and shows the install hint.
- `sw.js` caches the app shell: pages network-first, files cache-first.
- `manifest.webmanifest`, `icons/` (the default launcher icon; `maskable-512.png` has extra margin
  for Android's icon shapes).
- In the hub itself, `IS_WEB` (`!window.AndroidBridge`) and `html.web` hide `.android-only` parts and
  show `.web-only` ones.
