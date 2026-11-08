#!/usr/bin/env bash
# Publishes this project to your GitHub account as a PUBLIC repository and creates the v1.0.0 release
# with the APK attached. Run it from anywhere:
#
#   bash scripts/publish-to-github.sh [path/to/Dailies-1.0.0.apk] [path/to/Dailies_signing_KEEP_PRIVATE]
#
# Needs git and the GitHub CLI (https://cli.github.com). It signs you in to GitHub if needed.
# If you pass the signing folder, the key is also stored as encrypted repository secrets so the
# Release workflow can build signed updates when you push a tag (optional; the key itself is never committed).
set -euo pipefail

REPO_NAME="${REPO_NAME:-dailies}"
DESCRIPTION="One home for every daily web puzzle: reset alerts, streaks, auto-logged results and an ad-blocking game player for Android."
VERSION="1.0.0"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${1:-$ROOT/../Dailies-$VERSION.apk}"
SIGNING_DIR="${2:-}"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
die() { printf '\n\033[31mError:\033[0m %s\n' "$*" >&2; exit 1; }

command -v git >/dev/null || die "git is not installed. Get it from https://git-scm.com/downloads"
command -v gh  >/dev/null || die "The GitHub CLI is not installed. Get it from https://cli.github.com (then re-run this script)"
[ -f "$APK" ] || die "APK not found at $APK. Pass its path as the first argument."

cd "$ROOT"

say "1/5  Signing in to GitHub"
gh auth status >/dev/null 2>&1 || gh auth login --hostname github.com --git-protocol https --web
gh auth setup-git >/dev/null 2>&1 || true
LOGIN="$(gh api user --jq .login)"
echo "Signed in as $LOGIN"

say "2/5  Preparing the git repository"
if [ ! -d .git ]; then
  git init -q -b main
fi
# Use GitHub's private no-reply address for the commit unless you've set your own.
if [ -z "$(git config user.email || true)" ]; then
  ID="$(gh api user --jq .id)"
  git config user.name  "$(gh api user --jq '.name // .login')"
  git config user.email "$ID+$LOGIN@users.noreply.github.com"
fi
for f in local.properties '*.jks' '*.keystore' signing.properties; do
  if git ls-files --error-unmatch $f >/dev/null 2>&1; then die "$f is tracked by git; remove it before publishing."; fi
done
git add -A
if ! git diff --cached --quiet; then
  git commit -q -m "Dailies $VERSION"
fi
git branch -M main

say "3/5  Creating https://github.com/$LOGIN/$REPO_NAME (public)"
if gh repo view "$LOGIN/$REPO_NAME" >/dev/null 2>&1; then
  echo "Repository already exists, pushing to it."
  git remote get-url origin >/dev/null 2>&1 || git remote add origin "https://github.com/$LOGIN/$REPO_NAME.git"
  git push -u origin main
else
  gh repo create "$REPO_NAME" --public --description "$DESCRIPTION" --source . --remote origin --push
fi
gh repo edit "$LOGIN/$REPO_NAME" --add-topic android --add-topic daily-games --add-topic wordle --add-topic ad-blocker >/dev/null 2>&1 || true

say "4/5  Signing secrets for automatic releases (optional)"
if [ -n "$SIGNING_DIR" ] && [ -f "$SIGNING_DIR/dailies-release.jks" ] && [ -f "$SIGNING_DIR/signing.properties" ]; then
  prop() { grep "^$1=" "$SIGNING_DIR/signing.properties" | cut -d= -f2-; }
  base64 < "$SIGNING_DIR/dailies-release.jks" | tr -d '\n' | gh secret set RELEASE_KEYSTORE_BASE64 -R "$LOGIN/$REPO_NAME"
  prop release.storePassword | gh secret set RELEASE_STORE_PASSWORD -R "$LOGIN/$REPO_NAME"
  prop release.keyAlias      | gh secret set RELEASE_KEY_ALIAS -R "$LOGIN/$REPO_NAME"
  prop release.keyPassword   | gh secret set RELEASE_KEY_PASSWORD -R "$LOGIN/$REPO_NAME"
  echo "Secrets saved. Pushing a tag like v1.0.1 will now build and publish a signed APK."
else
  echo "Skipped (no signing folder given). You can still build releases on your own computer."
fi

say "5/5  Publishing release v$VERSION with the APK"
cp "$APK" "${TMPDIR:-/tmp}/Dailies-$VERSION.apk"
if gh release view "v$VERSION" -R "$LOGIN/$REPO_NAME" >/dev/null 2>&1; then
  gh release upload "v$VERSION" "${TMPDIR:-/tmp}/Dailies-$VERSION.apk" --clobber -R "$LOGIN/$REPO_NAME"
else
  gh release create "v$VERSION" "${TMPDIR:-/tmp}/Dailies-$VERSION.apk" -R "$LOGIN/$REPO_NAME" \
    --title "Dailies $VERSION" --notes-file RELEASE_NOTES.md --latest
fi

say "Done!"
echo "Repository: https://github.com/$LOGIN/$REPO_NAME"
echo "Download:   https://github.com/$LOGIN/$REPO_NAME/releases/latest"
