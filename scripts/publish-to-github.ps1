# Publishes this project to your GitHub account as a PUBLIC repository and creates the v1.0.0 release
# with the APK attached. In PowerShell:
#
#   powershell -ExecutionPolicy Bypass -File scripts\publish-to-github.ps1 [-Apk path\to\Dailies-1.0.0.apk] [-SigningDir path\to\Dailies_signing_KEEP_PRIVATE]
#
# Needs Git (https://git-scm.com/download/win) and the GitHub CLI (https://cli.github.com, or: winget install GitHub.cli).
param(
  [string]$Apk = "",
  [string]$SigningDir = "",
  [string]$RepoName = "dailies"
)
$ErrorActionPreference = "Stop"
$Version = "1.0.0"
$Description = "One home for every daily web puzzle: reset alerts, streaks, auto-logged results and an ad-blocking game player for Android."
$Root = Split-Path -Parent $PSScriptRoot
if (-not $Apk) { $Apk = Join-Path (Split-Path -Parent $Root) "Dailies-$Version.apk" }

function Say($m) { Write-Host "`n$m" -ForegroundColor Cyan }
function Die($m) { Write-Host "`nError: $m" -ForegroundColor Red; exit 1 }

if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Die "Git is not installed. Get it from https://git-scm.com/download/win" }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Die "The GitHub CLI is not installed. Run: winget install GitHub.cli  (then open a new PowerShell window)" }
if (-not (Test-Path $Apk)) { Die "APK not found at $Apk. Pass it with -Apk." }
$Apk = (Resolve-Path $Apk).Path
Set-Location $Root

Say "1/5  Signing in to GitHub"
gh auth status 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { gh auth login --hostname github.com --git-protocol https --web }
gh auth setup-git 2>$null | Out-Null
$Login = gh api user --jq .login
Write-Host "Signed in as $Login"

Say "2/5  Preparing the git repository"
if (-not (Test-Path ".git")) { git init -q -b main }
$email = git config user.email
if (-not $email) {
  $id = gh api user --jq .id
  $name = gh api user --jq '.name // .login'
  git config user.name "$name"
  git config user.email "$id+$Login@users.noreply.github.com"
}
git add -A
git diff --cached --quiet
if ($LASTEXITCODE -ne 0) { git commit -q -m "Dailies $Version" }
git branch -M main

Say "3/5  Creating https://github.com/$Login/$RepoName (public)"
gh repo view "$Login/$RepoName" 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) {
  git remote get-url origin 2>$null | Out-Null
  if ($LASTEXITCODE -ne 0) { git remote add origin "https://github.com/$Login/$RepoName.git" }
  git push -u origin main
} else {
  gh repo create $RepoName --public --description "$Description" --source . --remote origin --push
}
gh repo edit "$Login/$RepoName" --add-topic android --add-topic daily-games --add-topic wordle --add-topic ad-blocker 2>$null | Out-Null

Say "4/5  Signing secrets for automatic releases (optional)"
$jks = if ($SigningDir) { Join-Path $SigningDir "dailies-release.jks" } else { "" }
$props = if ($SigningDir) { Join-Path $SigningDir "signing.properties" } else { "" }
if ($SigningDir -and (Test-Path $jks) -and (Test-Path $props)) {
  $p = @{}
  Get-Content $props | Where-Object { $_ -match '^[^#].*=' } | ForEach-Object { $k, $v = $_ -split '=', 2; $p[$k] = $v }
  [Convert]::ToBase64String([IO.File]::ReadAllBytes($jks)) | gh secret set RELEASE_KEYSTORE_BASE64 -R "$Login/$RepoName"
  $p['release.storePassword'] | gh secret set RELEASE_STORE_PASSWORD -R "$Login/$RepoName"
  $p['release.keyAlias']      | gh secret set RELEASE_KEY_ALIAS -R "$Login/$RepoName"
  $p['release.keyPassword']   | gh secret set RELEASE_KEY_PASSWORD -R "$Login/$RepoName"
  Write-Host "Secrets saved. Pushing a tag like v1.0.1 will now build and publish a signed APK."
} else {
  Write-Host "Skipped (no -SigningDir given)."
}

Say "5/5  Publishing release v$Version with the APK"
$named = Join-Path $env:TEMP "Dailies-$Version.apk"
Copy-Item $Apk $named -Force
gh release view "v$Version" -R "$Login/$RepoName" 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) {
  gh release upload "v$Version" $named --clobber -R "$Login/$RepoName"
} else {
  gh release create "v$Version" $named -R "$Login/$RepoName" --title "Dailies $Version" --notes-file RELEASE_NOTES.md --latest
}

Say "Done!"
Write-Host "Repository: https://github.com/$Login/$RepoName"
Write-Host "Download:   https://github.com/$Login/$RepoName/releases/latest"
