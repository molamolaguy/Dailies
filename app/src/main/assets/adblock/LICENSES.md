# Ad-blocking data sources

Compiled by `tools/build_adblock.py` (see `sources.json` for the build date and per-list counts).

| List | Source | Licence |
|---|---|---|
| EasyList (all components) | github.com/easylist/easylist | GPL-3.0 / CC BY-SA 3.0 (dual) |
| EasyPrivacy (all components) | github.com/easylist/easylist | GPL-3.0 / CC BY-SA 3.0 (dual) |
| AdGuard Base filter | github.com/AdguardTeam/FiltersRegistry | GPL-3.0 |
| HaGeZi Multi PRO (domains) | github.com/hagezi/dns-blocklists | GPL-3.0 |
| StevenBlack unified hosts | github.com/StevenBlack/hosts | MIT (data: CC BY 3.0 where noted) |
| Public Suffix List (`psl.txt`) | publicsuffix.org | MPL-2.0 |

Thanks to all list maintainers. The lists are bundled unmodified in meaning; the build step only
re-encodes them (hostnames as 64-bit FNV-1a hashes, supported rule subset as text).
