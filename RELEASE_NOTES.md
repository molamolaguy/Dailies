# Dailies 1.10.2

Daily Hub spacing, Gold & Ruby theme polish, navigation bar stability, numeral kerning, and monochrome achievement badges tuned on Google Pixel 11 Pro XL. It installs over 1.10.1 and keeps everything, every setting included. If you're on 1.10.1, you can install it from **Settings → About → Check now**.

## Changes
- **Balanced Daily Hub spacing & default All view.** Restored clean vertical and horizontal breathing room between the **Today's Progress** hero card, the **All / Ready / Done / Search / Sort** row, game cards, inline action buttons, and the split floating bottom dock, and ensured the Daily Hub always opens on **All**.
- **Brushed Champagne Gold match, concentric Ruby & Gold rims, and steady nav labels.** Synchronized the **Gold** theme palette directly to the Gold app icon's Brushed Champagne tones (`#CBA854` / `#EBD796` / `#FFF8DF` / `#8C6D28`), unified all filled **Ruby** and **Gold** buttons onto a concentric `1px` `border-box` rim with centered glow (eliminating top-edge crescents), and switched the sliding tab indicator to translucent burnished-gold / star-ruby glass so bottom navigation text never flips color while swiping.
- **Crisp proportional numerals, monochrome achievements & streamlined Recap.** Replaced gradient-clipped and fixed-width `tabular-nums` streak/stat counters with uniformly lit proportional `Google Sans Flex` numerals (fixing the `"1"` in `21d` while keeping live countdown clocks tabular), converted *On a roll* and *Speed run* achievements to monochrome outline SVG badges so all 24 achievements match, and removed the **Share text** / **Share card** buttons from **Weekly Recap**.

## Requirements
- Android 7.0 or newer.

---

# Dailies 1.10.1

UI, motion, layout, and theme refinements tuned for Google Pixel 11 Pro XL (and smooth down to 360 px). It installs over 1.10.0 and keeps everything, every setting included. If you're on 1.10.0, you can install it from **Settings → About → Check now**.

## Changes
- **Persistent, interactive header & cleaner Daily Hub.** Pinned the **Dailies** header (`#appHeader`) across all three tabs with interactive **Ready** (tap to filter or jump back to ready games) and **Top streak** (tap to jump to your highest-streak game's calendar on **Streaks & Log**) pills alongside **Results**; made the Filter / Search / Sort row non-sticky and seamless; removed card `X` delete buttons; hid `↑`/`↓` reorder arrows outside **Custom order** sort; and allowed **Compact** game cards to wrap full names cleanly without truncation.
- **Zero-stretch boundaries & smoother transitions.** Eliminated elastic over-stretch at scroll and horizontal swipe limits across Android views, main tabs, Calendar, Recap, and Score History; rebuilt Calendar month transitions with a flicker-free ghost cross-slide across constant 6-row grids; and batched Settings accordion expand/collapse into a single smooth layout pass.
- **Monochrome SVG icons, Streaks & Gold/Ruby surface polish.** Kept `🔥` exclusively for streaks and `⚡` exclusively for fastest completion times while replacing every other UI emoji across Achievements, What's New, Recap, cards, toasts, and shared digests with crisp monochrome outline SVGs and clean markers; kept **Achievements** open below **Score History**; paginated **Score History** by whole calendar day (`HubUiHelpers.paginateHistoryByDay`); added modal safe-area clearance; and added warm obsidian **Gold** and wine-obsidian **Ruby** surface undertones synchronized across the native game player and first-frame boot.

## Requirements
- Android 7.0 or newer.

---

# Dailies 1.10.0

A Material 3 Expressive design overhaul, matching preset launcher icons for every color theme, and comprehensive reliability and security hardening. It installs over 1.9.6 and keeps everything, every setting included. If you're on 1.9.6, you can install it from **Settings → About → Check now**.

## Changes
- **Material 3 Expressive redesign.** Bundled authentic rounded `Google Sans Flex` (`ROND=100`) fonts across the hub, web app, and native game player; added a **Today's Progress** hero summary card with integrated **Play all**, standalone `26px` tonal game cards with `32%` squircle icons, and a split floating 3-tab pill dock paired with a `+` **Add game** button.
- **Expressive native game player.** Toolbar and sheet pills gently morph on touch; the **Game** switcher, **More** menu, and **Page zoom** dialog now use rounded tonal sheets instead of rectangular system menus; and in-game `<select>` menus match the expressive styling across Dark, Light, AMOLED, High Contrast, Gold, and Ruby.
- **Button, switch & menu alignment overhaul.** Aligned game card actions onto a uniform horizontal centerline, upgraded Settings checkboxes to Material 3 pill toggle switches, fixed multi-column Settings alignment on wider screens, right-aligned compact dropdowns so they never overhang card edges, and unified all 21 hub modals.
- **Matching app icons for all 14 themes.** Added adaptive and legacy preset launcher icons for Iris, Forest, Rose, Teal, Amber, Crimson, Cobalt, Sage, Plum, and Slate alongside Ocean, Sunset, Gold, and Ruby — automatically rotating in sync when **Daily Random Theme** is enabled.
- **Session persistence, auto-logging, ad-blocking & stability fixes.** Active in-flight game sessions now persist across notification taps and activity restarts; `#` puzzle numbers in shared links (such as `Queens #540`) are preserved; and ad-blocking wildcard rules, headless scraping, SafeHttp DNS/redirect checks, streak reminders, and atomic state backups are faster and more resilient.

## Requirements
- Android 7.0 or newer.

---

# Dailies 1.9.6

More color themes, daily random theme rotation, a remade Gold icon, and UI polish. It installs over 1.9.5 and keeps everything, every setting included. If you're on 1.9.5, you can install it from **Settings → About → Check now**.

## Changes
- **7 new Color Themes.** Added Teal, Amber, Crimson, Cobalt, Sage, Plum and Slate alongside Iris, Ocean, Forest, Sunset and Rose.
- **Gold & Ruby in the Theme picker.** Unlocked Gold and Ruby themes now appear right inside **Settings → Display → Theme** instead of a separate Accent menu.
- **Daily Random Theme.** Turn on **Daily Random Theme** in Theme settings to rotate through your unlocked pre-made color themes each day (without repeating yesterday's), with the app icon automatically matching.
- **Brushed Champagne Gold icon.** Rebuilt the Gold app icon with crisp pure-vector edges, a silky diagonal specular sheen, and a centered elevation shadow across all launcher densities.
- **UI polish.** Fixed the calendar's today glow clipping on Sunday/Saturday edges and aligned the Reset Alerts card border and button glow with standard app theme colors.

## Requirements
- Android 7.0 or newer.

---

# Dailies 1.9.5

A visual refresh for the Ruby and Gold unlocks. It installs over 1.9.4 and keeps everything, every setting included. If you're on 1.9.4, you can install it from **Settings → About → Check now**.

## Changes
- **Radiant Star Ruby accents.** Replaced the coral-pink cabochon Ruby finish with a deep faceted crimson palette, crisp prism rims, vivid crimson glow, high-contrast Light mode tones, and a subtle sheen sweep on primary buttons (disabled when Reduce Motion is on).
- **Crystalline Prism Ruby icon.** Redesigned the Ruby app icon as a 4-plane geometric crystal with a central diamond table, crimson under-glow, and luminous white glints across all launcher densities and the hub picker.
- **Gold accent polish.** Fixed unlocked achievement cards missing their gold border and aligned Gold's completed status pill with the horizontal gold sheen used on buttons.

## Requirements
- Android 7.0 or newer.

---

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
