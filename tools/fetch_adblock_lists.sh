#!/usr/bin/env bash
# Downloads the public filter lists the ad blocker is built from into a folder, for
#   python3 tools/build_adblock.py <folder>
# Usage: tools/fetch_adblock_lists.sh <folder>
set -euo pipefail
out="${1:?usage: fetch_adblock_lists.sh <folder>}"
mkdir -p "$out"
get() { curl -fsSL --retry 4 --retry-delay 5 -o "$out/$1" "$2"; echo "  $1"; }

echo "Downloading filter lists into $out"
get stevenblack_hosts https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts
get hagezi_pro.txt https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt
get adguard_base.txt https://filters.adtidy.org/extension/chromium/filters/2.txt
get public_suffix_list.dat https://publicsuffix.org/list/public_suffix_list.dat

EL=https://raw.githubusercontent.com/easylist/easylist/master
for f in adservers adservers_popup allowlist allowlist_dimensions allowlist_general_hide allowlist_popup \
         general_block general_block_dimensions general_block_popup general_hide specific_block \
         specific_block_popup specific_hide thirdparty thirdparty_popup; do
  get "easylist_easylist_$f.txt" "$EL/easylist/easylist_$f.txt"
done
for f in allowlist allowlist_international general general_emailtrackers specific specific_international \
         thirdparty thirdparty_international trackingservers trackingservers_admiral \
         trackingservers_international trackingservers_thirdparty; do
  get "easyprivacy_easyprivacy_$f.txt" "$EL/easyprivacy/easyprivacy_$f.txt"
done
