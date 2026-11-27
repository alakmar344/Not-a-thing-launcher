# Not-a-Thing Launcher

[![Build and Release APK](https://github.com/alakmar344/Not-a-thing-launcher/actions/workflows/build.yml/badge.svg)](https://github.com/alakmar344/Not-a-thing-launcher/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/alakmar344/Not-a-thing-launcher?label=Latest%20Release&color=d71921)](https://github.com/alakmar344/Not-a-thing-launcher/releases/latest)

An independent, minimalist dot-matrix Android home screen built for phones first and adaptive on big screens (tablets, foldables, and docked displays): monochrome, dot-matrix, tactile — with hardware phosphor accents used where something needs your attention.

Under the hood it is **stock AOSP Launcher3** (`android-14.0.0_r75`) with the Not a Thing experience layered on top, so workspaces, folders, drag & drop, widget resizing, and app shortcuts follow stock Android cleanly.

**Version 14.1** · runs on Android 8.0 – 17 · targets Android 16 (API 36)

<p>
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_homeMono.png" width="24%" alt="Home" />
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_drawer.png" width="24%" alt="App drawer" />
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_customizeSheet.png" width="24%" alt="Customize" />
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensLightTest_homeMono.png" width="24%" alt="Light mode" />
</p>

## What you get in v14.1

- **Security Hardening & Signed Key Rotation:** Retired the former public repository keystore in favor of a newly generated 4096-bit RSA release key (`notathing-release`) stored in GitHub Actions secrets, enforced fail-closed Hidden Vault authentication across Android 8–17 and all entry points, added signature-level permissions to `GridCustomizationsProvider`, revoked stale notification digest intents on permission loss, and bounded all backup/HTTP/JSON parsers.
- **Android StandBy Screensaver (`NothingDreamService`) & 4-Stage Matrix Desk HUD (`DeskHudActivity`):** Turn any charging or docked phone into a dark OLED clock console (**StandBy Dot Clock**) with per-minute burn-in pixel shift, weather, battery, and notification digest — or open the 4-stage Desk HUD (**COCKPIT**, **CLOCK** theatre, **FOCUS** Pomodoro studio, and procedural **MATRIX** LED visualizer reactive to music, soundscapes, timer progress, and touch ripples).
- **13 Dot-Matrix Widgets & Kinetic Matrix Playground:** Interactive **Kinetic Matrix (`KineticMatrixWidget`)** (`GRAVITY` LED beads, Conway's `LIFE`, playable `SNAKE`), **Desk Briefing (`DeskBriefingWidget`)**, **App Stack** Max Icon mode, **Focus Note**, **Habit Matrix**, 12h rain/wind telemetry in `WeatherWidget`, live cross-surface sync, and per-widget surfaces (**Default**, **Obsidian Dark**, **Bone Paper**, **Frosted Glass**).
- **On-Device Notification Digest & Procedural Focus Soundscapes:** Swipe right into `-1` Dock Space for an offline priority-grouped **Notification Digest** (`NotificationDigestRepository` powered by AOSP `NotificationListener`, with Focus Shield when Focus Mode is on) and zero-asset `AudioTrack` focus soundscapes (`FocusSoundEngine`: **BROWN**, **RAIN**, **40HZ**).
- **Big-Screen Studio (`w600dp`+), Smart Drawer & Android 8–11 Compatibility:** Dual-column Bento studio layouts on tablets/foldables, instant **GRID ↔ LIST ↔ CATS** drawer switching (`AppCategorizer` with package caching), sequence-guarded `/` Command Search utilities, and concrete pre-API-31 `wallpaper_popup_scrim` theme overrides.

Full guide: [`docs/FEATURES.md`](docs/FEATURES.md).

## Install and update

Download **`app-release.apk`** from the newest [release](https://github.com/alakmar344/Not-a-thing-launcher/releases)
and set it as your home app (or launch **Not a Thing** from the drawer to open the guided Setup & Permissions Hub).
Every push to `main` or matching `v*` tag builds signed APKs that install over the previous build.
See [`docs/RELEASING.md`](docs/RELEASING.md) for signing and upgrade details.

> Upgrading from v14 or earlier? Uninstall once before installing v14.1: v14.1 rotates to a dedicated secret-managed release signing key.

## Build

Requires JDK 17 and Android SDK (`compileSdk 35`, `minSdk 26`, `targetSdk 36`).

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # R8-minified APK + verifyReleaseReflection check
./gradlew testDebugUnitTest    # unit, Robolectric, and Paparazzi golden tests
./gradlew recordPaparazziDebug # re-record screenshot goldens after a visual change
```

On Windows use `gradlew.bat`. Paparazzi screenshots run on JVM layoutlib without an emulator
(note: Windows Application Control environments that block `libicuuc_stubdata.dll` can still
run non-Paparazzi unit/Robolectric tests such as `VisualTierTest`, `LauncherRuntimeTest`,
`V13FeaturesTest`, and `V14FeaturesTest`).

## Docs

| | |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Source layout, AOSP extension points, v14.1 subsystems, and R8 reflection contract |
| [`docs/FEATURES.md`](docs/FEATURES.md) | Complete guide to StandBy Screensaver, Kinetic Matrix, Digest, Soundscapes, Matrix Desk HUD, 13 widgets, Big-Screen Studio & Settings |
| [`docs/SCREENSHOTS.md`](docs/SCREENSHOTS.md) | Visual golden tests (phone & tablet) and host notes |
| [`docs/RELEASING.md`](docs/RELEASING.md) | How builds are published and installed as updates; GitHub secret signing |
| [`CHANGELOG.md`](CHANGELOG.md) | Release history through v14.1 |
| [`NOTICE`](NOTICE) | Third-party copyrights, AOSP Apache 2.0 & font attributions, and trademark disclaimer |
| [`AGENTS.md`](AGENTS.md) | Rules for AI coding agents working in this repo |
| [`docs/archive/`](docs/archive/) | Archived documentation for v13, v12, and pre-v11.0 releases |

## Project layout

| Path | What it is |
|---|---|
| `app/` | The Not a Thing app (`com.nothinglauncher`): theme, `-1` Dock Space overlay, StandBy Dream, Matrix Desk HUD, 13 widgets, search, drawer, vault, settings |
| `launcher3/`, `iconloader/`, `animationlib/` | Gradle modules compiling upstream AOSP sources (`launcher3/src` holds stand-ins and `src_ui_overrides`) |
| `third_party/aosp/` | Unmodified AOSP Launcher3 + SystemUI libraries (`android-14.0.0_r75`) |
| `tools/` | Generators for Launcher3's flags and theme; R8 release-reflection verification |
| `keystore/` | Local release signing directory (git-ignored; CI loads signing key from GitHub Actions secrets) |

Fonts: Space Grotesk and Space Mono, SIL Open Font License (`licenses/`). See [`NOTICE`](NOTICE).
