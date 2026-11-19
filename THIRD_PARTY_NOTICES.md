# Third-party notices

Dailies is licensed under GPL-3.0 (see `LICENSE`). It bundles the following third-party material,
each under its own license:

| Component | Where | License |
|---|---|---|
| EasyList and EasyPrivacy filter lists | `app/src/main/assets/adblock/` (compiled) | GPL-3.0 / CC BY-SA 3.0 (dual), github.com/easylist/easylist |
| AdGuard Base filter | `app/src/main/assets/adblock/` (compiled) | GPL-3.0, github.com/AdguardTeam/AdguardFilters |
| HaGeZi DNS blocklists (Multi PRO) | `app/src/main/assets/adblock/` (compiled) | GPL-3.0, github.com/hagezi/dns-blocklists |
| StevenBlack unified hosts | `app/src/main/assets/adblock/` (compiled) | MIT, github.com/StevenBlack/hosts |
| Public Suffix List | `app/src/main/assets/adblock/psl.txt` | MPL-2.0, publicsuffix.org |
| Google Sans Flex, Google Sans Code | `app/src/main/assets/fonts/`, `app/src/main/res/font/` | SIL Open Font License 1.1 (license texts next to the fonts) |
| Material Symbols Rounded | `design/icons/`, app drawables and inline hub icons | Apache License 2.0 (`design/icons/LICENSE-MaterialSymbols.txt`) |
| AndroidX libraries (core, activity, webkit, work) | Gradle dependencies | Apache License 2.0 |
| ZXing Core (`com.google.zxing:core:3.5.4`) | Gradle dependency (QR codes for shared game lists) | Apache License 2.0, github.com/zxing/zxing |
| ML Kit Text Recognition (`com.google.android.gms:play-services-mlkit-text-recognition:19.0.2`) | Gradle dependency (on-device OCR for reading results from screenshots) | Google Play services SDK / Android Software Development Kit License (proprietary, non-free) |

The filter lists are re-encoded by `tools/build_adblock.py` (hostnames as 64-bit hashes, a supported
subset of rules as text). Their meaning is unchanged. Thanks to all list maintainers.

Note for downstream / F-Droid builders: `play-services-mlkit-text-recognition` is a non-free Google
Play services dependency used solely by `ScreenshotReader.kt` for on-device screenshot OCR.

"Google Sans" and "Material" are trademarks of Google LLC. This app isn't affiliated with or endorsed
by Google or by any of the games it can open.
