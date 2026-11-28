# Dailies 1.11.0

Faster game preloading, distinct **Failed** vs. **Missed** outcome tracking, smarter duplicate game prevention across URLs and logged results, and more reliable automatic result capture & player polish. It installs over 1.10.3 and keeps everything, every setting included. If you're on 1.10.3, you can install it from **Settings → About → Check now**.

## Changes
- **Faster game preloading & idle cache warming.** Fixed same-origin ES `<script type="module">` CORS handling in `PageCache` so preloaded Vite, Webpack, and Next.js bundles load directly from cache; expanded subresource discovery (`import`/`export ... from`, Next.js `_buildManifest.js`, `modulepreload`, and CSS `url(...)`) across a parallel worker pool with raised cache budgets (`96` files / `18 MB` per game); scheduled reset preloading even for muted games; and added a detached, script-disabled Chromium HTTP/TLS cache warmer (`GamePrewarmer`) while the hub is idle.
- **Distinct Failed vs. Missed outcome tracking.** Separated attempted losses (**Failed** — such as `X/6`, 4 Connections mistakes, `"Next time"`, or selecting **Failed** in `#returnPrompt` / `#modalLogScore`) from unattempted or unfinished days (**Missed**). Both break the active streak (`0d`) and count against win rate without incrementing wins, while surfacing distinct amber **Failed** and rose **Missed** states across Daily Hub cards, the 4-tile Completion Calendar, day detail sheets, Score History filter chips, and shared digests; starting a game without finishing keeps it **Ready** while live and rolls over to **Missed** at reset.
- **Smarter duplicate game prevention (URLs & logged results).** Canonicalized entry subpaths (`/game`, `/play`, `/daily`, `/today`, etc.), wired cross-domain `KnownGames` aliases, and enforced duplicate checks across Add/Edit, Popular Games, Share → Dailies, shared friend lists, Trash restore, and JSON backup imports while keeping multi-game portals (`nytimes.com/games/wordle` + `/connections`, LinkedIn, Puzzmo, LoLdle modes) separate — plus added result-signature duplicate detection (`shareResultSignature` / `findDuplicateByResult`) offering to **Merge** or **Keep separate** when a newly added game's logged result matches an existing game's format, style, and puzzle epoch.
- **Below-the-fold result capture & quiet player re-entry.** Added unclipped below-the-fold button probing, debounced touch-scroll scanning, split-node completion header recognition (`"KRILLION Dive # 102 complete"`, `"The Mystery Country is ..."`), `Today's guesses` stat-pair matching, and `disabled`/`opacity` container transition watching so games like **Globle** and **Krillion** auto-capture reliably; re-entering a game already marked **Completed** (with a logged result) or **Failed** for the active cycle now hides the player's top-bar **Log** pill, disables the auto-logger and clipboard watcher, and skips the **Finished?** prompt on exit.
- **Stacked toast deck, Cloudflare scraper fix & UI polish.** Stacked simultaneous in-app notifications into a skewed card deck (`+N` badge + swipe-to-dismiss) where every toast runs an independent `4.5s` timer; stopped passive Cloudflare JSD telemetry scripts (`fe-guess.com`) from triggering false `"blocked (200)"` health alerts; kept the active filter unchanged when tapping the header **Ready** pill back to the Daily Hub; softened bottom dock backdrop blur by 20%; updated **Settings → Display → Theme** subtext; and configured side-by-side `Dailies Debug` (`.debug`) development builds.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.10.3

Smarter automatic result logging, upgraded URL site scraping for custom daily games, and the **My Results** tab update. It installs over 1.10.2 and keeps everything, every setting included. If you're on 1.10.2, you can install it from **Settings → About → Check now**.

## Changes
- **More reliable automatic result logging.** Extended post-input finish watching for games with 30–45s finish animations (such as Farflung), recognized word-based countdowns (`"opens in 9 h 12 m"`) and visibility/attribute modal transitions, filtered below-the-fold FAQ/SEO prose and live gameplay progress counters (`0/9 Fields`, remaining guesses) so in-progress games never trigger early result prompts, blocked Practice / Unlimited / Replay / Archive / `∞` rounds from logging as today's Daily, and added support for `aria-label` / icon-only Share buttons, `localStorage` completion cues, pre-populated share boxes, and two-step Share → Copy menus.
- **Smarter URL site scraping.** Fixed bare-origin relative bundle/manifest/icon URL resolution and prioritized app bundles over vendor scripts; expanded reset time & time zone extraction (`German time`, `Berlin`, `Toronto`, `Brazil`, Asian/Oceania zones, prepositions like `"in your local time"`, fixed `UTC±offset` conversion, and Luxon/Day.js bundle hints); added rich **Target Format / Par** detection (`37 guesses`, written numbers like `four mistakes` / `five prompts`, `50,000 points`, `Fastest time`, `Most points`, `Fewest guesses`, `Fewest strokes`); refined concise how-to-play descriptions; and switched category detection to weighted scoring with native **Gaming & RPG** and **Custom Daily** support.
- **My Results tab.** Renamed the middle navigation tab from **Streaks & Log** to **My Results**.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.10.2

Daily Hub spacing, Gold & Ruby theme polish, navigation bar stability, numeral kerning, and monochrome achievement badges tuned on Google Pixel 11 Pro XL. It installs over 1.10.1 and keeps everything, every setting included. If you're on 1.10.1, you can install it from **Settings → About → Check now**.

## Changes
- **Balanced Daily Hub spacing & default All view.** Restored clean vertical and horizontal breathing room between the **Today's Progress** hero card, the **All / Ready / Done / Search / Sort** row, game cards, inline action buttons, and the split floating bottom dock, and ensured the Daily Hub always opens on **All**.
- **Brushed Champagne Gold match, concentric Ruby & Gold rims, and steady nav labels.** Synchronized the **Gold** theme palette directly to the Gold app icon's Brushed Champagne tones (`#CBA854` / `#EBD796` / `#FFF8DF` / `#8C6D28`), unified all filled **Ruby** and **Gold** buttons onto a concentric `1px` `border-box` rim with centered glow (eliminating top-edge crescents), and switched the sliding tab indicator to translucent burnished-gold / star-ruby glass so bottom navigation text never flips color while swiping.
- **Crisp proportional numerals, monochrome achievements & streamlined Recap.** Replaced gradient-clipped and fixed-width `tabular-nums` streak/stat counters with uniformly lit proportional `Google Sans Flex` numerals (fixing the `"1"` in `21d` while keeping live countdown clocks tabular), converted *On a roll* and *Speed run* achievements to monochrome outline SVG badges so all 24 achievements match, and removed the **Share text** / **Share card** buttons from **Weekly Recap**.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.10.1

UI, motion, layout, and theme refinements tuned for Google Pixel 11 Pro XL (and smooth down to 360 px). It installs over 1.10.0 and keeps everything, every setting included. If you're on 1.10.0, you can install it from **Settings → About → Check now**.

## Changes
- **Persistent, interactive header & cleaner Daily Hub.** Pinned the **Dailies** header (`#appHeader`) across all three tabs with interactive **Ready** (tap to filter or jump back to ready games) and **Top streak** (tap to jump to your highest-streak game's calendar) pills alongside **Results**; made the Filter / Search / Sort row non-sticky and seamless; removed card `X` delete buttons; hid `↑`/`↓` reorder arrows outside **Custom order** sort; and allowed **Compact** game cards to wrap full names cleanly without truncation.
- **Zero-stretch boundaries & smoother transitions.** Eliminated elastic over-stretch at scroll and horizontal swipe limits across Android views, main tabs, Calendar, Recap, and Score History; rebuilt Calendar month transitions with a flicker-free ghost cross-slide across constant 6-row grids; and batched Settings accordion expand/collapse into a single smooth layout pass.
- **Monochrome SVG icons, Streaks & Gold/Ruby surface polish.** Kept `🔥` exclusively for streaks and `⚡` exclusively for fastest completion times while replacing every other UI emoji across Achievements, What's New, Recap, cards, toasts, and shared digests with crisp monochrome outline SVGs and clean markers; kept **Achievements** open below **Score History**; paginated **Score History** by whole calendar day (`HubUiHelpers.paginateHistoryByDay`); added modal safe-area clearance; and added warm obsidian **Gold** and wine-obsidian **Ruby** surface undertones synchronized across the native game player and first-frame boot.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

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
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

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
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.9.5

A visual refresh for the Ruby and Gold unlocks. It installs over 1.9.4 and keeps everything, every setting included. If you're on 1.9.4, you can install it from **Settings → About → Check now**.

## Changes
- **Radiant Star Ruby accents.** Replaced the coral-pink cabochon Ruby finish with a deep faceted crimson palette, crisp prism rims, vivid crimson glow, high-contrast Light mode tones, and a subtle sheen sweep on primary buttons (disabled when Reduce Motion is on).
- **Crystalline Prism Ruby icon.** Redesigned the Ruby app icon as a 4-plane geometric crystal with a central diamond table, crimson under-glow, and luminous white glints across all launcher densities and the hub picker.
- **Gold accent polish.** Fixed unlocked achievement cards missing their gold border and aligned Gold's completed status pill with the horizontal gold sheen used on buttons.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

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
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.9.3

A visual polish update for the Ruby app icon and calendar today marker. It installs over 1.9.2 and keeps everything, every setting included. If you're on 1.9.2, you can install it from **Settings → About → Check now**.

## Changes
- **Step-cut Ruby app icon.** Redesigned the Ruby icon (**Settings → Display → App Icon**) as a smooth step-cut gem with bands following the star's shape and catching light from the top left, replacing the earlier wedge facets.
- **Brighter Today highlight on the calendar.** Today's cell on the completion calendar now features a clearer ring, soft inner glow, and subtle outer halo across all themes (including Gold and Ruby).

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.9.2

An in-place accordion Settings update. It installs over 1.9.1 and keeps everything, every setting included. If you're on 1.9.1, you can install it from **Settings → About → Check now**.

## Changes
- **Settings sections open in place.** Tapping any section (**Display**, **Gameplay & Time**, **Game Player**, **Reset Alerts**, **Data & Backup**, **Archived Games**, **About**) expands its options directly on the page instead of navigating to a sub-screen.
- **One section open at a time.** Opening another section smoothly closes the previous one while keeping the tapped heading anchored in view.
- **Clean reset when leaving Settings.** Switching tabs, pressing Back, or tapping **Settings** again collapses open sections so Settings returns to its compact overview next time.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.9.1

A cleaner section-based Settings layout with inline descriptions restored. It installs over 1.9.0 and keeps everything, every setting included. If you're on 1.9.0, you can install it from **Settings → About → Check now**.

## Changes
- **Organized Settings sections.** Grouped Settings into **Display**, **Gameplay & Time**, **Game Player**, **Reset Alerts**, **Data & Backup**, **Archived Games** (when present), and **About**, each with a short summary line.
- **Quick Back navigation.** Return to the main Settings list via the top back button, the system Back gesture, or tapping **Settings** in the navigation bar; deep links into settings (such as **Import Backup** from a data alert) open the target section directly.
- **Inline setting explanations restored.** Removed the `ⓘ` popovers from 1.9.0 so every setting's description is visible directly beneath its label while keeping **Theme**, **App Icon**, and **Accent** as compact single rows.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.9.0

A more compact Settings layout and cleaner game cards on the Daily Hub. It installs over 1.8.13 and keeps everything, every setting included. If you're on 1.8.13, you can install it from **Settings → About → Check now**.

## Changes
- **Single-row Theme, App Icon & Accent pickers.** Consolidated **Theme** (dark/light/system, wallpaper colors, color themes, and pitch-black AMOLED), **App Icon**, and unlocked **Accent** (**Gold** / **Ruby**) into compact summary rows that open dedicated picker dialogs.
- **Tighter Settings spacing.** Reduced vertical padding across Settings rows (~25% shorter overall, and ~50% shorter in **Display**).
- **Cleaner Daily Hub game cards.** Removed the inline **PB** badge from Daily Hub cards so personal bests live cleanly under the completion calendar.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.13

Polished Ruby styling, duplicate-game detection across alternate links, and a quieter all-done celebration. It installs over 1.8.12 and keeps everything, every setting included. If you're on 1.8.12, you can install it from **Settings → About → Check now**.

## Changes
- **Smooth cabochon Ruby finish & even rims.** Replaced stepped red bands on Ruby buttons, navigation highlights, Done badges, calendar days, and progress bars with a smooth crimson gradient and uniform top-and-bottom rim outlines.
- **Duplicate game detection across redirects and embeds.** Adding a URL that redirects to, embeds, or sits on a parent/child path or matching title of a game already in your hub now flags the existing game with a quick **Open** button instead of silently adding a duplicate.
- **Quieter all-done celebration.** Removed the full-screen completion overlay when finishing your last game of the day while keeping the subtle header icon sparkle (configurable via **Settings → Display → Celebrate When All Done**).

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.12

Smarter end-screen result detection and faster achievement & history calculations. It installs over 1.8.11 and keeps everything, every setting included. If you're on 1.8.11, you can install it from **Settings → About → Check now**.

## Changes
- **Timers and countdowns are never mistaken for scores.** End-screen score prompts now require explicit completion phrasing (`Solved`, `You won`, `Well done`), ignore next-puzzle countdowns and ticking live clocks, require a label on time scores (never `0:00`), and prioritize guess counts over raw numbers or times.
- **Late end-screen detection.** Fixed the DOM observer startup timing so end screens appearing more than 8 seconds after your final move are reliably detected without rescanning on every clock tick.
- **Faster startup and stats with long histories.** Memoized cycle-date and reset-time lookups, halved achievement evaluation work, avoided redundant recalculations when saving earned badges, and deferred startup achievement checks until after initial tab swipes.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.11

Eliminates first-swipe frame drops right after launching the app. It installs over 1.8.10 and keeps everything, every setting included. If you're on 1.8.10, you can install it from **Settings → About → Check now**.

## Changes
- **Pre-warmed tab layers during launch.** While the launch screen is still visible, the hub quietly pre-slides across adjacent tab layers once out of sight so the first real finger swipe never stalls on initial rasterization (cutting worst-frame first-swipe times on emulator benchmarks from 150–500 ms down to 24–40 ms).
- **Automated frame-timing touch tests.** Added end-to-end emulator swipe instrumentation measuring per-frame render times across fast and slow bidirectional tab swipes.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.10

Instant first-frame response on fast flick swipes between tabs. It installs over 1.8.9 and keeps everything, every setting included. If you're on 1.8.9, you can install it from **Settings → About → Check now**.

## Changes
- **Zero-jump fast flicks.** Moved tab-swipe preparation to initial touch-down rather than the first movement frame, eliminating the ~37 ms catch-up jump on quick flicks.
- **Pre-positioned off-screen pages & cached tab bounds.** Adjacent pages stay staged in position without restyling on swipe start, and the navigation highlight no longer forces a synchronous layout pass on landing.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.9

Velocity-matched tab swipe release physics and lighter per-frame drag work. It installs over 1.8.8 and keeps everything, every setting included. If you're on 1.8.8, you can install it from **Settings → About → Check now**.

## Changes
- **Smooth velocity-matched release.** Lifting your finger during a tab swipe now carries the page forward seamlessly at your finger's release velocity instead of pausing for a frame and lurching.
- **Half the per-frame work while dragging.** Driven by scrubbed Web Animations layers, single-pass viewport/tab measurements, and delayed card press states so horizontal swipes starting on a game card never trigger press animations (~16 ms down to ~7 ms per drag frame under 6× CPU throttling).
- **Exact sub-pixel landing & layered sub-pagers.** Pages and the bottom-bar highlight settle on exact pixel bounds, and Calendar, Score History, and Recap swipes promote moving content onto dedicated compositor layers.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.8

Persistent compositor layers for smoother swipes, reliable themed menus in games, clear glass bar support for Gold & Ruby, and centered button rims. It installs over 1.8.7 and keeps everything, every setting included. If you're on 1.8.7, you can install it from **Settings → About → Check now**.

## Changes
- **Persistent page layers & scroll-synced staging.** Each tab keeps its own compositor layer permanently and stages off-screen at the active scroll offset, cutting raster work during swipes by 25–45%.
- **Reliable themed `<select>` menus inside games.** Handled game-page dropdown taps on touch-up so Android's native WebView picker never intercepts the tap before Dailies' themed menu opens.
- **Truly clear bottom bar & balanced Gold/Ruby rims.** Faded Gold and Ruby border tints and glows cleanly down to 0% at full bottom-bar transparency, and equalized top/bottom rim lighting on Gold and Ruby buttons.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.7

Cleaner Score History rows, streamlined Settings styling, themed dropdown menus inside games, and flicker-free tab/theme transitions. It installs over 1.8.6 and keeps everything, every setting included. If you're on 1.8.6, you can install it from **Settings → About → Check now**.

## Changes
- **Compact one-line Score History rows.** Folded history entries now show the game, streak, play time, and outcome on a single clean line until tapped to expand, while **Recent changes** moved to **Settings → Data & Backup**.
- **Cleaner Settings, squircle icons & glass bottom bar.** Replaced boxed Settings rows with crisp dividers, unified Daily Hub icons onto a consistent rounded-square shape, turned the bottom bar into clear glass as transparency increases, and removed legacy Card Style and single-game backup options.
- **Themed dropdown menus in the game player.** HTML `<select>` menus on game sites now open Dailies' own themed menu sheet instead of the stock Android picker.
- **No background flash on tab or theme switches.** Fixed the ambient background glow to the viewport and synchronized button color transitions when switching themes.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.6

Smoother animations, targeted DOM updates, and idle-batched state saves across the app. It installs over 1.8.5 and keeps everything, every setting included. If you're on 1.8.5, you can install it from **Settings → About → Check now**.

## Changes
- **Gliding folds & targeted list redraws.** Folding **Done today**, Settings sections, and Achievements now animates smoothly without rebuilding unaffected cards, and paging or filtering **Score History** updates only the history list.
- **Idle-deferred persistence & smarter countdown ticks.** Deferred disk writes until animations settle (flushing immediately when opening a game or backgrounding the app), paused countdown DOM updates during tab drags, and ticked minute-only countdowns once per minute when seconds are hidden.
- **Slider scroll guard & lighter celebration.** Prevented vertical page scrolls starting on the **Bottom Bar See-Through** slider from altering its value, and removed the heavy per-frame full-screen blur from the completion celebration.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.5

Weekly background checks for game-page updates, an adjustable see-through bottom bar, and smoother first swipes. It installs over 1.8.4 and keeps everything, every setting included. If you're on 1.8.4, you can install it from **Settings → About → Check now**.

## Changes
- **Check Game Pages for Changes.** Optional weekly Wi-Fi background check (**Settings → Gameplay & Time**, plus manual **Check now**) that re-reads your games' pages and asks before applying any updated title, description, icon, reset schedule, score format, category, or moved domain.
- **Bottom Bar See-Through slider.** Added a **Solid → Clear** transparency slider in **Settings → Display** (defaulting to 75%).
- **Readable shared digests, roomier Recaps & centered glows.** Added a blank spacer line between games when copying/sharing all of today's results, increased line spacing in Weekly and Monthly Recaps, and centered Gold and Ruby button glows.
- **Stutter-free first swipe to Settings.** Pre-rendered all off-screen tabs while idle and halted background pre-rendering immediately on touch-down.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.4

Faster glass card rendering and pre-drawn adjacent pages for smoother tab swipes. It installs over 1.8.3 and keeps everything, every setting included. If you're on 1.8.3, you can install it from **Settings → About → Check now**.

## Changes
- **Lightweight Glass cards.** Replaced expensive live backdrop blurs on Glass cards with a crisp translucent tint and bright rim (~3× faster to composite during swipes).
- **Pre-drawn neighboring tabs & deferred background work.** Adjacent tabs are rendered ahead of time off-screen and non-urgent tasks pause while a swipe is in progress.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.3

Headless JavaScript page rendering when adding games, broader automatic result capture, automatic score-format learning, and 8 more built-in games. It installs over 1.8.2 and keeps everything, every setting included. If you're on 1.8.2, you can install it from **Settings → About → Check now**.

## Changes
- **Rendered single-page app scraping & 8 new catalog games.** Adding a URL now also renders the live page with scripts enabled (`PageRenderer`), reads web-app manifests, JSON-LD, and JS bundle time-zone hints, extracts concise how-to-play descriptions, and adds Blossom, Keyword, WhenTaken, Sedecordle, Duotrigordle, Spotle, Chronophoto, and Globle: Capitals to the built-in catalog.
- **Automatic score-format learning.** Leaves unknown score formats on **Daily Puzzle** instead of guessing, then automatically learns the game's format (`6 guesses`, `Fastest time`, `Most points`, `Fewest moves`) after a few logged results (with Undo).
- **Broader in-game result detection.** Opens intermediate `"See results"` / `"Stats"` buttons to find hidden Share buttons, captures scores shared via X / WhatsApp / Telegram / SMS / mailto links, reads open web components and 6 European languages, and offers end-screen scores (`Log it / Not now`) for games without a Share button.
- **UI & notification cleanup.** Removed duplicate notification icons, switched all Hub dropdowns to themed custom menus, streamlined the Daily Hub action bar to **Play all**, and enabled horizontal swiping on Recap stat tiles.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.2

Fixes bundled multi-game reset alerts, holds the launch screen until the hub is ready, and smooths tab transitions. It installs over 1.8.1 and keeps everything, every setting included. If you're on 1.8.1, you can install it from **Settings → About → Check now**.

## Changes
- **Bundled reset alerts stay visible.** Fixed a bug where simultaneous multi-game `"games are ready"` notifications were immediately canceled upon posting.
- **No blank flash on app launch.** Kept the native launch screen visible until the Daily Hub finishes its initial render so your games appear right away.
- **Smoother tab & calendar transitions.** Kept neighboring tabs ready before touch movement starts and fixed the completion calendar shifting vertically after a swipe.
- **Retired Daily Challenge.** Removed the Daily Challenge banner, setting, and Challenger secret achievement; Ruby now unlocks with the remaining five secret achievements while preserving existing unlocks.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.8.1

Day-grouped Score History rows, twice-as-fast startup with large histories, and smoother tab sliding. It installs over 1.8.0 and keeps everything, every setting included. If you're on 1.8.0, you can install it from **Settings → About → Check now**.

## Changes
- **Day-grouped Score History.** Grouped logged results under day headers (`Today`, `Yesterday`, `Fri, Nov 13`) with daily solve counts, compact single-row previews, expandable full share text with **Copy**, and 8 entries per page.
- **2× faster startup with long histories.** Deferred achievement checks, full time-zone list population, and puzzle-number reset validation until after the first screen is interactive.
- **Smoother tab switching.** Removed the initial jump when sliding between tabs and sped up returning from the game player to the hub.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.7.0

Secret achievements with the Ruby icon & accents, custom app icon colors, typography & contrast controls, Wi-Fi game preloading, and reliable reset alerts. It installs over 1.6.1 and keeps everything, every setting included. If you're on 1.6.1, you can install it from **Settings → About → Check now**.

## Changes
- **Secret achievements & Ruby unlock.** Added hidden **`???`** secret achievements that unlock the faceted 3D **Ruby** launcher icon and **Ruby** theme accents, plus upgraded **Gold** to a polished champagne finish with crisp beveled edges.
- **Custom app icons, fonts, text sizes & High Contrast.** Added a **Custom** app icon editor (usable in-app and pinnable to the Android home screen), **Mono / Rounded / Classic** countdown fonts, **Normal / Large / Larger** text scaling, **High Contrast** mode, and card surface styles.
- **Wi-Fi game preloading after reset.** Optionally preloads a game's assets on Wi-Fi after its daily reset so it opens faster, automatically cleaning up once logged (**Settings → Game Player → Preload Games After Reset**).
- **Reliable reset alerts & sticky Hub controls.** Ensured reset notifications always post even if Dailies was open at reset time (using your selected app icon accent), pinned the Hub header and filter bar, and added swipe-to-dismiss on banners.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.6.1

Modal scroll-lock fixes, clearer wallpaper color controls, a translucent navigation bar, and Web PWA polish. It installs over 1.6.0 and keeps everything, every setting included. If you're on 1.6.0, you can install it from **Settings → About → Check now**.

## Changes
- **Fixed modal close scroll lock.** Closing Achievement details, **Today's results**, or **Recent changes** via `✕` or Back no longer leaves the underlying page unable to scroll or tap.
- **Clearer Wallpaper vs Color Theme toggle.** Separated **Match Device Wallpaper Colors** from the preset Color Theme swatches, graying out manual swatches while wallpaper matching is active.
- **Translucent bottom bar & clean Web App title.** Made the bottom navigation bar subtly see-through and simplified the Web PWA tab title at `molamolaguy.github.io/Dailies` to **Dailies**.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.6.0

Play all mode, Recent changes undo history, Weekly & Monthly Recaps, Personal Bests, Hub sorting, combined daily result sharing, and Color Themes. It installs over 1.5.0 and keeps everything, every setting included. If you're on 1.5.0, you can install it from **Settings → About → Check now**.

## Changes
- **Play all & Hub sorting.** Added **Play all** to step through every unplayed game automatically after each captured result, plus Hub sorting by **Custom order**, **Next reset**, **A–Z**, or **Least played**.
- **Weekly & Monthly Recaps + combined daily Results digest.** Added a swipeable **Week / Month** recap card tracking solved count, win rate, best streak, top game, and puzzling time, plus a **Results** button in the Hub header to copy or share all of today's solved games in one message (with optional emoji-grid toggle).
- **Recent changes (undo history) & Personal Bests.** Added an undo log for recent logs, edits, removals, and archives, along with per-game best score, fastest time, longest streak, and tough-day tracking.
- **Color Themes, finger-tracking nav indicator & themed pickers.** Added **Iris**, **Ocean**, **Forest**, **Sunset**, **Rose**, and **Custom** color themes across Dark, Light, AMOLED, and the native player; made the bottom-bar highlight track your finger live during swipes; recognized plain-DOM `"Copy result"` buttons (such as Krillion); and replaced native game dropdowns in Calendar and Score History with themed menus.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.5.0

Two-tier achievements, unlockable Gold accents, bundled multi-game reset alerts, quick removed-game restore, and collapsible sections. It installs over 1.4.0 and keeps everything, every setting included. If you're on 1.4.0, you can install it from **Settings → About → Check now**.

## Changes
- **19 two-tier achievements & Gold accents unlock.** Expanded Achievements to 19 two-tier badges (38 tiers total, carrying over all previously earned badges) and added unlockable metallic **Gold** app-wide accents alongside the **Gold** launcher icon (persisted across backups).
- **Combined multi-game reset notifications.** When multiple games reset together, Dailies now posts a single clean summary notification listing the ready games instead of separate alerts.
- **Quickly re-add removed games.** **Add Game** now surfaces your 5 most recently removed games so you can restore them in one tap with their full streaks and history intact.
- **Collapsible sections & preserved scroll position.** Made Settings and Achievements sections collapsible and preserved relative scroll position when switching tabs.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.4.0

Automatic end-screen result probing, in-player game switching, per-site page zoom, reset-schedule suggestions, 59 built-in popular games, and selectable app icons. It installs over 1.3.0 and keeps everything, every setting included. If you're on 1.3.0, you can install it from **Settings → About → Check now**.

## Changes
- **Find Results Automatically.** When a game reaches its end screen, Dailies quietly probes its Share / Copy button in the background to capture your score without writing to your clipboard or opening share sheets (configurable in **Settings → Game Player**), and recognizes LinkedIn, NYT Mini, Quordle, and word/clue/stroke formats while ignoring non-game clipboard text.
- **In-player game switcher, Next button & per-site Zoom.** Tap the game's title in the player to jump to any of today's games, tap **Next** (`⏭`) for the next unplayed game, or adjust **⋮ → Zoom** (50–200%, remembered per site).
- **59 built-in popular daily games & smart reset-time suggestions.** Added a searchable catalog of 59 daily games with known links and reset schedules, plus automatic suggestions when logged puzzle numbers show a game's reset clock has shifted.
- **Searchable Score History, Copy Today & new App Icons.** Added full-text search and finger-tracking pagination in Score History, one-tap copy for all of today's solved games, and **Midnight**, **Sunset**, **Ocean**, **Mono**, and unlockable **Gold** app icons.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.3.0

In-app APK updates, Play next workflow, per-game streak reminders, richer score stats, and finger-tracking tab & calendar swipes. It installs over 1.2.0 and keeps your games, streaks, and history (install 1.3.0 once by downloading the APK; future updates can install directly from **Settings → About → Check now**).

## Changes
- **Direct in-app updates.** **Settings → About → Check now** now downloads and installs new signed APK releases right inside Dailies (Android prompts once to allow *Install unknown apps*), automatically cleaning up downloaded APKs on next launch.
- **Play next, richer score formats & per-game streak reminders.** Added an **Up next** prompt for unplayed games, expanded personal bests and averages to cover times (`1:42`), points (`2,450 pts`), metres, percentages, and counts (`45 guesses`), checked clipboard results when returning from or closing the player, and added per-game streak reminder lead times.
- **Finger-tracking swipes, day-detail popovers & Score History filters.** Tab and calendar month swipes now track your finger smoothly, tapping a calendar day previews its logged result with **Copy** and **Edit**, **Score History** filters by game and outcome, **Done today** starts folded, and the default app icon uses the white-and-star design.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.2.0

Per-puzzle play timing, Personal Bests, Achievements, Monthly Recaps, on-device screenshot OCR, dark game pages, game archiving, QR list sharing, and CSV export. It installs over 1.1.0 and keeps your games, streaks, and history.

## Changes
- **Play time, Personal Bests, Achievements & Monthly Recaps.** Tracks active play time per puzzle (fastest and average), highlights new personal bests in score, time, and streak, introduces 14 achievement badges, and adds a shareable Monthly Recap summary.
- **Read results from screenshots & darken bright game sites.** Share or pick a result screenshot for on-device text recognition (powered locally via Google Play services), and automatically darken bright game websites when using Dailies' dark theme.
- **Game archiving, multi-game editing, QR sharing & CSV export.** Archive games you're taking a break from without losing their history, batch-edit reset schedules or alerts, share your game list with friends via link or QR code, and export your full log history as a `.csv` spreadsheet.
- **Material 3 flower-and-sparkle app icon & smoother tab swipes.** Introduced the adaptive Material 3 flower-star launcher icon with optional theme matching and smooth finger-tracking tab transitions.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.1.0

Light & system themes, foldable finished games, per-game win rate & score stats, streak reminders, automatic weekly folder backups, and update checks. It installs over 1.0.x and keeps your games, streaks, and history.

## Changes
- **Dark, Light & Match-phone themes + grouped finished games.** Added Light mode and automatic system theme following, and grouped completed games into a foldable **Done today** section.
- **Win rate, average score & streak reminders.** Added per-game win rate and average score statistics, plus optional reminders before a puzzle resets when an active streak hasn't been logged yet.
- **Scheduled weekly backups & GitHub update checks.** Added automatic weekly `.json` backups to a folder you choose (keeping the latest 5 copies) and optional daily checks for new releases on GitHub.
- **Ad-blocked sign-in popups, proxy support & Android 7–9 downloads.** Applied full ad blocking to OAuth/popup WebViews, improved scraping and icon loading over device VPNs/proxies, enabled in-app downloads on Android 7–9, and sharpened icon alignment across devices.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.0.1

A bug-fix and reliability update for result logging, reset rollovers, backups, popups, and notifications. It installs over 1.0.0 and keeps your games, streaks, and history.

## Changes
- **Accurate failed-puzzle & near-reset logging.** Failed share results (such as `X/6`) now log as **Missed** instead of **Solved**, logs recorded around a reset attach to the puzzle cycle you opened, every log includes **Undo**, and past days can be edited from the calendar.
- **Safer edits, imports & atomic saves.** Editing a game's URL preserves your custom title and reset schedule, importing a backup asks for confirmation (keeping history and supporting Undo), and state saves retry automatically on I/O errors.
- **Popup windows, persistent ad hiding & reliable reset alerts.** Sign-in popups and new-window links open cleanly in an overlay WebView without replacing the game, hidden ads stay hidden after **Reload**, game downloads work inside the player, and reset notifications deduplicate across time-zone travel and catch up after device reboots.
- **Responsive layout & localization polish.** Fixed countdown and label clipping on narrow screens and large font sizes, and improved right-to-left game names, plurals, and localized calendar labels.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`

---

# Dailies 1.0.0

The first public release of Dailies — one home for every daily web puzzle you play, with no account and no tracking. (If upgrading from an earlier private 2.x build, export a `.json` backup in the old app first, uninstall it, install 1.0.0, and use **Settings → Data & Backup → Import Backup**.)

## Changes
- **Daily Hub & automatic link metadata.** Add any daily web puzzle by URL to automatically fill in its name, icon, category, and reset schedule, with live countdowns to the next puzzle.
- **Time-zone-accurate reset schedules & alerts.** Tracks each game's reset in its own published time zone (`local`, `UTC`, or named zone), displays it in your local time, and sends a notification when a new puzzle is ready.
- **Built-in ad-blocking game player & auto-logging.** Opens games in an ad-free in-app WebView with a per-site shield toggle and automatically captures your result when you tap the game's **Share** button.
- **Completion calendar, streaks & local backups.** Tracks current and best streaks on a monthly calendar alongside full score history, stored 100% locally on your phone with `.json` export and import.

## Requirements
- Android 7.0 or newer.
- Signing certificate SHA-256: `5F:1C:D5:43:10:F2:A0:B3:10:13:39:7B:C3:2E:34:C2:B4:02:80:10:FF:C3:2E:F9:F9:1F:17:7E:68:B9:1A:BF`
