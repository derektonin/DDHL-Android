# DDHL Android app

The DDHL website inside an Android app, plus alerts, a home-screen widget and game-night live mode.
It only reads the site's public data; nothing on the website needs to change. Sideloaded (no Play Store).

## Install on a phone

1. On the phone, open https://github.com/derektonin/DDHL-Android/releases/latest and tap the `.apk` file
   (or copy the APK over by email, Drive, Discord or USB cable).
2. Open it. Android will ask you to allow installs from that app (Files, Gmail, Chrome…). Allow it once.
3. Tap **Install**. Google Play Protect may warn about an unknown developer. Tap **More details → Install anyway**.
4. Open **DDHL**, allow notifications, and pick your team.
5. Recommended: ⋮ menu → **Keep alerts reliable (battery)** → Allow.

Android 8.0 or newer. **If you installed the 0.1 test version, uninstall it first.** Versions 0.2 and
later are signed with the permanent DDHL key, so updates install over the top.

## Features

**Alerts.** Each can be switched on or off in ⋮ → Alert settings, and each has its own channel under
Android Settings → Apps → DDHL → Notifications.

| Alert | Example |
|---|---|
| Daily recap posted | "Daily recap is up: Tue, Nov 10 · Player of the night: Juuse Saros" |
| Power rankings updated | "New rankings for November 9, 2026" |
| Lead changes + final result | "You took the lead vs The Barrage!" / "Period 7 final: Win 7-4-1" |
| My players score | "Quinn Hughes: PP assist (+1 A, +1 STP4)", including goalie wins and shutouts |
| Category flips | "You take the lead in plus/minus (TEMU 3 – BAR 2)" |

**Smarter timing.** The app wakes every 15 minutes but only contacts the site when it's worth it: every
15 minutes while NHL games are on, every 30 minutes on quiet days, every 2 hours overnight (2–8 am), and
right at the first puck drop the site lists.

**Game-night live mode.** Switches on automatically about 5 minutes after the night's first game starts,
or as soon as one of your players is on the ice. It pins your live score in the notification shade
("TEMU 4-7-1 vs BAR · Trailing · Win chance 8% · 2 of your games live"), checks every 2 minutes, and
sends scoring and category alerts right away. It turns itself off 15 minutes after your last game ends.
**Stop for tonight** on the notification turns it off until 6 am. You can also start it from the ⋮ menu.
Data use: about 2 MB per hour while it's on.

**Home-screen widget.** Long-press the home screen → Widgets → DDHL. It shows both teams' logos (from the
league site), your matchup score, lead, win chance, standings place and a LIVE badge during games. Tap it
to open Live scoring.

**Update checker.** Once a day the app checks this repository's latest GitHub release. When a new version
is available, it notifies you, then downloads and installs it in two taps. ⋮ → Check for app update
checks right away.

## Menu (⋮ top right)

Refresh · Alert settings · Start/Stop live mode · Keep alerts reliable (battery) · Check for app update ·
Check the site now · Test alerts (simulate updates). Test alerts makes each enabled alert fire once
using real data, which is handy for a demo.

## Download

Latest version: **https://github.com/derektonin/DDHL-Android/releases/latest**. Open it on the phone and tap
the `.apk` file. Once installed, the app updates itself.

## Publishing an update

Updates are published as **GitHub Releases** on this repository. The app checks
`releases/latest/download/update.json` once a day. When that file lists a higher `versionCode`, the app
offers the new APK.

Requirements on the build PC: JDK 17, the Android SDK, Git and GitHub CLI (`gh`, signed in once with
`gh auth login`). All are already set up on the PC that built 0.3.

1. In `app/build.gradle.kts`, raise `versionCode` by 1 and set a new `versionName` (e.g. 4 / "0.4").
2. Run `.\release.ps1 -Notes "What's new"`.

That's all. The script builds and signs the APK, writes `update.json`, pushes the code to GitHub, and
creates the release `v0.4` with both files attached. Phones pick it up within a day, or right away with
⋮ → Check for app update.

## ⚠ The signing key

`keystore/ddhl-release.jks` plus `keystore.properties` (its password) are what make updates possible.
**Back both up somewhere safe** (a password manager or a private cloud folder). If they're lost, nobody
can update. Everyone would have to uninstall and reinstall. Never share them publicly.

## If alerts stop arriving

Some phones (Samsung, Xiaomi, OnePlus, Huawei) aggressively stop background apps. Use ⋮ → Keep alerts
reliable, and set battery usage to **Unrestricted** (Settings → Apps → DDHL → Battery).

## Where things are

| File | What it does |
|---|---|
| `Ddhl.kt` | Site address, data sources, update link |
| `LiveModel.kt` | Reads `/api/live`: your matchup, plays, category changes, standings, game times |
| `LiveAlerts.kt` | Lead, final, player and category alerts |
| `CheckWorker.kt` | Background checks, smarter timing, recap and rankings alerts |
| `GameNight.kt`, `LiveModeService.kt` | Game-night live mode |
| `Widget.kt` + `res/layout/widget_matchup.xml` | Home-screen widget |
| `Updater.kt` | Update check, download and install |
| `MainActivity.kt` | The in-app browser, menu and settings |
| `res/xml/network_security_config.xml` | Trusts the site's Let's Encrypt root on older phones |

If the site moves to a domain name, change `HOST` in `Ddhl.kt` and release an update.

## Known limits

- Background alerts can be up to 15 minutes late outside live mode (Android's limit for phone-side checks).
- On phones that put apps into deep sleep, live mode can pause when the phone has been face-down and
  idle for a long time. The battery setting above prevents this.
- The app depends on the site's current data format. If your friend renames fields in `/api/live` or
  `today.json`, an alert type may quietly stop until the app is updated.
