# Dailies 1.9.4

A stability, security and reliability update. It installs over 1.9.3 and keeps everything, every setting included. If you're on 1.9.3, you can install it from **Settings → About → Check now**.

## Changes
- **Fixed sorting by "Next reset".** Sorting the hub by Next reset no longer crashes when you have two or more games.
- **More reliable saves, restores and edits.** Alternate links and embedded game URLs are kept across restarts; restoring or undoing a single game now updates today's status and score properly; editing a past day keeps its real streak; schedule changes no longer leave duplicate log rows; and changes flush to native storage immediately when leaving the app.
- **Smarter streak reminders and backups.** Broken streaks no longer send daily reminders, per-game streak reminders work even when reset alerts are muted, and Android Auto Backup / device transfers no longer copy device-specific backup folder grants or permission flags.
- **Faster, safer game player and scraper.** File exports, screenshot OCR and background preloading/rechecks run off the main thread; blocked popups no longer leak WebViews; headless scraping checks redirects against local/private addresses; icon refreshes only fetch lightweight HTML; and per-site ad-block and zoom settings persist reliably on `.vercel.app`, `.netlify.app` and other shared-host domains.
- **Ad-blocker and web app improvements.** Fixed `$important` rule precedence and `domain=foo.*` matching in the ad blocker, scoped the web app's service worker cache with offline fallback on server errors, and refreshed documentation and third-party notices.

## Requirements
- Android 7.0 or newer.

---

# Dailies 1.9.3

A small update. It installs over 1.9.2 and keeps everything, every setting included. If you're on 1.9.2, you can install it from **Settings → About → Check now**.

## Changes
- **A step-cut Ruby app icon.** The Ruby icon (Settings → Display → App Icon) is a step-cut gem now: smooth bands that follow the star's shape, each catching the light from the top left. The old wedge facets looked like a pinwheel of reds up close. If you're using the Ruby icon, your home screen picks up the new one after the update (some launchers take a moment or a restart).
- **Today stands out on the calendar.** Today's day on the Streaks & Log calendar has a brighter ring, a soft glow inside and a halo round it, in every theme (gold and ruby included).

## Requirements
- Android 7.0 or newer.
