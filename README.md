# Not-a-Thing Launcher

[![Build and Release APK](https://github.com/alakmar344/Not-a-thing-launcher/actions/workflows/build.yml/badge.svg)](https://github.com/alakmar344/Not-a-thing-launcher/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/alakmar344/Not-a-thing-launcher?label=Latest%20Release&color=d71921)](https://github.com/alakmar344/Not-a-thing-launcher/releases/latest)

A Nothing OS–inspired Android home screen: monochrome, dot-matrix, tactile — with hardware
phosphor accents used where something needs your attention.

Under the hood it is **stock AOSP Launcher3** (`android-14.0.0_r75`) with the Not a Thing
experience layered on top, so workspaces, folders, drag & drop, widget resizing, and app
shortcuts follow stock Android cleanly.

**Version 13** · runs on Android 8.0 – 17 · targets Android 16 (API 36)

<p>
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_homeMono.png" width="24%" alt="Home" />
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_drawer.png" width="24%" alt="App drawer" />
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_customizeSheet.png" width="24%" alt="Customize" />
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensLightTest_homeMono.png" width="24%" alt="Light mode" />
</p>

## What you get in v13

- **`-1` Essential Space:** Swipe right from page 0 for daily briefing (weather, alarm, calendar), hardware toggles (torch, focus mode, caffeine, ringer), scratchpad note, local voice memos, live Pomodoro timer & stopwatch, and storage/RAM/screen-time vitals.
- **11 dot-matrix widgets:** Clock (Digital / Analog / Word / World), Battery & Calendar, Weather (compact `2×2` + wide `4×2+` 12h sparkline), Media Player (Bayer 4×4 halftone art & output route), Hardware Toggles, Focus Timer, Essential Note, Habit Matrix, Device Vitals, Compass & Steps, and Essential Stack folder.
- **Offline Command Search & Smart Drawer:** Calculator (`15% of 240`), unit/temperature conversions, city timezones (`time tokyo`), device quick actions, contacts, web search, acronym & 1-typo fuzzy app matching, Grid ↔ Minimal List drawer toggle, recent apps strip, and biometric Hidden Vault.
- **Tactile personalization:** 5 hardware accent palettes (`RED`, `AMBER`, `PHOSPHOR`, `COBALT`, `MONO`), Monochrome / Colour / **13×13 Dot-Matrix Glyph** icons, third-party ADW/Nova icon packs, custom app labels, `SpringAnimation` physics + mechanical click audio, interactive dot-grid parallax & ripples, configurable home gestures + biometric-safe Accessibility lock, 4 starter presets, and `.nothing.json` backup/restore.

Full guide: [`docs/FEATURES.md`](docs/FEATURES.md).

## Install and update

Download **`app-release.apk`** from the newest [release](https://github.com/alakmar344/Not-a-thing-launcher/releases)
and set it as your home app (or launch **Not a Thing** from the drawer to open the guided Setup & Permissions Hub).
Every push to `main` or matching `v*` tag builds signed APKs that install over the previous build.
See [`docs/RELEASING.md`](docs/RELEASING.md) for signing and upgrade details.

> Coming from a release before 11.0? Uninstall it once first: those builds used throwaway keys.

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
run non-Paparazzi unit/Robolectric tests such as `VisualTierTest`, `LauncherRuntimeTest`, and
`V13FeaturesTest`).

## Docs

| | |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Source layout, AOSP extension points, v13 subsystems, and R8 reflection contract |
| [`docs/FEATURES.md`](docs/FEATURES.md) | Complete guide to v13 widgets, Essential Space, Search, Vault, and Settings |
| [`docs/SCREENSHOTS.md`](docs/SCREENSHOTS.md) | Visual golden tests and host notes |
| [`docs/RELEASING.md`](docs/RELEASING.md) | How builds are published and installed as updates; signing |
| [`CHANGELOG.md`](CHANGELOG.md) | Release history through v13 |
| [`AGENTS.md`](AGENTS.md) | Rules for AI coding agents working in this repo |
| [`docs/archive/`](docs/archive/) | Archived documentation for v12 and pre-v11.0 releases |

## Project layout

| Path | What it is |
|---|---|
| `app/` | The Not a Thing app (`com.nothinglauncher`): theme, `-1` overlay, 11 widgets, search, drawer, vault, settings |
| `launcher3/`, `iconloader/`, `animationlib/` | Gradle modules compiling upstream AOSP sources (`launcher3/src` holds stand-ins and `src_ui_overrides`) |
| `third_party/aosp/` | Unmodified AOSP Launcher3 + SystemUI libraries (`android-14.0.0_r75`) |
| `tools/` | Generators for Launcher3's flags and theme; R8 release-reflection verification |
| `keystore/` | Shared signing key (`stable-signing.p12`) for in-place updates |

Fonts: Space Grotesk and Space Mono, SIL Open Font License (`licenses/`).
