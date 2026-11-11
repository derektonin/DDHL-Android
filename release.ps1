# Publishes a new version of the DDHL app to GitHub Releases. Phones with the app installed
# see it within a day (or right away via the menu's "Check for app update").
#
# 1. In app\build.gradle.kts, raise versionCode by 1 and set a new versionName
# 2. Run:   .\release.ps1 -Notes "What's new in this version"
#
# Builds the signed APK, writes update.json, commits and pushes the code, and creates the
# GitHub release with both files attached. Add -DryRun to only build into dist\ without publishing.
param(
    [string]$Notes = "",
    [switch]$DryRun
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$repo = "derektonin/DDHL-Android"
if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = Join-Path $env:LOCALAPPDATA "Programs\Microsoft\jdk-17.0.10.7-hotspot"
}
if (-not (Test-Path "keystore.properties")) { throw "keystore.properties is missing: releases must be signed with the DDHL key." }

$gradle = Get-Content "app\build.gradle.kts" -Raw
$code = [int]([regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value)
$name = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$tag = "v$name"

if (-not $DryRun) {
    $existing = gh release view $tag --repo $repo --json tagName 2>$null
    if ($existing) { throw "Release $tag already exists. Bump versionCode/versionName in app\build.gradle.kts first." }
}

.\gradlew.bat assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

New-Item -ItemType Directory -Force dist | Out-Null
Get-ChildItem dist | Remove-Item -Force
$apkName = "DDHL-$name.apk"
Copy-Item "app\build\outputs\apk\release\app-release.apk" "dist\$apkName" -Force
[ordered]@{
    versionCode = $code
    versionName = $name
    apkUrl      = "https://github.com/$repo/releases/download/$tag/$apkName"
    notes       = $Notes
} | ConvertTo-Json | Set-Content "dist\update.json" -Encoding utf8

if ($DryRun) { Write-Host "Built dist\$apkName and dist\update.json (not published)."; return }

git add -A
git commit -m "Release $name" --allow-empty | Out-Null
git push origin main
if ($LASTEXITCODE -ne 0) { throw "git push failed" }

$releaseNotes = if ($Notes) { $Notes } else { "DDHL app $name" }
gh release create $tag "dist\$apkName" "dist\update.json" --repo $repo --title "DDHL $name" --notes $releaseNotes
if ($LASTEXITCODE -ne 0) { throw "Creating the GitHub release failed" }
Write-Host ""
Write-Host "Published $tag. Download link to share: https://github.com/$repo/releases/latest"
